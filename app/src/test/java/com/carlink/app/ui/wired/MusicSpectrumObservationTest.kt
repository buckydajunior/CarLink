package com.carlink.app.ui.wired

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicSpectrumObservationTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test fun startsOnlyWhileVisibleAndStopsOnBackgroundOrRouteDisposal() {
        val owner = Owner()
        var opened = 0
        var closed = 0
        val route = observeMusicWhileStarted(owner.lifecycle) {
            opened++
            AutoCloseable { closed++ }
        }
        assertEquals(0, opened)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(1, opened)
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        assertEquals(1, closed)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertEquals(2, opened)
        route.close()
        route.close()
        assertEquals(2, closed)
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertEquals(2, opened)
    }

    @Test fun routeOpenedWhileActivityAlreadyStartedSubscribesImmediatelyAndReleasesOnDestroy() {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        var opened = 0
        var closed = 0
        val route = observeMusicWhileStarted(owner.lifecycle) {
            opened++
            AutoCloseable { closed++ }
        }
        assertEquals(1, opened)
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        assertEquals(1, closed)
        route.close()
        assertEquals(1, closed)
    }
}
