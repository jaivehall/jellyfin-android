package org.jellyfin.mobile.player.cast

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import org.jellyfin.mobile.R

/**
 * Device chooser dialog used while scanning for Cast receivers.
 *
 * The stock [MediaRouteChooserDialog] keeps an active scan running until the dialog is
 * dismissed by tapping outside or pressing back, neither of which is obvious while the
 * "Searching for devices" spinner is shown. An explicit cancel button makes it clear
 * that a long search can be aborted at any time.
 */
private class CancelableRouteChooserDialog(context: Context) : MediaRouteChooserDialog(context) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The stock layout is a vertical LinearLayout holding the title, the searching
        // label, the progress bar and the route list. Append a cancel button below them.
        val root = window?.decorView
            ?.findViewById<ViewGroup>(android.R.id.content)
            ?.getChildAt(0) as? LinearLayout ?: return

        val topMargin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            8f,
            getContext().resources.displayMetrics,
        ).toInt()

        Button(getContext(), null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.player_cast_cancel_search)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END
                this.topMargin = topMargin
            }
            // Dismissing detaches the dialog, which stops MediaRouter's active scan.
            setOnClickListener { dismiss() }
        }.also(root::addView)
    }
}

/** Chooser fragment that creates [CancelableRouteChooserDialog]. */
class CancelableRouteChooserDialogFragment : MediaRouteChooserDialogFragment() {
    override fun onCreateChooserDialog(
        context: Context,
        savedInstanceState: Bundle?,
    ): MediaRouteChooserDialog = CancelableRouteChooserDialog(context)
}

/** Dialog factory that makes the Cast device search cancellable. */
class CancelableRouteDialogFactory : MediaRouteDialogFactory() {
    override fun onCreateChooserDialogFragment(): MediaRouteChooserDialogFragment =
        CancelableRouteChooserDialogFragment()
}
