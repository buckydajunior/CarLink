package com.carlink.app.diagnostics

import android.app.Application
import com.carlink.core.diagnostics.EventLog
import com.carlink.core.diagnostics.LogMasking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 29], application = Application::class)
class ConnectionLogTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @After fun clearEvents() = EventLog.clear()

    @Test fun masksDeviceAddressesInSharedLogs() {
        assertEquals("Phone [address] connected", LogMasking.mask("Phone AA:bb:01:23:45:67 connected"))
        assertEquals("id [address]", LogMasking.mask("id aa-bb-cc-dd-ee-ff"))
        // Times and versions are left alone.
        assertEquals("12:04:33.120 v1.2.3", LogMasking.mask("12:04:33.120 v1.2.3"))
    }

    @Test fun masksPasswordValues() {
        assertEquals("joined password=[hidden], psk: [hidden]", LogMasking.mask("joined password=s3cret!, psk: abc123"))
        assertEquals("passphrase=[hidden] ok", LogMasking.mask("passphrase=\"two words\" ok"))
        // Words alone are left alone.
        assertEquals("wrong password, try again", LogMasking.mask("wrong password, try again"))
    }

    @Test fun eventLogMasksAndKeepsOnlyTheNewestEntries() {
        repeat(EventLog.CAPACITY + 5) { EventLog.add("Test", "event $it") }
        EventLog.add("Bluetooth", "11:22:33:44:55:66 bonded")
        val entries = EventLog.entries.value
        assertEquals(EventLog.CAPACITY, entries.size)
        assertEquals("[address] bonded", entries.last().message)
    }

    @Test fun datesDiPlayLinesFromTheHeaderAndRollsOverMidnight() {
        val lines = ConnectionLog.parseDiPlay(listOf(
            "DiPlay log started 2026-10-09 23:59:58.000 pid=1 path=x",
            "23:59:59.500  Video start failed",
            "00:00:01.000  Retrying",
        ), 0)
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        assertEquals(2, lines.size)
        assertEquals("2026-10-09 23:59:59.500", format.format(lines[0].timeMillis))
        assertEquals("2026-10-10 00:00:01.000", format.format(lines[1].timeMillis))
        assertEquals('E', lines[0].level)
    }

    @Test fun anIdleWiredControlReadIsNotAProblemButAFailureIs() {
        val counters = "elapsedMs=100 readCalls=1833 writeCalls=40 readTimeouts=1797 ends=0 failures=0 maxReadMs=104 maxWriteMs=6"
        val prefix = "CONNECTION_DIAGNOSTIC attempt=12 run=1 phase=CONTROL wired io"
        val lines = ConnectionLog.parseDiPlay(listOf(
            "DiPlay log started 2026-10-23 17:43:00.000 pid=1 path=x",
            "17:46:52.356  $prefix operation=READ result=TIMED_OUT $counters", // issue #5, every 10 s
            "17:46:52.400  $prefix operation=READ result=COMPLETED $counters",
            "17:46:52.500  CONNECTION_DIAGNOSTIC attempt=12 run=1 phase=CONTROL wired io final readCalls=9 readTimeouts=8",
            "17:46:53.000  $prefix operation=READ result=FAILED $counters",
            "17:46:54.000  $prefix operation=READ result=ENDED $counters",
            "17:46:55.000  AirPlay timeout waiting for RTSP SETUP",
        ), 0)
        assertEquals(listOf(null, null, null, 'E', 'W', 'W'), lines.map { it.level })
    }

    @Test fun exportsWiredTimingAndSavedEventsInOrderWithoutWirelessProbes() {
        File(context.filesDir, "logs").apply { mkdirs() }.resolve("diplay.log").writeText(
            "DiPlay log started 2026-11-18 12:00:00.000 pid=1 path=x\n" +
            "12:00:00.010  WIRED_TIMING iphone-found plug+10ms\n" +
            "12:00:03.100  WIRED_TIMING first-frame plug+3100ms\n")
        val file = ConnectionLog.export(context, "F-150 connection log")
        val text = file.readText()
        assertTrue(file.path.contains("/cache/shared/"))
        assertTrue(text.startsWith("F-150 connection log\nConnection: USB CarPlay"))
        assertTrue(text.indexOf("iphone-found") < text.indexOf("first-frame"))
        assertTrue(text.contains("plug+3100ms"))
        org.junit.Assert.assertFalse(text.contains("External Bluetooth probe"))
    }

    @Test fun savedCarLinkLinesComeBackForReportsAfterARestart() {
        val e = ConnectionLog.parseSaved("1793734323164 [CarLink] start CARPLAY USB #s1")!!
        org.junit.Assert.assertEquals(1793734323164L, e.timeMillis)
        org.junit.Assert.assertEquals("CarLink", e.tag)
        org.junit.Assert.assertEquals("start CARPLAY USB #s1", e.message)
        org.junit.Assert.assertNull(ConnectionLog.parseSaved("  signals=none"))
    }

    @Test fun exportIncludesAnEarlierSavedCrashWithoutLeakingItsSensitiveLines() {
        val crash = File(context.filesDir, "logs/last-crash.txt")
        crash.parentFile!!.mkdirs()
        val original = "crash atEpochMs=1234 thread=main\n" +
            "java.lang.IllegalStateException: startup failure at 192.168.1.20\n" +
            "  at com.carlink.app.CarLinkApplication.onCreate(CarLinkApplication.kt:20)\n" +
            "phoneName=PRIVATE_PHONE\npassword=PRIVATE_SECRET\npeer AA:BB:01:23:45:67\n"
        crash.writeText(original)
        EventLog.clear() // A reopened process has no fatal event in its new in-memory buffer.
        val text = ConnectionLog.export(context, "Installed version 1.3.2").readText()
        assertTrue(text.contains("Saved Java crash (may be from an earlier installed version)"))
        assertTrue(text.contains("crash atEpochMs=1234 thread=main"))
        assertTrue(text.contains("java.lang.IllegalStateException: startup failure at [ip]"))
        assertTrue(text.contains("CarLinkApplication.onCreate"))
        org.junit.Assert.assertFalse(text.contains("PRIVATE_PHONE"))
        org.junit.Assert.assertFalse(text.contains("PRIVATE_SECRET"))
        org.junit.Assert.assertFalse(text.contains("AA:BB:01:23:45:67"))
        assertEquals("Export must preserve the original evidence", original, crash.readText())
    }

    @Test fun aMissingJavaCrashRecordDoesNotClaimThereWasNoCrash() {
        File(context.filesDir, "logs/last-crash.txt").delete()
        val text = ConnectionLog.export(context, "F-150 connection log").readText()
        assertTrue(text.contains("No crash recorded by this version."))
        assertTrue(text.contains("does not rule out a native crash or an unresponsive app"))
    }
}
