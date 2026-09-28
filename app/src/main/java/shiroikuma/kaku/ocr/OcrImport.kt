package shiroikuma.kaku.ocr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * Importing the MangaOCR files from local storage — the app has no network access. A picked file
 * is copied next to the others under its fixed name, written as `.part` and renamed only once it is
 * complete and has been checked: an ONNX file must open in ONNX Runtime, the vocabulary must have
 * the model's 6144 tokens. A bad pick therefore never replaces a working file.
 */
object OcrImport
{
    /** The display name of a picked document, or null. */
    fun displayName(context: Context, uri: Uri): String?
    {
        return try
        {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }
        catch (e: Exception)
        {
            null
        }
    }

    /** Which of the three files a picked name is (by the names the downloads carry), or null. */
    fun classify(name: String?): String?
    {
        val n = name?.lowercase() ?: return null
        return when
        {
            n.endsWith(".onnx") && n.contains("encoder") -> MangaOcr.ENCODER
            n.endsWith(".onnx") && n.contains("decoder") -> MangaOcr.DECODER
            n.endsWith(".txt") && n.contains("vocab") -> MangaOcr.VOCAB
            else -> null
        }
    }

    /** Copy [uri] in as [target] (one of the MangaOcr file names); returns the size in bytes. */
    @Throws(IOException::class)
    fun importFile(context: Context, uri: Uri, target: String): Long
    {
        val dir = MangaOcr.dir(context).apply { mkdirs() }
        val part = File(dir, "$target.part")
        try
        {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open the file")
            input.use { inp -> part.outputStream().use { out -> inp.copyTo(out, 256 * 1024) } }
            check(target, part)
            MangaOcr.unload()
            val dest = File(dir, target)
            if (!part.renameTo(dest)) throw IOException("cannot move ${part.name} into place")
            return dest.length()
        }
        finally
        {
            if (part.exists()) part.delete()
        }
    }

    private fun check(target: String, file: File)
    {
        if (target == MangaOcr.VOCAB)
        {
            val lines = file.readLines(Charsets.UTF_8).size
            if (lines < 6000) throw IOException("not the MangaOCR vocabulary ($lines lines, expected 6144)")
            return
        }
        val env = OrtEnvironment.getEnvironment()
        val session = try
        {
            env.createSession(file.absolutePath, OrtSession.SessionOptions())
        }
        catch (e: Exception)
        {
            throw IOException("not a usable ONNX model: ${e.message}")
        }
        session.use {
            val inputs = it.inputNames
            val ok = if (target == MangaOcr.ENCODER) inputs.any { n -> n.contains("pixel_values") }
                     else inputs.any { n -> n.contains("input_ids") } && inputs.any { n -> n.contains("encoder_hidden_states") }
            if (!ok) throw IOException("this is not the MangaOCR ${if (target == MangaOcr.ENCODER) "encoder" else "decoder"} (inputs: $inputs)")
        }
    }
}
