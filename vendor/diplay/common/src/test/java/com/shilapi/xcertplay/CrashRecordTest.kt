package com.shilapi.xcertplay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33], manifest = Config.NONE) // CarLink: this head unit runs Android 8.1.
class CrashRecordTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun theRecordNamesTheExceptionChainAndWhereItWasThrown() {
        val cause = IllegalStateException("surface released at 192.168.1.20")
        val crash = RuntimeException("Unable to start activity\nsecond line", cause)
        crash.stackTrace = Array(20) { StackTraceElement("com.shilapi.xcertplay.DiPlayActivity", "onCreate$it", "DiPlayActivity.kt", it) }

        val record = CrashRecord.format("main", crash, 1_000L)

        val lines = record.lines()
        assertEquals("crash atEpochMs=1000 thread=main", lines[0])
        assertEquals("java.lang.RuntimeException: Unable to start activity", lines[1])
        assertEquals("  at com.shilapi.xcertplay.DiPlayActivity.onCreate0(DiPlayActivity.kt:0)", lines[2])
        assertTrue(record.contains("  at com.shilapi.xcertplay.DiPlayActivity.onCreate17(DiPlayActivity.kt:17)"))
        assertTrue(record.contains("  ... 2 more"))
        assertTrue(record.contains("caused by java.lang.IllegalStateException: surface released at 192.168.1.20"))
        assertFalse(record.contains("second line"))
    }

    // #463: Android's startActivity frames filled the record and hid the DiPlay call that crashed.
    @Test fun ourFramesBelowTheFirstOnesAreKept() {
        val framework = Array(14) { StackTraceElement("android.app.Activity", "startActivity$it", "Activity.java", it) }
        val ours = Array(9) { StackTraceElement("com.shilapi.xcertplay.DiPlayActivity", "open$it", "DiPlayActivity.kt", it) }
        val crash = IllegalArgumentException("There is no running activity in rootTask")
        crash.stackTrace = framework + ours + StackTraceElement("android.os.Looper", "loop", "Looper.java", 1)

        val record = CrashRecord.format("main", crash, 0)

        assertTrue(record.contains("  at android.app.Activity.startActivity11(Activity.java:11)"))
        assertFalse(record.contains("startActivity12"))
        assertTrue(record.contains("  ... App/engine frames further down:")) // CarLink: includes the embedding host.
        assertTrue(record.contains("  at com.shilapi.xcertplay.DiPlayActivity.open0(DiPlayActivity.kt:0)"))
        assertTrue(record.contains("  at com.shilapi.xcertplay.DiPlayActivity.open5(DiPlayActivity.kt:5)"))
        assertFalse(record.contains("open6"))
        assertTrue(record.contains("  ... 6 more"))
    }

    @Test fun aCauseLoopEndsTheChain() {
        val first = RuntimeException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)

        assertEquals(2, CrashRecord.format("worker", first, 0).lines().count { it.startsWith("java.") || it.startsWith("caused by") })
    }

    @Test fun theReportShowsTheRedactedRecordOrSaysThereIsNone() {
        val file = File(context.filesDir, "logs/last-crash.txt")
        file.delete()
        assertEquals("No crash recorded by this version.", CrashRecord.report(context))

        file.parentFile?.mkdirs()
        file.writeText(CrashRecord.format("main", IllegalStateException("surface released at 192.168.1.20"), 5L))
        val report = CrashRecord.report(context)
        assertTrue(report, report.startsWith("crash atEpochMs=5 thread=main\njava.lang.IllegalStateException: surface released at [ip]"))
    }

    // CarLink: startup failures can have framework frames before the embedding Application caller.
    @Test fun theEmbeddingHostsFramesFurtherDownAreRetained() {
        val framework = Array(15) { StackTraceElement("android.app.ContextImpl", "getSharedPreferences$it", "ContextImpl.java", it) }
        val crash = IllegalStateException("SharedPreferences unavailable before unlock")
        crash.stackTrace = framework + StackTraceElement("com.carlink.app.CarLinkApplication", "onCreate", "CarLinkApplication.kt", 15)
        assertTrue(CrashRecord.format("main", crash, 0).contains("com.carlink.app.CarLinkApplication.onCreate"))
    }

    // CarLink: public host diagnostics must not read a corrupt/legacy record without bounds.
    @Test fun aLargeRecordIsBoundedBeforeRedactionAndKeepsItsRecentEvidence() {
        val file = File(context.filesDir, "logs/last-crash.txt")
        file.parentFile!!.mkdirs()
        file.writeText("OLD_RECORD" + "x".repeat(100_000) + "\n" +
            "crash atEpochMs=1234 thread=main\njava.lang.IllegalStateException: recent failure\n")
        val report = CarPlayHostIntegration.lastCrashReport(context)
        assertTrue(report.startsWith("Older crash record content omitted."))
        assertTrue(report.contains("recent failure"))
        assertFalse(report.contains("OLD_RECORD"))
        assertTrue(report.length < 66_000)
    }

    // CarLink: byte-small records can still have excessive line counts.
    @Test fun aManyLineRecordIsExplicitlyTruncated() {
        val file = File(context.filesDir, "logs/last-crash.txt")
        file.parentFile!!.mkdirs()
        file.writeText((0..150).joinToString("\n") { "frame $it" })
        val report = CrashRecord.report(context)
        assertTrue(report.startsWith("Older crash record content omitted."))
        assertTrue(report.lines().size <= 98)
        assertTrue(report.contains("frame 150"))
        assertFalse(report.contains("frame 0\n"))
    }
}
