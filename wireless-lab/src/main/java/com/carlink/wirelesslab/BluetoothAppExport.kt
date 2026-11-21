package com.carlink.wirelesslab

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Copies only this installed package's public base/split APKs. Never reads dataDir or loads code. */
internal object BluetoothAppExport {
    const val PACKAGE = "com.szchoiceway.btsuite"
    private const val MAX_APK_BYTES = 32L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    @Suppress("DEPRECATION")
    fun prepare(context: Context): File {
        val info = context.packageManager.getPackageInfo(PACKAGE, 0)
        val app = requireNotNull(info.applicationInfo)
        check(app.packageName == PACKAGE)
        val files = (listOf(requireNotNull(app.sourceDir)) + app.splitSourceDirs.orEmpty())
            .map { File(it).canonicalFile }.distinct()
        if (files.size !in 1..16) throw IOException()
        val sizes = files.map { file ->
            if (!file.isFile || !file.canRead() || file.extension != "apk") throw IOException()
            file.length().also { if (it !in 1..MAX_APK_BYTES) throw IOException() }
        }
        if (sizes.sum() > MAX_TOTAL_BYTES) throw IOException()
        val dir = File(context.cacheDir, "reports").apply { check(isDirectory || mkdirs()) }
        val output = File.createTempFile("linkswell-bluetooth-", ".zip", dir)
        try {
            val entries = JSONArray()
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                files.forEachIndexed { index, source ->
                    val name = if (index == 0) "base.apk" else "split-$index.apk"
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                    var copied = 0L
                    source.inputStream().buffered().use { input ->
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            total += count
                            if (copied > MAX_APK_BYTES || total > MAX_TOTAL_BYTES) throw IOException()
                            digest.update(buffer, 0, count)
                            zip.write(buffer, 0, count)
                        }
                    }
                    if (copied != sizes[index]) throw IOException() // Reject a changing/truncated input.
                    zip.closeEntry()
                    entries.put(JSONObject().put("name", name).put("bytes", copied)
                        .put("sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }))
                }
                val metadata = JSONObject().put("package", PACKAGE)
                    .put("versionName", info.versionName.orEmpty().take(100))
                    .put("versionCode", info.versionCode).put("files", entries)
                zip.putNextEntry(ZipEntry("code-info.json").apply { time = 0 })
                zip.write(metadata.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            dir.listFiles().orEmpty().filter { it != output && it.name.startsWith("linkswell-bluetooth-") && it.extension == "zip" }
                .sortedByDescending { it.lastModified() }.drop(1).forEach { it.delete() }
            return output
        } catch (failure: Exception) {
            output.delete()
            throw failure
        }
    }

    fun failureMessage(failure: Throwable) = if (failure is PackageManager.NameNotFoundException) {
        R.string.carlink_lab_export_missing
    } else R.string.carlink_lab_export_failed

    /** Destination is granted by the user's ACTION_CREATE_DOCUMENT choice, never a typed path. */
    fun save(context: Context, name: String, destination: Uri) {
        require(destination.scheme == "content")
        require(name.matches(Regex("linkswell-bluetooth-[0-9]+\\.zip")))
        val file = File(File(context.cacheDir, "reports"), name)
        require(file.isFile && file.length() in 1..(MAX_TOTAL_BYTES + 1024 * 1024))
        file.inputStream().buffered().use { input ->
            requireNotNull(context.contentResolver.openOutputStream(destination, "w")).buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                var copied = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > MAX_TOTAL_BYTES + 1024 * 1024) throw IOException()
                    output.write(buffer, 0, count)
                }
            }
        }
    }
}
