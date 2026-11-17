package com.byhasstudio.askly

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {

    private const val RELEASE_API = "https://api.github.com/repos/byhasstudio/askly/releases/tags/latest"

    @Volatile
    private var checking = false
    private var promptedBuild = 0L

    private fun currentBuild(act: Activity): Long =
        act.packageManager.getPackageInfo(act.packageName, 0).longVersionCode

    // Dipanggil setiap Askly dibuka. Cek release terbaru, tawarkan update bila ada.
    fun check(act: Activity) {
        if (checking) return
        checking = true
        Thread {
            try {
                val conn = URL(RELEASE_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) return@Thread
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })

                val remote = Regex("build (\\d+)").find(json.optString("name"))
                    ?.groupValues?.get(1)?.toLongOrNull() ?: return@Thread

                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (apkUrl.isNullOrBlank()) return@Thread

                if (remote > currentBuild(act) && remote != promptedBuild) {
                    promptedBuild = remote
                    act.runOnUiThread {
                        if (!act.isFinishing) showDialog(act, remote, apkUrl)
                    }
                }
            } catch (e: Exception) {
                // Tidak ada internet atau GitHub tidak terjangkau: lewati saja.
            } finally {
                checking = false
            }
        }.start()
    }

    private fun showDialog(act: Activity, build: Long, url: String) {
        AlertDialog.Builder(act)
            .setTitle("Update tersedia")
            .setMessage("Versi baru Askly (build $build) siap dipasang.")
            .setPositiveButton("Update") { _, _ -> startUpdate(act, url) }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun startUpdate(act: Activity, url: String) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            promptedBuild = 0L
            Toast.makeText(
                act,
                "Aktifkan 'Izinkan dari sumber ini' untuk Askly, lalu buka Askly lagi.",
                Toast.LENGTH_LONG
            ).show()
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName))
            )
            return
        }
        Toast.makeText(act, "Mengunduh update...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val dir = File(act.cacheDir, "updates")
                dir.mkdirs()
                val file = File(dir, "askly.apk")
                if (file.exists()) file.delete()

                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 120000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                conn.inputStream.use { input ->
                    file.outputStream().use { out -> input.copyTo(out) }
                }
                act.runOnUiThread { install(act, file) }
            } catch (e: Exception) {
                act.runOnUiThread {
                    Toast.makeText(act, "Gagal mengunduh update: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun install(act: Activity, file: File) {
        val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        act.startActivity(intent)
    }
}
