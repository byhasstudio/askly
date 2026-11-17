package com.byhasstudio.askly

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

const val PREFS = "askly"
const val EXTRA_CODE = "proj_code"
const val EXTRA_DATA = "proj_data"
private const val REQ_PROJECTION = 100

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var radioShort: RadioButton
    private lateinit var radioLong: RadioButton

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            setPadding(0, dp(16), 0, dp(4))
        }

        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        root.addView(TextView(this).apply {
            text = "Askly"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Asisten penjawab pertanyaan yang mengambang di atas app lain."
        })

        root.addView(label("URL Worker Cloudflare (https://...)"))
        urlInput = EditText(this).apply {
            hint = "https://nama-worker.akun.workers.dev"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("url", ""))
        }
        root.addView(urlInput)

        root.addView(label("Token app (APP_TOKEN di Worker, boleh kosong)"))
        tokenInput = EditText(this).apply {
            hint = "kata sandi bebas"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("token", ""))
        }
        root.addView(tokenInput)

        root.addView(label("Gaya jawaban"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        radioShort = RadioButton(this).apply { text = "Singkat, langsung ke inti"; id = 1001 }
        radioLong = RadioButton(this).apply { text = "Lengkap dengan penjelasan"; id = 1002 }
        group.addView(radioShort)
        group.addView(radioLong)
        if (prefs.getString("style", "singkat") == "lengkap") radioLong.isChecked = true
        else radioShort.isChecked = true
        root.addView(group)

        root.addView(label("Langkah"))
        root.addView(button("1. Izinkan tampil di atas app lain") { openOverlaySettings() })
        root.addView(button("2. Bebaskan dari hemat baterai (agar tidak dimatikan sistem)") { requestBatteryExemption() })
        root.addView(button("3. Mulai Askly") { startAskly() })
        root.addView(button("Hentikan Askly") {
            stopService(Intent(this, OverlayService::class.java))
            toast("Askly dihentikan")
        })

        root.addView(TextView(this).apply {
            text = "Saat menekan Mulai, Android akan meminta izin merekam layar. " +
                "Izin ini dipakai hanya saat kamu menekan tombol 'Tanya layar'. " +
                "Kalau ditolak, Askly tetap jalan untuk pertanyaan ketikan."
            setPadding(0, dp(16), 0, 0)
        })

        val scroll = ScrollView(this)
        scroll.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        setContentView(scroll)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            toast("Izin sudah aktif")
        } else {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Askly sudah bebas dari hemat baterai")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun saveSettings(): Boolean {
        val url = urlInput.text.toString().trim()
        if (!url.startsWith("https://")) {
            toast("URL Worker harus diawali https://")
            return false
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("url", url)
            .putString("token", tokenInput.text.toString().trim())
            .putString("style", if (radioLong.isChecked) "lengkap" else "singkat")
            .apply()
        return true
    }

    private fun startAskly() {
        if (!saveSettings()) return
        if (!Settings.canDrawOverlays(this)) {
            toast("Tekan tombol 1 dulu untuk memberi izin tampil di atas app lain")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        val svc = Intent(this, OverlayService::class.java)
        if (resultCode == RESULT_OK && data != null) {
            svc.putExtra(EXTRA_CODE, resultCode)
            svc.putExtra(EXTRA_DATA, data)
        } else {
            toast("Izin rekam layar ditolak. Askly jalan hanya untuk pertanyaan ketikan.")
        }
        startForegroundService(svc)
        moveTaskToBack(true)
    }
}
