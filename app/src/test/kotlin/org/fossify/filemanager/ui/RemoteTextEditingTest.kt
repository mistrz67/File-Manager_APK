package org.fossify.filemanager.ui

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import org.fossify.commons.helpers.REAL_FILE_PATH
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.ReadTextActivity
import org.fossify.filemanager.network.android.SftpTestDrive
import org.fossify.filemanager.network.ui.RemoteActions
import org.junit.After
import org.junit.Assert.assertEquals
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

/** A text file on a network drive is opened in the built-in editor and saved back to the server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AllFilesAccessEnvironment::class])
class RemoteTextEditingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var drive: SftpTestDrive
    private lateinit var controller: ActivityController<ReadTextActivity>
    private lateinit var activity: ReadTextActivity

    @Before
    fun setUp() {
        drive = SftpTestDrive(RuntimeEnvironment.getApplication(), folder)
        drive.serverRoot.resolve("notes.txt").writeText("first draft")

        // the same intent RemoteActions.open() builds after downloading the file to the cache
        val remote = drive.remote("/notes.txt")
        val cached = drive.manager.files.download(remote)
        val intent = Intent(RuntimeEnvironment.getApplication(), ReadTextActivity::class.java).apply {
            data = Uri.fromFile(cached)
            putExtra(REAL_FILE_PATH, cached.absolutePath)
            putExtra(RemoteActions.EXTRA_REMOTE_PATH, remote)
        }
        controller = Robolectric.buildActivity(ReadTextActivity::class.java, intent).setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        drive.stop()
    }

    private val editor get() = activity.findViewById<EditText>(R.id.read_text_view)

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    @Test
    fun editedTextIsWrittenBackToTheServer() {
        await("the file to be shown") { editor.text.toString() == "first draft" }

        editor.setText("second draft, saved from the phone")
        activity.findViewById<Toolbar>(R.id.read_text_toolbar).menu.performIdentifierAction(R.id.menu_save, 0)

        await("the server file to change") {
            drive.serverRoot.resolve("notes.txt").readText() == "second draft, saved from the phone"
        }
        assertEquals("second draft, saved from the phone", drive.serverRoot.resolve("notes.txt").readText())
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
