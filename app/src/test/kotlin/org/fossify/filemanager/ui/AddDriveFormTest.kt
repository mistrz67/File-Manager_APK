package org.fossify.filemanager.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.EditNetworkDriveActivity
import org.fossify.filemanager.network.core.EmbeddedSftpServer
import org.fossify.filemanager.network.core.KeySecretCipher
import org.fossify.filemanager.network.core.PinnedIdentity
import org.fossify.filemanager.network.core.Protocol
import org.fossify.filemanager.network.data.NetworkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import javax.crypto.KeyGenerator

/** Fills in the "add network drive" form for an SFTP server, tests the connection, trusts it and saves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AllFilesAccessEnvironment::class])
class AddDriveFormTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: EmbeddedSftpServer
    private lateinit var manager: NetworkManager
    private lateinit var controller: ActivityController<EditNetworkDriveActivity>
    private lateinit var activity: EditNetworkDriveActivity

    @Before
    fun setUp() {
        server = EmbeddedSftpServer(folder.newFolder("root"), folder.newFile("hostkey.ser")).also { it.start() }
        server.rootDir.resolve("hello.txt").writeText("hi")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        manager = NetworkManager.installForTesting(RuntimeEnvironment.getApplication(), KeySecretCipher { key })
        controller = Robolectric.buildActivity(EditNetworkDriveActivity::class.java).setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        manager.pool.close()
        server.stop()
    }

    private fun text(id: Int) = activity.findViewById<EditText>(id)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            idle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    private fun allViews(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(allViews(root.getChildAt(i)))
    }

    private fun latestDialog(): Dialog? = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }

    /** The success toast names the number of items found in the start folder (just hello.txt here). */
    private fun testSucceeded(): Boolean {
        val expected = activity.getString(R.string.drive_test_ok, 1, "\u0000").substringBefore("\u0000")
        val shown = ShadowToast.getTextOfLatestToast() ?: return false
        return shown.startsWith(expected.substringBefore("1"))
    }

    private fun chooseSftp() {
        activity.findViewById<View>(R.id.edit_drive_protocol).performClick()
        idle()
        val dialog = latestDialog() ?: throw AssertionError("the protocol dialog did not open")
        val sftp = activity.getString(R.string.protocol_sftp)
        val button = allViews(dialog.window!!.decorView).filterIsInstance<RadioButton>().first { it.text.toString() == sftp }
        button.performClick()
        idle()
    }

    private fun fillForm() {
        chooseSftp()
        text(R.id.edit_drive_name).setText("Home server")
        text(R.id.edit_drive_host).setText("127.0.0.1")
        text(R.id.edit_drive_port).setText(server.port.toString())
        text(R.id.edit_drive_username).setText(EmbeddedSftpServer.USER)
        text(R.id.edit_drive_password).setText(EmbeddedSftpServer.PASSWORD)
    }

    @Test
    fun newServerMustBeTrustedBeforeTheConnectionTestSucceeds() {
        fillForm()
        activity.findViewById<View>(R.id.edit_drive_test).performClick()

        await("the trust dialog") {
            latestDialog()?.let { dialog ->
                allViews(dialog.window!!.decorView).any { it is TextView && it.text.toString().contains("SHA256") }
            } == true
        }
        (latestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()

        await("the successful test") { testSucceeded() }
    }

    @Test
    fun savingStoresTheDriveTheTrustedIdentityAndAnEncryptedPassword() {
        fillForm()
        activity.findViewById<View>(R.id.edit_drive_test).performClick()
        await("the trust dialog") { latestDialog() is AlertDialog }
        (latestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        await("the successful test") { testSucceeded() }

        activity.findViewById<Toolbar>(R.id.edit_drive_toolbar).menu.performIdentifierAction(R.id.save_network_drive, 0)
        idle()

        val saved = manager.repository.getAll().single()
        assertEquals("Home server", saved.name)
        assertEquals(Protocol.SFTP, saved.protocol)
        assertEquals("127.0.0.1", saved.host)
        assertEquals(server.port, saved.port)
        assertEquals(EmbeddedSftpServer.USER, saved.username)
        assertNotNull(PinnedIdentity.parse(saved.trustedIdentity))
        assertEquals(EmbeddedSftpServer.PASSWORD, manager.repository.loadSecrets(saved.id)?.password)

        // the stored blob must not contain the password in clear text
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("network_drive_secrets", 0)
        assertTrue(prefs.all.isNotEmpty())
        assertTrue(prefs.all.values.none { it.toString().contains(EmbeddedSftpServer.PASSWORD) })
        val meta = RuntimeEnvironment.getApplication().getSharedPreferences("network_drives", 0)
        assertTrue(meta.all.values.none { it.toString().contains(EmbeddedSftpServer.PASSWORD) })

        // and the saved drive works without typing anything again
        assertEquals(setOf("hello.txt"), manager.files.list(
            org.fossify.filemanager.network.core.RemotePath.build(saved.id, "/")
        ).map { it.name }.toSet())
    }

    @Test
    fun wrongPasswordIsReportedAndNothingIsSaved() {
        fillForm()
        text(R.id.edit_drive_password).setText("not-the-password")
        activity.findViewById<View>(R.id.edit_drive_test).performClick()
        await("the trust dialog") { latestDialog() is AlertDialog }
        (latestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()

        await("a login error") {
            ShadowToast.getTextOfLatestToast()?.contains(activity.getString(R.string.network_error_auth)) == true
        }
        assertTrue(manager.repository.getAll().isEmpty())
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
