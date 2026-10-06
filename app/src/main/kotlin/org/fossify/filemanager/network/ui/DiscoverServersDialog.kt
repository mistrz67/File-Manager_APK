package org.fossify.filemanager.network.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.models.RadioItem
import org.fossify.filemanager.R
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.filemanager.databinding.DialogDiscoverServersBinding
import org.fossify.filemanager.databinding.ItemDiscoveredServerBinding
import org.fossify.filemanager.network.core.DiscoveredServer
import org.fossify.filemanager.network.core.Protocol

/** Scans the local network and lets the user pick a server to fill in the address of a new drive. */
class DiscoverServersDialog(
    private val activity: BaseSimpleActivity,
    private val onPicked: (address: String, name: String?, protocol: Protocol) -> Unit,
) {
    private val binding = DialogDiscoverServersBinding.inflate(activity.layoutInflater)
    private val servers = LinkedHashMap<String, DiscoveredServer>()
    private val adapter = ServerAdapter()
    private var dialog: AlertDialog? = null
    private val discovery = NetworkDiscovery(activity, object : NetworkDiscovery.Listener {
        override fun onServer(server: DiscoveredServer) = merge(server)

        override fun onProgress(percent: Int) {
            binding.discoverProgress.setProgressCompat(percent, true)
        }

        override fun onFinished(hasNetwork: Boolean) {
            binding.discoverProgress.beGone()
            binding.discoverStatus.text = when {
                !hasNetwork -> activity.getString(R.string.drive_scan_no_wifi)
                servers.isEmpty() -> activity.getString(R.string.drive_scan_none)
                else -> activity.getString(R.string.drive_scan_done)
            }
        }
    })

    init {
        binding.discoverStatus.text = activity.getString(R.string.drive_scanning)
        binding.discoverStatus.setTextColor(activity.getProperTextColor())
        binding.discoverList.adapter = adapter

        activity.getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { discovery.stop() }
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId = R.string.drive_scan_network) { alert -> dialog = alert }
            }
        discovery.start()
    }

    private fun merge(found: DiscoveredServer) {
        val existing = servers[found.address]
        servers[found.address] = if (existing == null) {
            found
        } else {
            DiscoveredServer(
                address = found.address,
                hostName = existing.hostName?.takeIf { it != existing.address } ?: found.hostName,
                protocols = existing.protocols + found.protocols,
            )
        }
        adapter.submit(servers.values.sortedBy { it.displayName.lowercase() })
    }

    private fun choose(server: DiscoveredServer) {
        val protocols = server.protocols.sortedBy { it.ordinal }
        val pick = { protocol: Protocol ->
            dialog?.dismiss()
            onPicked(server.address, server.hostName?.takeIf { it != server.address }, protocol)
        }

        if (protocols.size == 1) {
            pick(protocols.first())
        } else {
            val items = ArrayList(protocols.map { RadioItem(it.ordinal, protocolLabel(it)) })
            RadioGroupDialog(activity, items, -1, R.string.drive_pick_protocol) { pick(Protocol.values()[it as Int]) }
        }
    }

    private fun protocolLabel(protocol: Protocol) = when (protocol) {
        Protocol.SMB -> "SMB"
        Protocol.SFTP -> "SFTP"
        else -> "FTP"
    }

    private inner class ServerAdapter : RecyclerView.Adapter<ServerAdapter.Holder>() {
        private var items: List<DiscoveredServer> = emptyList()

        fun submit(newItems: List<DiscoveredServer>) {
            items = newItems
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDiscoveredServerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val server = items[position]
            holder.binding.apply {
                discoveredServerName.text = server.displayName
                discoveredServerName.setTextColor(activity.getProperTextColor())
                discoveredServerDetails.text = "${server.address} · ${server.protocols.sortedBy { it.ordinal }.joinToString(", ") { protocolLabel(it) }}"
                discoveredServerDetails.setTextColor(activity.getProperTextColor())
                discoveredServerIcon.applyColorFilter(activity.getProperPrimaryColor())
                root.setOnClickListener { choose(server) }
            }
        }

        inner class Holder(val binding: ItemDiscoveredServerBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
