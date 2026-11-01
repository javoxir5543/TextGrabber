package com.textgrabber.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object UpdateChecker {
    // O'ZGARTIRING: GitHub usernameingizni yozing
    private const val GITHUB_USER = "javoxir5543"
    private const val GITHUB_REPO = "TextGrabber"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_USER/$GITHUB_REPO/releases/latest"

    fun checkForUpdates(activity: Activity) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(API_URL).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.d("UpdateChecker", "API error: ${response.code}")
                    return@launch
                }

                val json = JSONObject(response.body?.string() ?: "")
                val latestVersion = json.getString("tag_name").replace("v", "").trim()
                
                // Hozirgi versiyani olish
                val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val currentVersion = pInfo.versionName ?: "1.0"

                Log.d("UpdateChecker", "Current: $currentVersion, Latest: $latestVersion")

                if (isNewerVersion(latestVersion, currentVersion)) {
                    val assets = json.getJSONArray("assets")
                    if (assets.length() > 0) {
                        val downloadUrl = assets.getJSONObject(0).getString("browser_download_url")
                        withContext(Dispatchers.Main) {
                            showUpdateDialog(activity, latestVersion, downloadUrl)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("UpdateChecker", "Error checking updates", e)
            }
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            false
        } catch (e: Exception) {
            latest != current
        }
    }

    private fun showUpdateDialog(activity: Activity, newVersion: String, url: String) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title))
            .setMessage(activity.getString(R.string.update_message, newVersion))
            .setPositiveButton(activity.getString(R.string.update_now)) { _, _ ->
                downloadAndInstall(activity, url)
            }
            .setNegativeButton(activity.getString(R.string.update_later), null)
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, url: String) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(activity, activity.getString(R.string.update_downloading), android.widget.Toast.LENGTH_LONG).show()
                }

                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) throw Exception("Download failed")

                val apkFile = File(activity.cacheDir, "update.apk")
                FileOutputStream(apkFile).use { out ->
                    response.body?.byteStream()?.copyTo(out)
                }

                withContext(Dispatchers.Main) {
                    installApk(activity, apkFile)
                }
            } catch (e: Exception) {
                Log.e("UpdateChecker", "Download error", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(activity, "Xato: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(activity: Activity, apkFile: File) {
        // Android 8.0+ noma'lum manbalardan o'rnatishga ruxsat tekshiruvi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                android.widget.Toast.makeText(activity, activity.getString(R.string.update_allow_install), android.widget.Toast.LENGTH_LONG).show()
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${activity.packageName}"))
                activity.startActivity(intent)
                return
            }
        }

        val apkUri = FileProvider.getUriForFile(
            activity,
            "com.textgrabber.app.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
