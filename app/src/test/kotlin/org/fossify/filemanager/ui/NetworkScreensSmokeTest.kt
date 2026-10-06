package org.fossify.filemanager.ui

import android.content.Intent
import org.fossify.filemanager.activities.EditNetworkDriveActivity
import org.fossify.filemanager.activities.NetworkDrivesActivity
import org.fossify.filemanager.network.core.NetworkConnection
import org.fossify.filemanager.network.core.Protocol
import org.fossify.filemanager.network.data.ConnectionRepository
import org.fossify.filemanager.network.core.KeySecretCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkScreensSmokeTest {
    @Test
    fun drivesScreenInflates() {
        val controller = Robolectric.buildActivity(NetworkDrivesActivity::class.java).create().start().resume()
        assertNotNull(controller.get())
    }

    @Test
    fun editScreenInflates() {
        val controller = Robolectric.buildActivity(EditNetworkDriveActivity::class.java).create().start().resume()
        assertNotNull(controller.get())
    }

    @Test
    fun editScreenShowsSavedDrive() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val repository = ConnectionRepository(context, KeySecretCipher { key })
        repository.save(NetworkConnection("id1", "My NAS", Protocol.SMB, "192.168.1.5", username = "jan", share = "Dane"), null)
        assertEquals(1, repository.getAll().size)

        val intent = Intent(context, EditNetworkDriveActivity::class.java).putExtra(EditNetworkDriveActivity.EXTRA_CONNECTION_ID, "id1")
        val controller = Robolectric.buildActivity(EditNetworkDriveActivity::class.java, intent).create().start().resume()
        assertNotNull(controller.get())
    }
}
