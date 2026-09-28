package ca.fuwafuwa.kaku.Dialogs

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.widget.TextView
import shiroikuma.kaku.KakuViews
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import ca.fuwafuwa.kaku.KAKU_PREF_FILE
import ca.fuwafuwa.kaku.KAKU_PREF_FIRST_LAUNCH
import ca.fuwafuwa.kaku.MainActivity
import ca.fuwafuwa.kaku.R

class GrantPermissionDialogFragment : DialogFragment()
{
    /** The house look: bordered black-yellow panel, dialog ink for title, text and buttons. */
    override fun onStart()
    {
        super.onStart()
        val dialog = dialog as? AlertDialog ?: return
        val ctx = requireContext()
        dialog.window?.setBackgroundDrawable(InsetDrawable(KakuViews.panelBackground(ctx), KakuViews.dp(ctx, 16f)))
        val ink = KakuViews.ink()
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(ink)
        val titleId = ctx.resources.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) dialog.findViewById<TextView>(titleId)?.setTextColor(ink)
        for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE))
        {
            dialog.getButton(which)?.setTextColor(ink)
        }
    }

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