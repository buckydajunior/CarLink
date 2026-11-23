package com.carlink.core.entry

import org.junit.Assert.*
import org.junit.Test

class UnlockQueueTest {
    private var unlocked = false
    private var resume: (() -> Unit)? = null
    private var registrations = 0
    private var stops = 0
    private fun queue() = UnlockQueue({ unlocked }) { callback ->
        registrations++
        resume = callback
        val stop: () -> Unit = { stops++; resume = null }
        stop
    }

    @Test fun initializationPrecedesUsbAndUiAndUnlockIsOneShot() {
        val calls = mutableListOf<String>()
        val queue = queue()
        queue.enqueue("app") { assertTrue(it); calls += "app" }
        queue.enqueue("usb") { assertTrue(it); calls += "usb" }
        queue.enqueue("home") { assertTrue(it); calls += "home" }
        assertTrue(calls.isEmpty())
        assertEquals(1, registrations)
        val signal = requireNotNull(resume)
        signal() // A premature/spurious broadcast must not unlock storage.
        assertTrue(calls.isEmpty())
        unlocked = true
        signal(); signal()
        assertEquals(listOf("app", "usb", "home"), calls)
        assertEquals(1, stops)
    }

    @Test fun unlockedStartupRunsImmediatelyWithoutListenerOrDelay() {
        unlocked = true
        var count = 0
        queue().enqueue("usb") { assertFalse(it); count++ }
        assertEquals(1, count)
        assertEquals(0, registrations)
    }

    @Test fun latestAttachReplacesOldAndOldCancellationCannotRemoveIt() {
        val calls = mutableListOf<String>()
        val queue = queue()
        queue.enqueue("app") { calls += "app" }
        val old = queue.enqueue("usb") { calls += "old" }
        queue.enqueue("usb") { calls += "new" }
        old.cancel()
        unlocked = true
        resume!!()
        assertEquals(listOf("app", "new"), calls)
    }

    @Test fun destroyedUiCancelsItsWorkWithoutLosingApplicationOrUsb() {
        val calls = mutableListOf<String>()
        val queue = queue()
        queue.enqueue("app") { calls += "app" }
        queue.enqueue("usb") { calls += "usb" }
        queue.enqueue("ui") { calls += "ui" }.cancel()
        unlocked = true
        resume!!()
        assertEquals(listOf("app", "usb"), calls)
    }

    @Test fun cancellingLastWorkUnregistersAndFutureWorkCanListenAgain() {
        val queue = queue()
        queue.enqueue("old") { fail() }.cancel()
        assertEquals(1, stops)
        var ran = false
        queue.enqueue("new") { ran = true }
        assertEquals(2, registrations)
        unlocked = true
        resume!!()
        assertTrue(ran)
        assertEquals(2, stops)
    }

    @Test fun unlockBetweenQueryAndRegistrationIsNotLost() {
        var stopped = false
        val queue = UnlockQueue({ unlocked }) {
            unlocked = true // broadcast passed before receiver registration finished
            val stop: () -> Unit = { stopped = true }
            stop
        }
        var ran = false
        queue.enqueue("usb") { assertTrue(it); ran = true }
        assertTrue(ran)
        assertTrue(stopped)
    }

    @Test fun inlineUnlockDuringRegistrationStillCleansUpReceiver() {
        var stopped = 0
        val queue = UnlockQueue({ unlocked }) { signal ->
            unlocked = true
            signal()
            val stop: () -> Unit = { stopped++ }
            stop
        }
        var count = 0
        queue.enqueue("usb") { count++ }
        assertEquals(1, count)
        assertEquals(1, stopped)
    }

    @Test fun reentrantWorkFollowsAlreadyQueuedComponents() {
        val calls = mutableListOf<String>()
        val queue = queue()
        queue.enqueue("app") { calls += "app"; queue.enqueue("nested") { calls += "nested" } }
        queue.enqueue("usb") { calls += "usb" }
        unlocked = true
        resume!!()
        assertEquals(listOf("app", "usb", "nested"), calls)
    }

    @Test fun failedListenerNeverRunsLockedWorkAndLaterEventCanRecover() {
        var tries = 0
        val queue = UnlockQueue({ unlocked }) { tries++; null }
        var count = 0
        queue.enqueue("app") { count++ }
        queue.enqueue("usb") { count++ }
        assertEquals(0, count)
        assertEquals(2, tries)
        unlocked = true
        queue.enqueue("home") { count++ }
        assertEquals(3, count)
    }

    @Test fun ordinaryScreenLockDoesNotRequeueAfterCredentialStorageUnlocked() {
        val queue = queue()
        unlocked = true
        queue.enqueue("first") { assertFalse(it) }
        unlocked = false // CE storage stays available through ordinary screen lock.
        var ran = false
        queue.enqueue("later") { assertFalse(it); ran = true }
        assertTrue(ran)
        assertEquals(0, registrations)
    }
}
