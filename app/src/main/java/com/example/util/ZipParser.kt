package com.example.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.model.ProjectInfo
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ZipParser {

    data class ParsedProject(
        val info: ProjectInfo,
        val files: Map<String, ByteArray>
    )

    fun parseZip(context: Context, uri: Uri): ParsedProject {
        val fileName = getFileName(context, uri)
        val projectName = deriveProjectName(fileName)
        val sanitizedRepoName = sanitizeRepoName(projectName)

        val files = mutableMapOf<String, ByteArray>()
        var totalBytes = 0L
        var hasGradleWrapper = false
        var hasSettingsGradle = false
        var hasBuildGradle = false

        // Detect single common root folder if whole zip is nested under a directory
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val path = normalizePath(entry.name)
                        // Ignore Mac / OS meta files
                        if (!path.startsWith("__MACOSX/") && !path.contains("/.DS_Store") && !path.startsWith(".DS_Store")) {
                            val bytes = readBytes(zis)
                            totalBytes += bytes.size
                            files[path] = bytes

                            val lower = path.lowercase(Locale.ROOT)
                            if (lower.endsWith("gradlew") || lower.endsWith("gradlew.bat")) {
                                hasGradleWrapper = true
                            }
                            if (lower.endsWith("settings.gradle") || lower.endsWith("settings.gradle.kts")) {
                                hasSettingsGradle = true
                            }
                            if (lower.endsWith("build.gradle") || lower.endsWith("build.gradle.kts")) {
                                hasBuildGradle = true
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        // Check if all files share a common root prefix
        val strippedFiles = stripCommonRootIfPresent(files)

        val info = ProjectInfo(
            name = projectName,
            sanitizedRepoName = sanitizedRepoName,
            zipSizeFormatted = formatBytes(totalBytes),
            fileCount = strippedFiles.size,
            hasGradleWrapper = hasGradleWrapper,
            hasSettingsGradle = hasSettingsGradle,
            hasBuildGradle = hasBuildGradle
        )

        return ParsedProject(info, strippedFiles)
    }

    private fun stripCommonRootIfPresent(files: Map<String, ByteArray>): Map<String, ByteArray> {
        if (files.isEmpty()) return files
        val keys = files.keys.toList()
        val firstSlash = keys.first().indexOf('/')
        if (firstSlash == -1) return files

        val candidateRoot = keys.first().substring(0, firstSlash + 1)
        val allShareRoot = keys.all { it.startsWith(candidateRoot) }
        if (!allShareRoot) return files

        val result = mutableMapOf<String, ByteArray>()
        for ((path, bytes) in files) {
            val stripped = path.removePrefix(candidateRoot)
            if (stripped.isNotEmpty()) {
                result[stripped] = bytes
            }
        }
        return result
    }

    private fun normalizePath(raw: String): String {
        return raw.replace('\\', '/').trimStart('/')
    }

    private fun readBytes(zis: ZipInputStream): ByteArray {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var read: Int
        while (zis.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun getFileName(context: Context, uri: Uri): String {
        var name = "projeto-android"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                val found = cursor.getString(nameIndex)
                if (!found.isNullOrBlank()) {
                    name = found
                }
            }
        }
        return name
    }

    fun deriveProjectName(fileName: String): String {
        var base = fileName.substringBeforeLast('.')
        if (base.isBlank()) base = "meu-app-android"
        return base
    }

    fun sanitizeRepoName(name: String): String {
        val clean = name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
        val finalName = if (clean.isBlank()) "android-app" else clean
        return if (finalName.length > 50) finalName.take(50) else finalName
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.getDefault(), "%.1f MB", mb)
    }
}
