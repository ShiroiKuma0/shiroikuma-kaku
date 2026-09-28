package shiroikuma.kaku.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ca.fuwafuwa.kaku.TESS_FOLDER_NAME
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp

/**
 * MangaOCR on the device: kha-white/manga-ocr-base (Apache-2.0) — the same model 白い熊's desktop
 * apps use — as the int8 ONNX pair exported by onnx-community/manga-ocr-base-ONNX, run by ONNX
 * Runtime on the CPU. No network: the three files are imported by hand (UI page → OCR) into the
 * OCR data directory, where the Export / Import "OCR data" category carries them.
 *
 * The pipeline is the one verified on the desktop against the PyTorch model (2026-09-28, ORT 1.27
 * and 1.30, identical output): grayscale → RGB, bilinear 224×224, (v/255 − 0.5)/0.5, NCHW; the
 * encoder once; then greedy decoding from [CLS] (2) until [SEP] (3), at most 300 tokens, the
 * decoder fed the whole prefix each step (it has only two layers, so no KV cache is needed).
 * Tokens are single characters (vocab.txt, one per line; 0–4 are special). The post-processing is
 * manga-ocr's: whitespace dropped, "…" → "...", ASCII to full width.
 *
 * For each character the top [TOP_K] candidates of that step's softmax are kept with their
 * probabilities — they fill the kanji-choice window as Tesseract's alternatives did.
 */
object MangaOcr
{
    private const val TAG = "MangaOcr"

    const val ENCODER = "mangaocr-encoder.onnx"
    const val DECODER = "mangaocr-decoder.onnx"
    const val VOCAB = "mangaocr-vocab.txt"

    /** Where each file comes from — shown on the UI page, to copy into a browser. */
    const val URL_ENCODER = "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/encoder_model_quantized.onnx"
    const val URL_DECODER = "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/decoder_model_quantized.onnx"
    const val URL_VOCAB = "https://huggingface.co/kha-white/manga-ocr-base/resolve/main/vocab.txt"

    private const val SIZE = 224
    private const val CLS = 2L
    private const val SEP = 3
    private const val FIRST_REAL = 5
    private const val MAX_TOKENS = 300
    private const val TOP_K = 8

    /** One recognised character and its candidates (character → probability, best first). */
    data class Char(val text: String, val choices: List<Pair<String, Double>>)

    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var vocab: List<String> = emptyList()
    private var loadedStamp = 0L

    fun dir(context: Context) = File(context.applicationContext.filesDir, TESS_FOLDER_NAME)

    fun file(context: Context, name: String) = File(dir(context), name)

    /** All three files are present. */
    fun installed(context: Context): Boolean =
            listOf(ENCODER, DECODER, VOCAB).all { file(context, it).let { f -> f.isFile && f.length() > 0 } }

    /** Drop the loaded sessions (after an import replaced a file). */
    fun unload()
    {
        synchronized(lock)
        {
            try { encoder?.close() } catch (ignored: Exception) {}
            try { decoder?.close() } catch (ignored: Exception) {}
            encoder = null
            decoder = null
            vocab = emptyList()
            loadedStamp = 0L
        }
    }

    private fun ensureLoaded(context: Context)
    {
        val stamp = listOf(ENCODER, DECODER, VOCAB).sumOf { file(context, it).lastModified() }
        if (encoder != null && decoder != null && stamp == loadedStamp) return
        unload()
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
        }
        encoder = e.createSession(file(context, ENCODER).absolutePath, options)
        decoder = e.createSession(file(context, DECODER).absolutePath, options)
        vocab = file(context, VOCAB).readLines(Charsets.UTF_8)
        loadedStamp = stamp
        Log.i(TAG, "loaded: vocab ${vocab.size}, encoder ${encoder!!.inputNames}, decoder ${decoder!!.inputNames}")
    }

    /**
     * Recognise the text in [bitmap] (one text block: a speech bubble, a line or a few lines of
     * dialogue, a vertical column). Throws when the model is missing or cannot run.
     */
    fun recognize(context: Context, bitmap: Bitmap): List<Char>
    {
        synchronized(lock)
        {
            ensureLoaded(context)
            val e = env!!
            val enc = encoder!!
            val dec = decoder!!

            val pixels = OnnxTensor.createTensor(e, preprocess(bitmap), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))
            val hidden: OnnxTensor = pixels.use {
                enc.run(mapOf(enc.inputNames.first() to it)).use { out ->
                    val t = out[0] as OnnxTensor
                    OnnxTensor.createTensor(e, t.floatBuffer, t.info.shape)
                }
            }

            val inputIds = dec.inputNames.firstOrNull { it.contains("input_ids") } ?: "input_ids"
            val hiddenName = dec.inputNames.firstOrNull { it.contains("encoder_hidden_states") } ?: "encoder_hidden_states"

            val ids = LongArray(MAX_TOKENS)
            ids[0] = CLS
            var n = 1
            val out = ArrayList<Char>()
            hidden.use {
                while (n < MAX_TOKENS)
                {
                    val idTensor = OnnxTensor.createTensor(e, LongBuffer.wrap(ids, 0, n), longArrayOf(1, n.toLong()))
                    val step = idTensor.use { idt ->
                        dec.run(mapOf(inputIds to idt, hiddenName to hidden)).use { res ->
                            val logits = (res[0] as OnnxTensor)
                            val shape = logits.info.shape            // [1, n, vocab]
                            val v = shape[2].toInt()
                            val buf = logits.floatBuffer
                            val row = FloatArray(v)
                            buf.position((n - 1) * v)
                            buf.get(row, 0, v)
                            row
                        }
                    }
                    var best = 0
                    for (i in step.indices) if (step[i] > step[best]) best = i
                    if (best == SEP) break
                    ids[n++] = best.toLong()
                    if (best >= FIRST_REAL && best < vocab.size) out.addAll(toChars(best, step))
                }
            }
            return out
        }
    }

    /** One token as display characters (post-processed), each carrying the step's top candidates. */
    private fun toChars(best: Int, logits: FloatArray): List<Char>
    {
        val text = post(vocab[best])
        if (text.isEmpty()) return emptyList()
        if (text.length > 1) return text.map { Char(it.toString(), listOf(it.toString() to 100.0)) }

        // Softmax over the row, then the best TOP_K real tokens as the candidates.
        var max = Float.NEGATIVE_INFINITY
        for (x in logits) if (x > max) max = x
        var sum = 0.0
        for (x in logits) sum += exp((x - max).toDouble())
        val order = (FIRST_REAL until minOf(logits.size, vocab.size)).sortedByDescending { logits[it] }.take(TOP_K)
        val choices = LinkedHashMap<String, Double>()
        for (i in order)
        {
            val c = post(vocab[i])
            if (c.length != 1) continue
            val p = exp((logits[i] - max).toDouble()) / sum * 100.0
            if (!choices.containsKey(c)) choices[c] = p
        }
        if (!choices.containsKey(text)) choices[text] = 100.0
        return listOf(Char(text, choices.entries.sortedByDescending { it.value }.map { it.key to it.value }))
    }

    /** manga-ocr's post-processing, per token: no whitespace, "…" → "...", ASCII → full width. */
    private fun post(token: String): String
    {
        val sb = StringBuilder()
        for (ch in token.replace("…", "..."))
        {
            if (ch.isWhitespace()) continue
            sb.append(if (ch.code in 0x21..0x7E) (ch.code + 0xFEE0).toChar() else ch)
        }
        return sb.toString()
    }

    /** Grayscale → RGB, bilinear to 224×224, normalised to [−1, 1], NCHW. */
    private fun preprocess(src: Bitmap): FloatBuffer
    {
        val scaled = Bitmap.createScaledBitmap(src, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== src) scaled.recycle()
        val gray = FloatArray(px.size)
        for (i in px.indices)
        {
            val p = px[i]
            val g = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
            gray[i] = g / 127.5f - 1f
        }
        val buf = FloatBuffer.allocate(3 * SIZE * SIZE)
        repeat(3) { buf.put(gray) }
        buf.rewind()
        return buf
    }
}
