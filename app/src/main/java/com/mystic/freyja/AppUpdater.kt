package com.mystic.freyja

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AppUpdater(private val activity: Activity) {

    fun checkForUpdate() {
        Thread {
            try {
                val url = URL(
                    "https://raw.githubusercontent.com/mystichero1/Freyja/refs/heads/main/version.json?t=" +
                        System.currentTimeMillis()
                )
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache")

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonString)

                    val remoteVersionName = json.getString("versionName")
                    val remoteVersionCode = json.optLong("versionCode", -1L)
                    val apkUrl = json.getString("apkUrl")
                    val forceUpdate = json.getBoolean("forceUpdate")

                    val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                    val localVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pInfo.longVersionCode
                    } else {
                        pInfo.versionCode.toLong()
                    }

                    // Prefer numeric versionCode comparison, fall back to name for
                    // version.json files that don't declare a versionCode yet.
                    val updateAvailable = when {
                        remoteVersionCode > 0L -> localVersionCode < remoteVersionCode
                        else -> pInfo.versionName != remoteVersionName
                    }

                    if (updateAvailable) {
                        Handler(Looper.getMainLooper()).post {
                            showUpdateDialog(apkUrl, forceUpdate)
                        }
                    }
                }
            } catch (e: Exception) {
                // Update check is best-effort; do not nag the user when offline
            }
        }.start()
    }

    private fun showUpdateDialog(apkUrl: String, forceUpdate: Boolean) {
        val builder = AlertDialog.Builder(activity)
            .setTitle("Update Available")
            .setMessage("A new version of Freyja is available. You must update to continue using the app.")
            .setCancelable(!forceUpdate)
            .setPositiveButton("Update Now") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                activity.startActivity(intent)
                if (forceUpdate) {
                    activity.finish()
                }
            }

        if (!forceUpdate) {
            builder.setNegativeButton("Later", null)
        }

        builder.show()
    }
}