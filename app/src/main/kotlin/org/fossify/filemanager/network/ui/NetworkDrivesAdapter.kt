package org.fossify.filemanager.network.ui

import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.getPopupMenuTheme
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.filemanager.R
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.filemanager.databinding.ItemNetworkDriveBinding
import org.fossify.filemanager.network.core.NetworkConnection

/** The list of saved network drives; tapping one opens it, the overflow menu edits or removes it. */
class NetworkDrivesAdapter(
    private val activity: BaseSimpleActivity,
    private var drives: List<NetworkConnection>,
    private val onOpen: (NetworkConnection) -> Unit,
    private val onEdit: (NetworkConnection) -> Unit,
    private val onDelete: (NetworkConnection) -> Unit,
) : RecyclerView.Adapter<NetworkDrivesAdapter.Holder>() {

    fun submit(newDrives: List<NetworkConnection>) {
        drives = newDrives
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = drives.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemNetworkDriveBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val drive = drives[position]
        val textColor = activity.getProperTextColor()
        holder.binding.apply {
            root.setupViewBackground(activity)
            networkDriveTitle.text = drive.name
            networkDriveTitle.setTextColor(textColor)
            val user = drive.username.takeIf { it.isNotEmpty() }?.let { "$it@" }.orEmpty()
            networkDriveSubtitle.text = drive.displayAddress().replaceFirst("://", "://$user")
            networkDriveSubtitle.setTextColor(textColor)
            networkDriveIcon.applyColorFilter(activity.getProperPrimaryColor())
            overflowMenuIcon.applyColorFilter(textColor)
            root.setOnClickListener { onOpen(drive) }
            overflowMenuIcon.setOnClickListener { showMenu(overflowMenuAnchor, drive) }
        }
    }

    private fun showMenu(anchor: View, drive: NetworkConnection) {
        val themed = ContextThemeWrapper(activity, activity.getPopupMenuTheme())
        PopupMenu(themed, anchor, Gravity.END).apply {
            inflate(R.menu.menu_network_drive_item)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.network_drive_open -> onOpen(drive)
                    R.id.network_drive_edit -> onEdit(drive)
                    R.id.network_drive_delete -> onDelete(drive)
                }
                true
            }
            show()
        }
    }

    class Holder(val binding: ItemNetworkDriveBinding) : RecyclerView.ViewHolder(binding.root)
}
