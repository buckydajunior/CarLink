package com.carlink.core.connection
import org.junit.Assert.*
import org.junit.Test
class ConnectionProfilesTest {
    private val request = SessionRequest("Driver's iPhone")
    @Test fun usbHistoryRoundTripsSuccessesFailuresAndAdaptiveSamples() {
        val storage = ConnectionProfiles.Storage.Memory()
        val p = ConnectionProfiles(storage) { 12345 }
        for (ms in listOf(2000L, 4000L, 6000L)) p.recordStage(request, ProgressStage.HANDSHAKE, ms)
        p.recordSuccess(request, 5000); p.recordFailure(request, "USB", ProgressStage.HANDSHAKE, "lost USB")
        val reloaded = ConnectionProfiles(storage)
        assertEquals(10_000L, reloaded.deadline(request, ProgressStage.HANDSHAKE))
        val health = reloaded.healthOf(request)!!
        assertEquals(2, health.attempts); assertEquals(1, health.successes)
        assertEquals("USB", health.bestPath); assertEquals("lost USB", health.lastProblems.single().detail)
    }
    @Test fun historyAndProblemDetailsRemainBounded() {
        val p = ConnectionProfiles()
        repeat(100) { p.recordFailure(request, "USB", null, "x".repeat(400)) }
        assertEquals(ConnectionProfiles.MAX_OUTCOMES, p.healthOf(request)!!.attempts)
        assertEquals(ConnectionProfiles.HEALTH_PROBLEMS, p.healthOf(request)!!.lastProblems.size)
        assertEquals(200, p.healthOf(request)!!.lastProblems.first().detail?.length)
    }
    @Test fun priorBuildUsbHistoryIsImportedWhileRemovedEngineRecordsAreIgnored() {
        val text = "carlink-profiles 1\nP\tCARPLAY:usb:iphone\tCARPLAY\tiPhone\t1\nO\tUSB\t1\t1\t3000\t-\t-\n" +
            "P\tANDROID_AUTO:usb:pixel\tANDROID_AUTO\tPixel\t2\nO\tUSB\t2\t1\t1000\t-\t-\n"
        val p = ConnectionProfiles(ConnectionProfiles.Storage.Memory(text))
        assertEquals(1, p.health.value.size); assertEquals(3000L, p.health.value.single().medianTimeToPictureMillis)
    }
    @Test fun malformedStorageDoesNotPreventAUsbStart() {
        assertTrue(ConnectionProfiles(ConnectionProfiles.Storage.Memory("invalid")).health.value.isEmpty())
        val p = ConnectionProfiles(ConnectionProfiles.Storage.Memory("carlink-profiles 1\nP\tbroken"))
        p.recordSuccess(request, 2000); assertEquals(1, p.health.value.single().successes)
    }
    @Test fun phoneNamesAndMessagesAreSafeForTheLineFormat() {
        val storage = ConnectionProfiles.Storage.Memory(); val p = ConnectionProfiles(storage)
        val request = SessionRequest("iPhone\n driver")
        p.recordFailure(request, "USB", null, "line1\tline2\nline3")
        assertEquals("line1 line2 line3", ConnectionProfiles(storage).health.value.single().lastProblems.single().detail)
        assertEquals("CARPLAY:usb:iphone driver", ConnectionProfiles.keyOf(request))
    }
}
