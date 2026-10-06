package org.fossify.filemanager.network.ui

import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.models.RadioItem
import org.fossify.filemanager.R
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.filemanager.network.core.RemotePath
import org.fossify.filemanager.network.data.networkManager

/**
 * Chooses where to copy or move items when network drives are involved. The result is a path: either a folder on
 * the phone or a `remote://` folder.
 */
object DestinationPicker {
    private const val DEVICE_ID = -1

    /** True when the plain folder picker of the app cannot be used: the source is remote or a drive could be the target. */
    fun isNeeded(activity: BaseSimpleActivity, items: List<FileDirItem>): Boolean {
        return items.any { RemotePath.isRemote(it.path) } || activity.networkManager.repository.hasConnections()
    }

    /**
     * Shows the choice between the device and the saved drives, then the matching folder picker.
     * [showDevicePicker] must show the regular folder picker of the app and report its result.
     */
    fun pick(
        activity: BaseSimpleActivity,
        showDevicePicker: (onPicked: (String) -> Unit) -> Unit,
        onPicked: (String) -> Unit,
    ) {
        val connections = activity.networkManager.repository.getAll()
        val items = ArrayList<RadioItem>()
        items.add(RadioItem(DEVICE_ID, activity.getString(R.string.device_storage)))
        connections.forEachIndexed { index, connection ->
            items.add(RadioItem(index, "${connection.name}  (${connection.displayAddress()})"))
        }

        RadioGroupDialog(activity, items, -2, R.string.network_select_destination) { choice ->
            val id = choice as Int
            if (id == DEVICE_ID) {
                showDevicePicker(onPicked)
            } else {
                RemoteFolderPickerDialog(activity, connections[id].id, null) { onPicked(it) }
            }
        }
    }
}
