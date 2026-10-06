package org.fossify.filemanager.network.ui

import android.content.Context
import android.content.Intent
import org.fossify.filemanager.activities.MainActivity

/** Entry points shared by several screens. */
object NetworkActions {
    const val ACTION_OPEN_DRIVE = "org.fossify.filemanager.action.OPEN_NETWORK_DRIVE"
    const val EXTRA_CONNECTION_ID = "network_connection_id"

    /** Brings the main screen to the front and opens the given drive in it. */
    fun openDriveInMainScreen(context: Context, connectionId: String) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_DRIVE
                putExtra(EXTRA_CONNECTION_ID, connectionId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }
}
