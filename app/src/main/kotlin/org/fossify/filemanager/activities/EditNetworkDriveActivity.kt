package org.fossify.filemanager.activities

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getFilenameFromUri
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.onTextChangeListener
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.filemanager.R
import org.fossify.filemanager.databinding.ActivityEditNetworkDriveBinding
import org.fossify.filemanager.network.core.AuthMethod
import org.fossify.filemanager.network.core.ConnectionConfig
import org.fossify.filemanager.network.core.ConnectionSecrets
import org.fossify.filemanager.network.core.NetworkConnection
import org.fossify.filemanager.network.core.Paths
import org.fossify.filemanager.network.core.Protocol
import org.fossify.filemanager.network.core.UntrustedServerException
import org.fossify.filemanager.network.data.networkManager
import org.fossify.filemanager.network.ui.DiscoverServersDialog
import org.fossify.filemanager.network.ui.NetworkErrors
import org.fossify.filemanager.network.ui.TrustServerDialog

/** Form to add a network drive or edit a saved one, with a button that tests the connection. */
class EditNetworkDriveActivity : SimpleActivity() {
    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
        private const val MAX_KEY_BYTES = 64 * 1024
    }

    private val binding by viewBinding(ActivityEditNetworkDriveBinding::inflate)
    private val manager get() = networkManager

    private var existing: NetworkConnection? = null
    private var protocol = Protocol.SMB
    private var authMethod = AuthMethod.PASSWORD
    private var trustedIdentity = ""

    /** Private key chosen in this session; empty means "keep what is saved" (or nothing). */
    private var chosenKey = ""
    private var passwordEdited = false
    private var passphraseEdited = false
    private var hasSavedKey = false
    private var testing = false

    private val pickKeyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadKey(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        existing = intent.getStringExtra(EXTRA_CONNECTION_ID)?.let { manager.repository.get(it) }
        existing?.let { showExisting(it) }

        binding.editDriveToolbar.title = getString(if (existing == null) R.string.add_network_drive else R.string.edit_network_drive)
        binding.editDriveToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.save_network_drive -> save()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        binding.apply {
            editDriveProtocol.setOnClickListener { chooseProtocol() }
            editDriveAuth.setOnClickListener { chooseAuthMethod() }
            editDriveChooseKey.setOnClickListener { pickKeyFile.launch(arrayOf("*/*")) }
            editDriveTest.setOnClickListener { testConnection() }
            editDriveScan.setOnClickListener { scanNetwork() }
            editDrivePassword.onTextChangeListener { passwordEdited = true }
            editDrivePassphrase.onTextChangeListener { passphraseEdited = true }

            setupEdgeToEdge(padBottomImeAndSystem = listOf(editDriveScrollview))
            setupMaterialScrollListener(editDriveScrollview, editDriveAppbar)
        }

        // text watchers above fire while the saved values are filled in; the user has not edited anything yet
        passwordEdited = false
        passphraseEdited = false
        updateForm()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.editDriveAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.editDriveScrollview)
    }

    // region form state

    private fun showExisting(drive: NetworkConnection) {
        protocol = drive.protocol
        authMethod = drive.authMethod
        trustedIdentity = drive.trustedIdentity
        hasSavedKey = manager.repository.loadSecrets(drive.id)?.privateKey?.isNotEmpty() == true

        binding.apply {
            editDriveName.setText(drive.name)
            editDriveHost.setText(drive.host)
            editDrivePort.setText(if (drive.port > 0) drive.port.toString() else "")
            editDriveShare.setText(drive.share)
            editDriveDomain.setText(drive.domain)
            editDriveInitialPath.setText(drive.initialPath)
            editDriveUsername.setText(drive.username)
            editDrivePasswordLayout.helperText = getString(R.string.drive_password_saved)
            editDrivePassive.isChecked = drive.ftpPassive
            editDriveEncoding.setText(drive.encoding)
        }
    }

    private fun updateForm() {
        val allowed = allowedAuthMethods(protocol)
        if (authMethod !in allowed) authMethod = AuthMethod.PASSWORD

        binding.apply {
            editDriveProtocol.setText(protocolLabel(protocol))
            editDriveAuth.setText(authLabel(authMethod))
            editDrivePortLayout.placeholderText = protocol.defaultPort.toString()
            if (editDriveEncoding.value.isEmpty()) editDriveEncoding.setText(NetworkConnection.DEFAULT_ENCODING)

            editDriveSmbHolder.beVisibleIf(protocol == Protocol.SMB)
            editDriveFtpHolder.beVisibleIf(protocol.isFtpFamily)
            editDriveUsernameHolder.beVisibleIf(authMethod != AuthMethod.ANONYMOUS)
            editDrivePasswordLayout.beVisibleIf(authMethod == AuthMethod.PASSWORD)
            editDriveKeyHolder.beVisibleIf(authMethod == AuthMethod.PRIVATE_KEY)
            editDriveKeyStatus.text = when {
                chosenKey.isNotEmpty() -> editDriveKeyStatus.text
                hasSavedKey -> getString(R.string.drive_key_saved)
                else -> getString(R.string.drive_key_none)
            }
            editDriveTest.isEnabled = !testing
            editDriveTest.alpha = if (testing) 0.5f else 1f
            editDriveTest.text = getString(if (testing) R.string.drive_testing else R.string.drive_test_connection)
        }
    }

    private fun allowedAuthMethods(protocol: Protocol) = when (protocol) {
        Protocol.SFTP -> listOf(AuthMethod.PASSWORD, AuthMethod.PRIVATE_KEY)
        else -> listOf(AuthMethod.PASSWORD, AuthMethod.ANONYMOUS)
    }

    private fun protocolLabel(protocol: Protocol) = getString(
        when (protocol) {
            Protocol.SMB -> R.string.protocol_smb
            Protocol.SFTP -> R.string.protocol_sftp
            Protocol.FTP -> R.string.protocol_ftp
            Protocol.FTPES -> R.string.protocol_ftpes
            Protocol.FTPS -> R.string.protocol_ftps
        }
    )

    private fun authLabel(method: AuthMethod) = getString(
        when (method) {
            AuthMethod.PASSWORD -> R.string.drive_auth_password
            AuthMethod.PRIVATE_KEY -> R.string.drive_auth_key
            AuthMethod.ANONYMOUS -> R.string.drive_auth_anonymous
        }
    )

    private fun chooseProtocol() {
        val items = Protocol.values().map { RadioItem(it.ordinal, protocolLabel(it)) } as ArrayList<RadioItem>
        RadioGroupDialog(this, items, protocol.ordinal, R.string.network_drive_type) {
            val chosen = Protocol.values()[it as Int]
            if (chosen != protocol) {
                val portText = binding.editDrivePort.value
                // a port typed for the previous protocol (or its default) would be wrong for the new one
                if (portText.isEmpty() || portText == protocol.defaultPort.toString()) binding.editDrivePort.setText("")
                protocol = chosen
                updateForm()
            }
        }
    }

    private fun chooseAuthMethod() {
        val items = allowedAuthMethods(protocol).map { RadioItem(it.ordinal, authLabel(it)) } as ArrayList<RadioItem>
        RadioGroupDialog(this, items, authMethod.ordinal, R.string.drive_auth) {
            authMethod = AuthMethod.values()[it as Int]
            updateForm()
        }
    }

    // endregion

    private fun scanNetwork() {
        DiscoverServersDialog(this) { address, name, chosenProtocol ->
            binding.editDriveHost.setText(address)
            if (binding.editDriveName.value.isEmpty() && name != null) binding.editDriveName.setText(name)
            if (chosenProtocol != protocol) {
                protocol = chosenProtocol
                binding.editDrivePort.setText("")
            }
            updateForm()
        }
    }

    private fun loadKey(uri: Uri) {
        ensureBackgroundThread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { readUpTo(it, MAX_KEY_BYTES + 1) }
                    ?: ByteArray(0)
                val text = String(bytes, Charsets.UTF_8)
                runOnUiThread {
                    if (bytes.size > MAX_KEY_BYTES || !text.contains("PRIVATE KEY")) {
                        toast(R.string.drive_key_invalid)
                    } else {
                        chosenKey = text
                        binding.editDriveKeyStatus.text = getString(R.string.drive_key_loaded, getFilenameFromUri(uri))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast(R.string.drive_key_invalid) }
            }
        }
    }

    // region actions

    /** Reads and validates the form. Shows a message and returns null when something is wrong. */
    private fun buildConnection(): NetworkConnection? {
        val host = binding.editDriveHost.value.removePrefix("//")
        if (host.isEmpty()) {
            toast(R.string.drive_invalid_host)
            return null
        }

        val portText = binding.editDrivePort.value
        val port = if (portText.isEmpty()) 0 else portText.toIntOrNull() ?: -1
        if (port < 0 || port > 65535) {
            toast(R.string.drive_invalid_port)
            return null
        }

        val share = binding.editDriveShare.value.trim('/', '\\')
        val initialPath = binding.editDriveInitialPath.value.let { if (it.isEmpty()) "" else Paths.normalize(it) }
        val old = existing
        val identityChanged = old != null && (old.protocol != protocol || old.host != host || old.effectivePort != (if (port > 0) port else protocol.defaultPort))

        return NetworkConnection(
            id = old?.id ?: NetworkConnection.newId(),
            name = binding.editDriveName.value.ifEmpty { host },
            protocol = protocol,
            host = host,
            port = port,
            username = if (authMethod == AuthMethod.ANONYMOUS) "" else binding.editDriveUsername.value,
            authMethod = authMethod,
            domain = if (protocol == Protocol.SMB) binding.editDriveDomain.value else "",
            share = if (protocol == Protocol.SMB) share else "",
            initialPath = initialPath,
            ftpPassive = binding.editDrivePassive.isChecked,
            encoding = binding.editDriveEncoding.value.ifEmpty { NetworkConnection.DEFAULT_ENCODING },
            trustedIdentity = if (identityChanged) trustedIdentity.takeIf { it != old?.trustedIdentity }.orEmpty() else trustedIdentity,
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
        )
    }

    /** What the form says, combined with what is saved for fields the user did not touch. */
    private fun effectiveSecrets(): ConnectionSecrets {
        val saved = existing?.let { manager.repository.loadSecrets(it.id) } ?: ConnectionSecrets.EMPTY
        val newPassword = binding.editDrivePassword.text?.toString().orEmpty()
        val newPassphrase = binding.editDrivePassphrase.text?.toString().orEmpty()
        return ConnectionSecrets(
            password = if (passwordEdited || existing == null) newPassword else saved.password,
            privateKey = chosenKey.ifEmpty { saved.privateKey },
            passphrase = if (passphraseEdited || existing == null) newPassphrase else saved.passphrase,
        )
    }

    private fun testConnection() {
        if (testing) return
        val connection = buildConnection() ?: return
        val config = ConnectionConfig(connection, effectiveSecrets())
        testing = true
        updateForm()

        ensureBackgroundThread {
            try {
                val result = manager.files.test(config)
                runOnUiThread {
                    toast(getString(R.string.drive_test_ok, result.itemCount, result.initialPath), Toast.LENGTH_LONG)
                }
            } catch (e: UntrustedServerException) {
                runOnUiThread {
                    TrustServerDialog(this, connection.host, e) {
                        trustedIdentity = e.presentedIdentity
                        testConnection()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast(NetworkErrors.describe(this, e), Toast.LENGTH_LONG) }
            } finally {
                runOnUiThread {
                    testing = false
                    updateForm()
                }
            }
        }
    }

    private fun save() {
        val connection = buildConnection() ?: return
        val secretsChanged = existing == null || passwordEdited || passphraseEdited || chosenKey.isNotEmpty()
        try {
            manager.repository.save(connection, if (secretsChanged) effectiveSecrets() else null)
        } catch (e: Exception) {
            toast(R.string.drive_storage_failed)
            return
        }

        manager.connectionChanged(connection.id)
        finish()
    }

    // endregion
}

private const val READ_BUFFER_BYTES = 8192

/** Reads at most [limit] bytes; `InputStream.readNBytes` only exists from Android 13. */
private fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    while (out.size() < limit) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
