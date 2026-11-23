package com.carlink.app.ui.wired

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Called/closed on the UI thread. Opening other routes or hiding the Activity releases the analyzer. */
internal fun observeMusicWhileStarted(lifecycle: Lifecycle, observe: () -> AutoCloseable): AutoCloseable {
    var observation: AutoCloseable? = null
    var closed = false
    fun sync() {
        if (closed) return
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (observation == null) observation = observe()
        } else {
            observation?.close()
            observation = null
        }
    }
    val listener = LifecycleEventObserver { _, _ -> sync() }
    lifecycle.addObserver(listener)
    sync()
    return AutoCloseable {
        if (!closed) {
            closed = true
            lifecycle.removeObserver(listener)
            observation?.close()
            observation = null
        }
    }
}
