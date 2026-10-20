package com.hippo.ehviewer.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.util.launchIO
import com.hippo.util.withUIContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

object WebDavBackupManager {
    private const val CHANNEL_ID = "webdav_backup"
    private const val NOTIFICATION_ID_PROGRESS = 1001
    private const val NOTIFICATION_ID_RESULT = 1002
    private const val BACKUP_ZIP_NAME = "ehviewer_backup.zip"
    private const val DB_ENTRY_NAME = "eh.export.db"
    private const val PREFS_ENTRY_NAME = "preferences.xml"

    private val operationMutex = Mutex()

    private fun getNotificationManager(context: Context): NotificationManager? {
        val nm = context.getSystemService<NotificationManager>() ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.webdav_backup_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }
        return nm
    }

    private fun showNotification(
        context: Context,
        id: Int,
        title: String,
        content: String,
        ongoing: Boolean = false,
        autoCancel: Boolean = true,
    ) {
        val nm = getNotificationManager(context) ?: return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(ongoing)
            .setAutoCancel(autoCancel)
        if (ongoing) {
            builder.setProgress(0, 0, true)
        }
        nm.notify(id, builder.build())
    }

    private fun cancelNotification(context: Context, id: Int) {
        getNotificationManager(context)?.cancel(id)
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService<ConnectivityManager>() ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun isConfigured(): Boolean = Settings.webDavServer.isNotBlank()

    private fun getWebDavTargetUrl(): String {
        var base = Settings.webDavServer.trim()
        if (!base.endsWith("/")) {
            base += "/"
        }
        return base + BACKUP_ZIP_NAME
    }

    private fun ensureRemoteDirectoryExists(baseUrl: String, authHeader: String?) {
        val parsed = baseUrl.toHttpUrlOrNull() ?: return
        val pathSegments = parsed.pathSegments
        if (pathSegments.isEmpty()) return

        var currentUrl = "${parsed.scheme}://${parsed.host}"
        if ((parsed.scheme == "http" && parsed.port != 80) || (parsed.scheme == "https" && parsed.port != 443)) {
            currentUrl += ":${parsed.port}"
        }

        val client = EhApplication.nonCacheOkHttpClient
        for (i in 0 until pathSegments.size - 1) {
            val seg = pathSegments[i]
            if (seg.isEmpty()) continue
            currentUrl += "/$seg"
            val mkcolReq = Request.Builder()
                .url(currentUrl)
                .method("MKCOL", null)
                .apply {
                    if (!authHeader.isNullOrBlank()) {
                        header("Authorization", authHeader)
                    }
                }
                .build()
            try {
                client.newCall(mkcolReq).execute().close()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun createBackupZip(context: Context): File {
        val tempDir = File(context.cacheDir, "webdav_backup_temp")
        tempDir.mkdirs()
        val zipFile = File(tempDir, BACKUP_ZIP_NAME)
        if (zipFile.exists()) {
            zipFile.delete()
        }

        val dbFile = File(tempDir, DB_ENTRY_NAME)
        if (dbFile.exists()) {
            dbFile.delete()
        }
        val exportSuccess = EhDB.exportDB(context, dbFile)
        if (!exportSuccess || !dbFile.exists()) {
            throw IOException("Failed to export database")
        }

        val prefsName = "${context.packageName}_preferences.xml"
        val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$prefsName")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            // 1. Add DB
            FileInputStream(dbFile).use { fis ->
                zos.putNextEntry(ZipEntry(DB_ENTRY_NAME))
                fis.copyTo(zos)
                zos.closeEntry()
            }
            // 2. Add Prefs
            if (prefsFile.exists()) {
                FileInputStream(prefsFile).use { fis ->
                    zos.putNextEntry(ZipEntry(PREFS_ENTRY_NAME))
                    fis.copyTo(zos)
                    zos.closeEntry()
                }
            }
        }
        dbFile.delete()
        return zipFile
    }

    suspend fun restoreBackupZip(context: Context, zipFile: File): String? {
        val tempDir = File(context.cacheDir, "webdav_restore_temp")
        tempDir.mkdirs()
        val dbFile = File(tempDir, DB_ENTRY_NAME)
        val prefsFile = File(tempDir, PREFS_ENTRY_NAME)
        if (dbFile.exists()) dbFile.delete()
        if (prefsFile.exists()) prefsFile.delete()

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (entry.name == DB_ENTRY_NAME) {
                    FileOutputStream(dbFile).use { fos ->
                        zis.copyTo(fos)
                    }
                } else if (entry.name == PREFS_ENTRY_NAME) {
                    FileOutputStream(prefsFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        if (!dbFile.exists()) {
            return context.getString(R.string.webdav_restore_corrupted)
        }

        val importError = EhDB.importDB(context, dbFile)
        if (importError != null) {
            return importError
        }

        if (prefsFile.exists()) {
            try {
                val targetPrefsName = "${context.packageName}_preferences.xml"
                val targetPrefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$targetPrefsName")
                targetPrefsFile.parentFile?.mkdirs()
                prefsFile.copyTo(targetPrefsFile, overwrite = true)
                // Reload preferences in memory
                Settings.initialize()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        dbFile.delete()
        prefsFile.delete()
        return null
    }

    suspend fun uploadBackup(context: Context, isAuto: Boolean = false): Boolean = operationMutex.withLock {
        if (!isConfigured()) {
            if (!isAuto) {
                withUIContext {
                    Toast.makeText(context, R.string.webdav_not_configured, Toast.LENGTH_SHORT).show()
                }
            }
            return false
        }

        val targetUrl = getWebDavTargetUrl()
        val authHeader = if (Settings.webDavUser.isNotBlank()) {
            Credentials.basic(Settings.webDavUser, Settings.webDavPassword)
        } else {
            null
        }

        val startingMsg = context.getString(R.string.webdav_backup_in_progress)
        showNotification(
            context,
            NOTIFICATION_ID_PROGRESS,
            context.getString(R.string.webdav_backup_notification_title),
            startingMsg,
            ongoing = true,
        )

        if (!isAuto) {
            withUIContext {
                Toast.makeText(context, startingMsg, Toast.LENGTH_SHORT).show()
            }
        }

        var zipFile: File? = null
        try {
            ensureRemoteDirectoryExists(targetUrl, authHeader)
            zipFile = createBackupZip(context)

            val client = EhApplication.nonCacheOkHttpClient
            val requestBody = zipFile.asRequestBody("application/zip".toMediaType())
            val request = Request.Builder()
                .url(targetUrl)
                .put(requestBody)
                .apply {
                    if (!authHeader.isNullOrBlank()) {
                        header("Authorization", authHeader)
                    }
                }
                .build()

            val response = client.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    cancelNotification(context, NOTIFICATION_ID_PROGRESS)
                    Settings.putWebDavLastBackupTime(System.currentTimeMillis())
                    val successMsg = context.getString(R.string.webdav_backup_success)
                    showNotification(
                        context,
                        NOTIFICATION_ID_RESULT,
                        context.getString(R.string.webdav_backup_notification_title),
                        successMsg,
                    )
                    withUIContext {
                        Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
                    }
                    return true
                } else {
                    throw IOException("HTTP ${it.code}: ${it.message}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            cancelNotification(context, NOTIFICATION_ID_PROGRESS)
            val failMsg = context.getString(
                R.string.webdav_backup_failed,
                e.localizedMessage ?: e.message ?: "",
            )
            showNotification(
                context,
                NOTIFICATION_ID_RESULT,
                context.getString(R.string.webdav_backup_notification_title),
                failMsg,
            )
            withUIContext {
                Toast.makeText(context, failMsg, Toast.LENGTH_LONG).show()
            }
            return false
        } finally {
            zipFile?.delete()
        }
    }

    suspend fun downloadRestore(context: Context): Boolean = operationMutex.withLock {
        if (!isConfigured()) {
            withUIContext {
                Toast.makeText(context, R.string.webdav_not_configured, Toast.LENGTH_SHORT).show()
            }
            return false
        }

        val targetUrl = getWebDavTargetUrl()
        val authHeader = if (Settings.webDavUser.isNotBlank()) {
            Credentials.basic(Settings.webDavUser, Settings.webDavPassword)
        } else {
            null
        }

        val startingMsg = context.getString(R.string.webdav_restore_in_progress)
        showNotification(
            context,
            NOTIFICATION_ID_PROGRESS,
            context.getString(R.string.webdav_restore_notification_title),
            startingMsg,
            ongoing = true,
        )

        withUIContext {
            Toast.makeText(context, startingMsg, Toast.LENGTH_SHORT).show()
        }

        val tempDir = File(context.cacheDir, "webdav_restore_download")
        tempDir.mkdirs()
        val tempZip = File(tempDir, "restore.zip")
        if (tempZip.exists()) tempZip.delete()

        try {
            val client = EhApplication.nonCacheOkHttpClient
            val request = Request.Builder()
                .url(targetUrl)
                .get()
                .apply {
                    if (!authHeader.isNullOrBlank()) {
                        header("Authorization", authHeader)
                    }
                }
                .build()

            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}: ${resp.message}")
                }
                val body = resp.body ?: throw IOException("Empty response body")
                FileOutputStream(tempZip).use { fos ->
                    body.byteStream().copyTo(fos)
                }
            }

            val restoreError = restoreBackupZip(context, tempZip)
            cancelNotification(context, NOTIFICATION_ID_PROGRESS)
            if (restoreError == null) {
                val successMsg = context.getString(R.string.webdav_restore_success)
                showNotification(
                    context,
                    NOTIFICATION_ID_RESULT,
                    context.getString(R.string.webdav_restore_notification_title),
                    successMsg,
                )
                withUIContext {
                    Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
                }
                return true
            } else {
                showNotification(
                    context,
                    NOTIFICATION_ID_RESULT,
                    context.getString(R.string.webdav_restore_notification_title),
                    restoreError,
                )
                withUIContext {
                    Toast.makeText(context, restoreError, Toast.LENGTH_LONG).show()
                }
                return false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            cancelNotification(context, NOTIFICATION_ID_PROGRESS)
            val failMsg = context.getString(
                R.string.webdav_restore_failed,
                e.localizedMessage ?: e.message ?: "",
            )
            showNotification(
                context,
                NOTIFICATION_ID_RESULT,
                context.getString(R.string.webdav_restore_notification_title),
                failMsg,
            )
            withUIContext {
                Toast.makeText(context, failMsg, Toast.LENGTH_LONG).show()
            }
            return false
        } finally {
            tempZip.delete()
        }
    }

    fun checkAndRunAutoBackup(context: Context) {
        val intervalHours = Settings.webDavAutoBackupInterval
        if (intervalHours <= 0) return
        if (!isConfigured()) return

        if (Settings.webDavWifiOnly && !isWifiConnected(context)) {
            return
        }

        val lastBackup = Settings.webDavLastBackupTime
        val intervalMillis = intervalHours * 3600 * 1000L
        val now = System.currentTimeMillis()
        if (now - lastBackup >= intervalMillis) {
            launchIO {
                uploadBackup(context, isAuto = true)
            }
        }
    }
}
