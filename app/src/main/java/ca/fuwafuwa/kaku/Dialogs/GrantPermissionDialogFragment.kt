package ca.fuwafuwa.kaku.Dialogs

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import ca.fuwafuwa.kaku.KAKU_PREF_FILE
import ca.fuwafuwa.kaku.KAKU_PREF_FIRST_LAUNCH
import ca.fuwafuwa.kaku.MainActivity
import ca.fuwafuwa.kaku.R

class GrantPermissionDialogFragment : DialogFragment()
{
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog
    {
        return activity?.let {

            val builder = AlertDialog.Builder(it)

            builder.setTitle(getString(R.string.grant_title))
                    .setMessage(getString(R.string.grant_text))
                    .setPositiveButton(getString(R.string.grant_ok))
                    {
                        _, _ ->
                        run {
                            val prefs = context!!.getSharedPreferences(KAKU_PREF_FILE, Context.MODE_PRIVATE)
                            prefs.edit().putBoolean(KAKU_PREF_FIRST_LAUNCH, false).apply()

                            startActivity(Intent(activity, MainActivity::class.java))
                            (activity as FragmentActivity).finish()
                        }
                    }
                    .setNegativeButton(getString(android.R.string.cancel))
                    {
                        _, _ ->
                        run {
                        }
                    }

            builder.create()

        } ?: throw IllegalStateException("Activity cannot be null")
    }
}