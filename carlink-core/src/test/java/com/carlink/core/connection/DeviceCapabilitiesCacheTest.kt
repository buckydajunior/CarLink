package com.carlink.core.connection

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeviceCapabilitiesCacheTest {
    private val panel = VideoSize(1920, 1080)
    private fun capabilities(size: VideoSize) = DeviceCapabilities(size,
        DeviceSignals(4096, false, 8, listOf("arm64-v8a")), null, null)

    @Before fun clearBefore() = DeviceCapabilities.trimCaches()
    @After fun clearAfter() = DeviceCapabilities.trimCaches()

    @Test fun concurrentWarmupAndSessionStartShareOneProbe() {
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val duplicateProbe = CountDownLatch(1)
        val loads = AtomicInteger()
        try {
            val first = pool.submit<DeviceCapabilities> {
                DeviceCapabilities.cached(panel) {
                    loads.incrementAndGet()
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    capabilities(panel)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = pool.submit<DeviceCapabilities> {
                secondStarted.countDown()
                DeviceCapabilities.cached(panel) {
                    loads.incrementAndGet()
                    duplicateProbe.countDown()
                    capabilities(panel)
                }
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse("The second caller must await the in-flight probe", duplicateProbe.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, loads.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun panelChangeOrMemoryTrimRequiresAFreshProbe() {
        var loads = 0
        fun read(size: VideoSize) = DeviceCapabilities.cached(size) { loads++; capabilities(size) }
        val initial = read(panel)
        assertSame(initial, read(panel))
        val portrait = read(panel.swapped())
        assertEquals(panel.swapped(), portrait.panel)
        assertEquals(2, loads)
        DeviceCapabilities.trimCaches()
        assertNotSame(portrait, read(panel.swapped()))
        assertEquals(3, loads)
    }
}
