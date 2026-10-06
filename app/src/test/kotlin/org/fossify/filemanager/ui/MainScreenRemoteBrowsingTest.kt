package org.fossify.filemanager.ui

import android.content.Intent
import android.os.Environment
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.MainActivity
import org.fossify.filemanager.fragments.ItemsFragment
import org.fossify.filemanager.network.android.SftpTestDrive
import org.fossify.filemanager.network.ui.NetworkActions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment

/** Opens a network drive in the real main screen and walks through it, including the Back button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AllFilesAccessEnvironment::class])
class MainScreenRemoteBrowsingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var drive: SftpTestDrive
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        drive = SftpTestDrive(RuntimeEnvironment.getApplication(), folder)
        drive.serverRoot.resolve("docs/deep").mkdirs()
        drive.serverRoot.resolve("docs/note.txt").writeText("note")
        drive.serverRoot.resolve("docs/deep/x.txt").writeText("x")
        drive.serverRoot.resolve("a.txt").writeText("alpha")

        val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java).apply {
            action = NetworkActions.ACTION_OPEN_DRIVE
            putExtra(NetworkActions.EXTRA_CONNECTION_ID, drive.connection.id)
        }
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        drive.stop()
    }

    private val fragment get() = activity.findViewById<ItemsFragment>(R.id.items_fragment)
    private val list get() = activity.findViewById<RecyclerView>(R.id.items_list)

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what (path: ${fragment.currentPath})")
            Thread.sleep(25)
        }
    }

    private fun shownNames(): Set<String> {
        val adapter = list.adapter ?: return emptySet()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, 1080, 1920)
        return (0 until adapter.itemCount).mapNotNull { index ->
            list.findViewHolderForAdapterPosition(index)?.itemView
                ?.findViewById<android.widget.TextView>(R.id.item_name)?.text?.toString()
        }.toSet()
    }

    private fun back() {
        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun opensTheDriveFromTheIntentAndListsItsContent() {
        await("the drive to open") { fragment.currentPath == "remote://drive1" && shownNames().isNotEmpty() }
        assertEquals(setOf("docs", "a.txt"), shownNames())
        assertTrue(fragment.isRemote())
    }

    @Test
    fun entersFoldersAndGoesBackUpOneLevelAtATime() {
        await("the drive to open") { fragment.currentPath == "remote://drive1" && shownNames().isNotEmpty() }

        fragment.openPath("remote://drive1/docs")
        await("docs to be shown") { fragment.currentPath == "remote://drive1/docs" && shownNames() == setOf("deep", "note.txt") }

        fragment.openPath("remote://drive1/docs/deep")
        await("deep to be shown") { shownNames() == setOf("x.txt") }

        back()
        await("docs again") { fragment.currentPath == "remote://drive1/docs" && shownNames() == setOf("deep", "note.txt") }
        back()
        await("the drive root again") { fragment.currentPath == "remote://drive1" && shownNames() == setOf("docs", "a.txt") }
    }

    @Test
    fun backAtTheTopOfTheDriveReturnsToThePhone() {
        await("the drive to open") { fragment.currentPath == "remote://drive1" && shownNames().isNotEmpty() }

        back()

        await("the phone's storage") { !fragment.isRemote() }
        assertFalse(fragment.currentPath.startsWith("remote://"))
    }

    @Test
    fun showsAFolderCreatedOnTheServerAfterRefresh() {
        await("the drive to open") { fragment.currentPath == "remote://drive1" && shownNames().isNotEmpty() }

        drive.serverRoot.resolve("fresh").mkdirs()
        fragment.refreshFragment()

        await("the new folder") { "fresh" in shownNames() }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}

/** The app asks for "all files access" on Android 11+; the tests run as if it was granted. */
@Implements(Environment::class)
class AllFilesAccessEnvironment : ShadowEnvironment() {
    companion object {
        @Implementation
        @JvmStatic
        @Suppress("FunctionOnlyReturningConstant")
        fun isExternalStorageManager(): Boolean = true
    }
}
