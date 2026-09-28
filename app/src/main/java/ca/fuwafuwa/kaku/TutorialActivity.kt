package ca.fuwafuwa.kaku

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import ca.fuwafuwa.kaku.databinding.ActivityTutorialBinding
import shiroikuma.kaku.applySystemBarPadding

class TutorialActivity : AppCompatActivity()
{
    inner class SectionsPagerAdapter(fm: FragmentManager) : FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT)
    {
        override fun getItem(position: Int): Fragment
        {
            if (position == 0){
                return TutorialWelcomeFragment.newInstance()
            }
            if (position in 1..TutorialFragment.PAGES)
            {
                return TutorialFragment.newInstance(position)
            }

            return TutorialEndFragment.newInstance()
        }

        override fun getCount(): Int
        {
            return TutorialFragment.PAGES + 2
        }
    }

    private lateinit var mSectionsPagerAdapter: FragmentStatePagerAdapter
    private lateinit var mBinding: ActivityTutorialBinding

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        mBinding = ActivityTutorialBinding.inflate(layoutInflater)

        supportActionBar?.hide()
        setContentView(mBinding.root)
        applySystemBarPadding(this)

        mSectionsPagerAdapter = SectionsPagerAdapter(supportFragmentManager)
        mBinding.container.adapter = mSectionsPagerAdapter
        mBinding.container.offscreenPageLimit = 1
        mBinding.tabIndicator.setupWithViewPager(mBinding.container)
    }

    companion object
    {
        private val TAG = TutorialActivity::class.java.name
    }
}