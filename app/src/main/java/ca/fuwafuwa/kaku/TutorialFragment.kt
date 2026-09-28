package ca.fuwafuwa.kaku

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/** One tutorial page: a title and its explanation (upstream's screen recordings were dropped). */
class TutorialFragment : Fragment()
{
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View?
    {
        val root = inflater.inflate(R.layout.fragment_tutorial, container, false)
        val pos = requireArguments().getInt(ARG_SECTION_NUMBER)

        root.findViewById<TextView>(R.id.tutorial_title).text = getString(TITLES[pos - 1])
        root.findViewById<TextView>(R.id.tutorial_text).text = getString(TEXTS[pos - 1])

        return root
    }

    companion object
    {
        private const val ARG_SECTION_NUMBER = "section_number"

        private val TITLES = intArrayOf(R.string.tut_title_1, R.string.tut_title_2, R.string.tut_title_3,
                R.string.tut_title_4, R.string.tut_title_5, R.string.tut_title_6, R.string.tut_title_7,
                R.string.tut_title_8, R.string.tut_title_9)
        private val TEXTS = intArrayOf(R.string.tut_text_1, R.string.tut_text_2, R.string.tut_text_3,
                R.string.tut_text_4, R.string.tut_text_5, R.string.tut_text_6, R.string.tut_text_7,
                R.string.tut_text_8, R.string.tut_text_9)

        /** Number of tutorial pages. */
        const val PAGES = 9

        fun newInstance(sectionNumber: Int): TutorialFragment
        {
            val fragment = TutorialFragment()
            val args = Bundle()
            args.putInt(ARG_SECTION_NUMBER, sectionNumber)
            fragment.arguments = args
            return fragment
        }
    }
}
