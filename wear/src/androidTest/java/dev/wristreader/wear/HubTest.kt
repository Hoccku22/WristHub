package dev.wristreader.wear

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material.MaterialTheme
import dev.wristreader.shared.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class HubTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var screenLock: android.os.PowerManager.WakeLock? = null
    @Suppress("DEPRECATION")
    @Before fun wakeScreen() {
        screenLock = context.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,"WristHub:test")
            .apply { acquire(120000) }
    }
    @After fun releaseScreen() { screenLock?.takeIf { it.isHeld }?.release() }
    private fun fixture(name: String, mime: String, bytes: ByteArray): Document {
        val store = DocumentStore(context)
        val id = UUID.randomUUID().toString()
        val dir = File(store.root,id).apply { mkdirs() }
        val original = File(dir,"original").apply { writeBytes(bytes) }
        val text = File(dir,"text.txt").apply { writeBytes(byteArrayOf()) }
        val h = Protocol.Header(id,name,mime,bytes.size.toLong(),0,Protocol.hash(original),Protocol.hash(text))
        store.metadata(dir,h)
        return Document(dir,h)
    }
    private fun waitFor(test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!test() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue(test())
    }
    @Test fun unknownFilesAndApkHaveReadOnlyUrisAndSystemInstaller() {
        val doc = fixture("example.zip","application/zip",byteArrayOf(80,75,1,2,3))
        val apk = fixture("WristHub.apk",FileTypes.APK,File(context.applicationInfo.sourceDir).readBytes())
        try {
            val intent = fileIntent(context,doc)
            assertEquals("content",intent.data!!.scheme)
            context.contentResolver.openInputStream(intent.data!!).use { assertArrayEquals(doc.original.readBytes(),it!!.readBytes()) }
            assertEquals("application/zip",context.contentResolver.getType(intent.data!!))
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0,intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertThrows(Exception::class.java) { context.contentResolver.openFileDescriptor(intent.data!!,"rw")?.close() }
            val install = fileIntent(context,apk)
            assertNotNull("Системный установщик должен обрабатывать APK",install.resolveActivity(context.packageManager))
            context.contentResolver.openInputStream(install.data!!).use { assertEquals(apk.header.size,it!!.readBytes().size.toLong()) }
            rule.setContent { MaterialTheme { FileScreen(apk) {} } }
            rule.waitUntil(15000) { rule.onAllNodesWithText("dev.wristreader.wear",substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Установить APK").assertIsEnabled()
        } finally { doc.directory.deleteRecursively(); apk.directory.deleteRecursively() }
    }
    @Test fun localMusicPlaysPausesSeeksAndContinuesInBackground() {
        // Silent PCM, so the playback test doesn't make sound on the user's watch.
        val samples = 8000 * 30
        val data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1.toShort()).putShort(1.toShort()).putInt(8000).putInt(16000)
            .putShort(2.toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt(samples * 2)
        val doc = fixture("Silent test.wav","audio/wav",data.array())
        try {
            rule.setContent { MaterialTheme { MusicScreen(doc) {} } }
            waitFor { MusicPlayback.state.value.id == doc.header.id && MusicPlayback.state.value.playing }
            waitFor { MusicPlayback.state.value.position > 400 }
            assertTrue(MusicPlayback.state.value.duration >= 29000)
            rule.onNodeWithTag("music-play-pause").performClick()
            waitFor { !MusicPlayback.state.value.playing }
            MusicPlayback.command(context,"SEEK",position = 10000)
            waitFor { MusicPlayback.state.value.position >= 9000 }
            rule.onNodeWithTag("music-play-pause").performClick()
            waitFor { MusicPlayback.state.value.playing }
            val before = MusicPlayback.state.value.position
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            waitFor { MusicPlayback.state.value.playing && MusicPlayback.state.value.position > before + 1000 }
        } finally {
            context.stopService(Intent(context,MusicService::class.java))
            waitFor { MusicPlayback.state.value.id.isEmpty() }
            doc.directory.deleteRecursively()
        }
    }
}
