package org.fossify.filemanager.network.ui

import android.content.Intent
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.getBasePath
import org.fossify.commons.extensions.hasExternalSDCard
import org.fossify.commons.extensions.hasOTGConnected
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.otgPath
import org.fossify.commons.extensions.sdCardPath
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.NetworkDrivesActivity
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.filemanager.network.core.RemotePath
import org.fossify.filemanager.network.data.networkManager

/**
 * The storage chooser of the file list, extended with the saved network drives. Replaces the stock dialog when
 * at least one drive exists; picking a drive reports its start folder as a `remote://` path.
 */
class StoragePickerWithDrives(
    private val activity: BaseSimpleActivity,
    currentPath: String,
    showRoot: Boolean,
    private val onPicked: (path: String) -> Unit,
) {
    private companion object {
        const val ID_INTERNAL = -1
        const val ID_SD = -2
        const val ID_OTG = -3
        const val ID_ROOT = -4
        const val ID_MANAGE = -5
    }

    init {
        val connections = activity.networkManager.repository.getAll()
        val items = ArrayList<RadioItem>()
        items.add(RadioItem(ID_INTERNAL, activity.getString(R.string.internal)))
        if (activity.hasExternalSDCard()) items.add(RadioItem(ID_SD, activity.getString(R.string.sd_card)))
        if (activity.hasOTGConnected()) items.add(RadioItem(ID_OTG, activity.getString(R.string.usb)))
        if (showRoot) items.add(RadioItem(ID_ROOT, activity.getString(R.string.root)))
        connections.forEachIndexed { index, drive -> items.add(RadioItem(index, drive.name)) }
        items.add(RadioItem(ID_MANAGE, activity.getString(R.string.network_drives_manage)))

        val checked = when {
            RemotePath.isRemote(currentPath) -> connections.indexOfFirst { it.id == RemotePath.connectionId(currentPath) }
            else -> when (currentPath.getBasePath(activity)) {
                activity.internalStoragePath -> ID_INTERNAL
                activity.sdCardPath -> ID_SD
                activity.otgPath -> ID_OTG
                else -> ID_ROOT
            }
        }

        RadioGroupDialog(activity, items, checked, R.string.select_storage) { choice ->
            when (val id = choice as Int) {
                ID_INTERNAL -> onPicked(activity.internalStoragePath)
                ID_SD -> onPicked(activity.sdCardPath)
                ID_OTG -> activity.handleOTGPermission { granted -> if (granted) onPicked(activity.otgPath) }
                ID_ROOT -> onPicked("/")
                ID_MANAGE -> activity.startActivity(Intent(activity, NetworkDrivesActivity::class.java))
                else -> openDrive(connections[id].id)
            }
        }
    }

    private fun openDrive(connectionId: String) {
        ensureBackgroundThread {
            try {
                val start = activity.networkManager.files.initialPath(connectionId)
                activity.runOnUiThread { onPicked(start) }
            } catch (e: Exception) {
                NetworkErrors.handle(activity, connectionId, e) { openDrive(connectionId) }
            }
        }
    }
}
