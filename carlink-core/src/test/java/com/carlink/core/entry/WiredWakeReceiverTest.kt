package com.carlink.core.entry

import android.app.Application
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class WiredWakeReceiverTest {
    @Test fun everySupportedIgnitionWakeReachesTheReceiver() {
        val context = RuntimeEnvironment.getApplication()
        for (action in WiredWakeReceiver.ACTIONS) {
            assertTrue(action, context.packageManager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
                .any { it.activityInfo.name == WiredWakeReceiver::class.java.name })
        }
    }
    @Test fun repeatedWakeWithoutAnIphoneDoesNotOpenAnything() {
        val context = RuntimeEnvironment.getApplication()
        val receiver = WiredWakeReceiver()
        repeat(3) { receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED)) }
        assertFalse(UsbWatcher.get(context).hasIphone())
        assertNull(org.robolectric.Shadows.shadowOf(context).nextStartedActivity)
    }
}
