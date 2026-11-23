package com.carlink.core.entry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Minimal Direct Boot bridge. No preferences, files, authentication or engine work before unlock.
 * Application queues its initialization first; later component work follows it on the main thread. */
object AfterUserUnlock {
    fun interface Pending { fun cancel() }
    private var application: Context? = null
    private var queue: UnlockQueue? = null

    /** Replaces only work with the same key. [action] learns whether it was held while locked. */
    fun run(context: Context, key: Any, action: (wasDeferred: Boolean) -> Unit): Pending {
        check(Looper.myLooper() == Looper.getMainLooper())
        val app = context.applicationContext
        if (application !== app) {
            queue?.close()
            application = app
            queue = UnlockQueue(
                unlocked = {
                    runCatching { app.getSystemService(UserManager::class.java)?.isUserUnlocked == true }
                        .getOrElse {
                            Log.w("CarLink-Boot", "Unlock state unavailable (${it.javaClass.simpleName})")
                            false
                        }
                },
                listen = { resume ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.action == Intent.ACTION_USER_UNLOCKED) resume()
                        }
                    }
                    try {
                        ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED),
                            ContextCompat.RECEIVER_NOT_EXPORTED)
                        val stop: () -> Unit = { runCatching { app.unregisterReceiver(receiver) }; Unit }
                        stop
                    } catch (failure: RuntimeException) {
                        // The queued boot/USB work can retry on the next real component event.
                        // Never treat a failed readiness query or listener as permission to use CE storage.
                        Log.w("CarLink-Boot", "Unlock listener unavailable (${failure.javaClass.simpleName})")
                        null
                    }
                },
            )
        }
        return requireNotNull(queue).enqueue(key, action)
    }
}

/** Main-thread queue separated from Android so registration races and replacement can be tested. */
internal class UnlockQueue(
    private val unlocked: () -> Boolean,
    private val listen: (resume: () -> Unit) -> (() -> Unit)?,
) {
    private class Work(val deferred: Boolean, val action: (Boolean) -> Unit)
    private val work = LinkedHashMap<Any, Work>()
    private var stopListening: (() -> Unit)? = null
    private var observedUnlock = false
    private var draining = false

    fun enqueue(key: Any, action: (Boolean) -> Unit): AfterUserUnlock.Pending {
        val entry = Work(!canRun(), action)
        work[key] = entry
        if (entry.deferred && stopListening == null) stopListening = listen(::drain)
        // Recheck AFTER registering: unlock can happen between the first query and registration.
        drain()
        return AfterUserUnlock.Pending {
            if (work[key] === entry) work.remove(key)
            if (work.isEmpty()) stop()
        }
    }

    private fun canRun(): Boolean {
        if (!observedUnlock && unlocked()) observedUnlock = true
        return observedUnlock
    }

    private fun drain() {
        if (draining || !canRun()) return
        draining = true
        try {
            stop()
            while (work.isNotEmpty()) {
                val key = work.keys.first()
                val entry = work.remove(key) ?: continue
                entry.action(entry.deferred)
            }
        } finally {
            draining = false
        }
    }

    private fun stop() {
        val stop = stopListening
        stopListening = null
        stop?.invoke()
    }

    fun close() { work.clear(); stop() }
}
