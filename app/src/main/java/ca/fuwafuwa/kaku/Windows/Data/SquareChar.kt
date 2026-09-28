package ca.fuwafuwa.kaku.Windows.Data

interface ISquareChar
{
    var index: Int

    var char: String

    var text: String?

    var prev: ISquareChar?

    var next: ISquareChar?

    var userTouched: Boolean

    val displayData: DisplayData

    fun clone() : ISquareChar
}

open class SquareChar(override val displayData: DisplayData,
                      override var char: String) : ISquareChar
{
    override var index: Int = -1

    override var userTouched: Boolean = false

    override var text : String? = null
    get()
    {
        val returnText = field
        field = null
        return returnText
    }

    override var prev : ISquareChar? = null
    get()
    {
        return if (index == 0)
        {
            null
        }
        else
        {
            displayData.squareChars[index - 1]
        }
    }

    override var next: ISquareChar? = null
    get()
    {
        return if (index == displayData.count - 1)
        {
            null
        }
        else
        {
            displayData.squareChars[index + 1]
        }
    }

    override fun clone(): ISquareChar
    {
        return SquareChar(displayData, char)
    }
}

class SquareCharOcr(override val displayData: DisplayDataOcr,
                    val allChoices : MutableList<Pair<String, Double>>,
                    val bitmapPos: IntArray) : SquareChar(displayData, "")
{
    override var char : String = ""

    /**
     * The character as first recognised and shown (after the lookalike corrections), kept through
     * swaps: the character's own image in the kanji-choice window restores it.
     */
    var originalChar : String = ""

    init
    {
        sortChoices()
        char = allChoices[0].first
        originalChar = char
    }

    fun addChoice(char: String, certainty: ChoiceCertainty)
    {
        val matchIndex = allChoices.indexOfFirst { x -> x.first == char}

        if (certainty == ChoiceCertainty.CERTAIN)
        {
            if (matchIndex >= 0) allChoices.removeAt(matchIndex)

            allChoices.add(0, Pair(char, 100.0))
            this.char = char
        }
        else
        {
            if (matchIndex < 0)
            {
                allChoices.add(Pair(char, 0.0))
            }
        }
    }

    override fun clone(): ISquareChar
    {
        return SquareCharOcr(displayData, allChoices.toMutableList(), bitmapPos).also { it.originalChar = originalChar }
    }

    private fun sortChoices()
    {
        allChoices.sortByDescending { x -> x.second }
    }
}

enum class ChoiceCertainty
{
    CERTAIN,
    UNCERTAIN
}
