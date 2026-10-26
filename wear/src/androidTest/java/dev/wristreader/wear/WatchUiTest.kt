package dev.wristreader.wear

import android.graphics.Bitmap
import android.os.PowerManager
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.wristreader.shared.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class WatchUiTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var wake: PowerManager.WakeLock? = null
    @Suppress("DEPRECATION") @Before fun wakeScreen() {
        wake = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,"WristHub:design-test")
            .apply { acquire(120000) }
    }
    @After fun releaseScreen() { wake?.takeIf { it.isHeld }?.release() }
    private fun doc(name: String, mime: String) = Document(context.cacheDir,Protocol.Header(UUID.randomUUID().toString(),name,mime,1,0,ByteArray(32),ByteArray(32)))

    @Test fun approvedHomeRoutesAndBackNavigation() {
        val pdf = doc("Notes.pdf","application/pdf")
        val music = doc("Music.mp3","audio/mpeg")
        val apk = doc("App.apk",FileTypes.APK)
        var section by mutableStateOf<HubFilter?>(null)
        rule.setContent { WatchTheme {
            if (section == null) WatchHome(true,Transfer(running = true),"",false,false,{},{ section = it },{},{})
            else WatchLibrary(listOf(pdf,music,apk),section!!,1024L * 1024,1f,{ section = null },{},{})
        } }
        for (tag in listOf("home-files","home-music","home-apps","home-reading")) rule.onNodeWithTag(tag).assertIsDisplayed()
        rule.onNodeWithTag("hub-home").captureToImage().asAndroidBitmap().save("hub-home-device.png")
        for ((tag,name) in listOf("home-music" to "Music.mp3","home-apps" to "App.apk","home-reading" to "Notes.pdf")) {
            rule.onNodeWithTag(tag).performClick()
            rule.onNodeWithTag("hub-library").assertExists()
            rule.onNodeWithText(name).assertExists()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.onNodeWithTag("hub-home").assertExists()
        }
    }

    @Test fun libraryDeleteUsesModalAndCancelKeepsTheEntry() {
        val doc = doc("My document.pdf","application/pdf")
        var pending by mutableStateOf<Document?>(null)
        var removed by mutableStateOf(false)
        rule.setContent { WatchTheme {
            WatchLibrary(if (removed) emptyList() else listOf(doc),HubFilter.ALL,1000000,1f,{}, {},{ pending = it })
            pending?.let { DeleteFileDialog(it.header.name,{ pending = null },{ removed = true; pending = null }) }
        } }
        rule.onNodeWithTag("delete-${doc.header.id}").performClick()
        rule.onNodeWithTag("delete-file-dialog").assertIsDisplayed()
        rule.onNodeWithTag("confirm-file-delete").assertIsDisplayed()
        rule.onNodeWithTag("delete-file-dialog").captureToImage().asAndroidBitmap().save("hub-delete-device.png")
        rule.onNodeWithTag("cancel-file-delete").performClick()
        assertFalse(removed)
        rule.onNodeWithText(doc.header.name).assertExists()
        rule.onNodeWithTag("delete-${doc.header.id}").performClick()
        rule.onNodeWithTag("confirm-file-delete").performClick()
        assertTrue(removed)
        rule.onNodeWithText(doc.header.name).assertDoesNotExist()
    }
    private fun Bitmap.save(name: String) {
        File(context.cacheDir,name).outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
