package com.example.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ApkDownloader {

    /**
     * Extracts the real .apk file from the GitHub artifact zip and saves it to public Downloads directory
     */
    fun saveArtifactApkToDownloads(
        context: Context,
        artifactZipBytes: ByteArray,
        projectName: String
    ): File? {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            var apkBytes: ByteArray? = null
            var apkFileName = "$projectName-release.apk"

            // Look inside the zip for .apk
            ZipInputStream(ByteArrayInputStream(artifactZipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                        apkFileName = entry.name.substringAfterLast('/')
                        apkBytes = zis.readBytes()
                        break
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // If no apk directly found, the bytes might already be the raw apk or we save the zip
            val targetBytes = apkBytes ?: artifactZipBytes
            val finalFile = File(downloadsDir, if (apkBytes != null) apkFileName else "$projectName-build.apk")

            FileOutputStream(finalFile).use { fos ->
                fos.write(targetBytes)
                fos.flush()
            }

            // Register with system DownloadManager so it shows in Downloads app
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            downloadManager?.addCompletedDownload(
                finalFile.name,
                "APK compilado via GitHub Actions",
                true,
                "application/vnd.android.package-archive",
                finalFile.absolutePath,
                finalFile.length(),
                true
            )

            finalFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Prompts the user to install or view the APK using an Intent
     */
    fun promptInstallApk(context: Context, apkFile: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Arquivo salvo em Downloads: ${apkFile.name}", Toast.LENGTH_LONG).show()
        }
    }
}
