package dev.wristreader.wear

import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.wristreader.shared.BluetoothAccess
import dev.wristreader.shared.TransferBus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ForegroundReceiveTest {
    @Suppress("DEPRECATION")
    @Test fun receiveStartsOnOpenStopsInBackgroundAndRestartsOnReturn() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        assertTrue("Нужно разрешение Bluetooth на часах",BluetoothAccess.granted(context))
        i.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").use { fd -> java.io.FileInputStream(fd.fileDescriptor).readBytes() }
        val lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,"WristReader:receive-test")
        lock.acquire(60_000)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                waitingForPhone()
                activity.onActivity {
                    assertTrue(it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
                }
                activity.moveToState(Lifecycle.State.CREATED)
                stopped()
                activity.onActivity {
                    assertEquals(0,it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                activity.moveToState(Lifecycle.State.RESUMED)
                waitingForPhone()
            }
            stopped()
        } finally { if (lock.isHeld) lock.release() }
    }

    private fun waitingForPhone() = runBlocking {
        withTimeout(10000) { TransferBus.current.first { it.running && it.message.contains("ожидаю телефон") } }
    }
    private fun stopped() = runBlocking {
        withTimeout(10000) { TransferBus.current.first { !it.running } }
    }
}
