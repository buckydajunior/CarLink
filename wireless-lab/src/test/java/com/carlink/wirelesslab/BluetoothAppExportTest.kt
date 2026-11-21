package com.carlink.wirelesslab

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33])
class BluetoothAppExportTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun apk(name: String, text: String) = File(context.filesDir, name).apply { writeText(text) }
    private fun install(base: File, splits: List<File> = emptyList()) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = BluetoothAppExport.PACKAGE
            versionName = "1.0"
            @Suppress("DEPRECATION")
            versionCode = 1
            applicationInfo = ApplicationInfo().apply {
                packageName = BluetoothAppExport.PACKAGE
                sourceDir = base.path
                splitSourceDirs = splits.map { it.path }.toTypedArray()
                dataDir = File(context.filesDir, "private-pairings").apply {
                    mkdirs()
                    File(this, "phone-keys").writeText("SECRET_PHONE_PAIRING")
                }.path
            }
        })
    }

    @Test fun copiesOnlyFixedPackagesCodeAndPublicMetadataIncludingSplits() {
        val base = apk("factory-base.apk", "BASE_CODE")
        val split = apk("device-specific-name.apk", "SPLIT_CODE")
        install(base, listOf(split))
        val output = BluetoothAppExport.prepare(context)
        ZipFile(output).use { zip ->
            assertEquals(setOf("base.apk", "split-1.apk", "code-info.json"), zip.entries().asSequence().map { it.name }.toSet())
            assertArrayEquals(base.readBytes(), zip.getInputStream(zip.getEntry("base.apk")).readBytes())
            assertArrayEquals(split.readBytes(), zip.getInputStream(zip.getEntry("split-1.apk")).readBytes())
            val jsonText = zip.getInputStream(zip.getEntry("code-info.json")).bufferedReader().readText()
            val json = JSONObject(jsonText)
            assertEquals(BluetoothAppExport.PACKAGE, json.getString("package"))
            val expected = MessageDigest.getInstance("SHA-256").digest(base.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, json.getJSONArray("files").getJSONObject(0).getString("sha256"))
            assertFalse(jsonText.contains(context.filesDir.path))
            assertFalse(jsonText.contains("SECRET_PHONE_PAIRING"))
            assertFalse(jsonText.contains("device-specific-name"))
        }
    }

    @Test fun missingTargetDoesNotExportAnotherInstalledPackage() {
        val failure = runCatching { BluetoothAppExport.prepare(context) }.exceptionOrNull()!!
        assertEquals(R.string.carlink_lab_export_missing, BluetoothAppExport.failureMessage(failure))
    }

    @Test fun rejectsMissingEmptyAndOversizeApksWithoutLeavingAnArchive() {
        for (mode in listOf("missing", "empty", "oversize")) {
            val file = File(context.filesDir, "$mode.apk")
            when (mode) {
                "empty" -> file.writeBytes(byteArrayOf())
                "oversize" -> RandomAccessFile(file, "rw").use { it.setLength(33L * 1024 * 1024) }
            }
            install(file)
            assertTrue(runCatching { BluetoothAppExport.prepare(context) }.exceptionOrNull() is IOException)
        }
        assertTrue(File(context.cacheDir, "reports").listFiles().orEmpty().isEmpty())
    }

    @Test fun interruptedCopyRemovesPartialArchive() {
        install(apk("base.apk", "BASE_CODE"))
        try {
            Thread.currentThread().interrupt()
            assertTrue(runCatching { BluetoothAppExport.prepare(context) }.exceptionOrNull() is IOException)
        } finally {
            Thread.interrupted()
        }
        assertTrue(File(context.cacheDir, "reports").listFiles().orEmpty().isEmpty())
    }

    @Test fun retainsOnlyTwoCodeArchivesWithoutDeletingTextReports() {
        install(apk("base.apk", "BASE_CODE"))
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val report = File(dir, "compatibility.txt").apply { writeText("KEEP_REPORT") }
        repeat(4) { BluetoothAppExport.prepare(context) }
        assertEquals(2, dir.listFiles().orEmpty().count { it.extension == "zip" })
        assertEquals("KEEP_REPORT", report.readText())
    }

    @Test fun clockMovingBackwardsCannotDeleteTheFreshArchive() {
        install(apk("base.apk", "BASE_CODE"))
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        for (name in listOf("linkswell-bluetooth-1.zip", "linkswell-bluetooth-2.zip")) {
            File(dir, name).apply { writeText("OLD"); setLastModified(System.currentTimeMillis() + 86400000) }
        }
        val output = BluetoothAppExport.prepare(context)
        assertTrue(output.isFile)
        assertEquals(2, dir.listFiles().orEmpty().count { it.extension == "zip" })
    }

    @Test fun savesExactZipToUserSelectedContentDestination() {
        install(apk("base.apk", "BASE_CODE"))
        val archive = BluetoothAppExport.prepare(context)
        val destination = Uri.parse("content://test.documents/new-archive")
        val bytes = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(destination, bytes)
        BluetoothAppExport.save(context, archive.name, destination)
        assertArrayEquals(archive.readBytes(), bytes.toByteArray())
    }

    @Test fun saveRejectsArbitraryFilesAndNonContentDestinations() {
        install(apk("base.apk", "BASE_CODE"))
        val archive = BluetoothAppExport.prepare(context)
        assertTrue(runCatching { BluetoothAppExport.save(context, "../private.txt", Uri.parse("content://test/target")) }.isFailure)
        assertTrue(runCatching { BluetoothAppExport.save(context, archive.name, Uri.parse("file:///sdcard/target.zip")) }.isFailure)
        assertTrue(runCatching { BluetoothAppExport.save(context, archive.name, Uri.parse("https://example.com/upload")) }.isFailure)
    }
}
