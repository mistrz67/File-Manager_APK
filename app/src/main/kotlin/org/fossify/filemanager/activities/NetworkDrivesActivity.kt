package org.fossify.filemanager.activities

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.filemanager.R
import org.fossify.filemanager.databinding.ActivityNetworkDrivesBinding
import org.fossify.filemanager.network.core.NetworkConnection
import org.fossify.filemanager.network.data.networkManager
import org.fossify.filemanager.network.ui.NetworkActions
import org.fossify.filemanager.network.ui.NetworkDrivesAdapter

/** Lists the saved network drives and lets the user add, edit, remove and open them. */
class NetworkDrivesActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityNetworkDrivesBinding::inflate)
    private lateinit var adapter: NetworkDrivesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        adapter = NetworkDrivesAdapter(
            activity = this,
            drives = emptyList(),
            onOpen = { NetworkActions.openDriveInMainScreen(this, it.id) },
            onEdit = { edit(it) },
            onDelete = { confirmDelete(it) },
        )
        binding.networkDrivesList.adapter = adapter

        binding.networkDrivesToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.add_network_drive -> edit(null)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(networkDrivesList))
            setupMaterialScrollListener(networkDrivesList, networkDrivesAppbar)
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.networkDrivesAppbar, NavigationIcon.Arrow)
        refresh()
    }

    private fun refresh() {
        val drives = networkManager.repository.getAll()
        adapter.submit(drives)
        binding.apply {
            networkDrivesPlaceholder.beVisibleIf(drives.isEmpty())
            networkDrivesPlaceholder.setTextColor(getProperTextColor())
            networkDrivesPlaceholder2.apply {
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                beVisibleIf(drives.isEmpty())
                setTextColor(getProperPrimaryColor())
                setOnClickListener { edit(null) }
            }
        }
    }

    private fun edit(drive: NetworkConnection?) {
        startActivity(Intent(this, EditNetworkDriveActivity::class.java).apply {
            drive?.let { putExtra(EditNetworkDriveActivity.EXTRA_CONNECTION_ID, it.id) }
        })
    }

    private fun confirmDelete(drive: NetworkConnection) {
        ConfirmationDialog(this, getString(R.string.drive_delete_confirm, drive.name)) {
            networkManager.repository.delete(drive.id)
            networkManager.connectionRemoved(drive.id)
            refresh()
        }
    }
}
