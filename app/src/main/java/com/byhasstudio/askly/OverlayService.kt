package com.byhasstudio.askly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleShown = false

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelShown = false

    private lateinit var input: EditText
    private lateinit var answer: TextView
    private lateinit var styleToggle: TextView
    private lateinit var sendBtn: Button
    private lateinit var shotBtn: Button

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vDisplay: VirtualDisplay? = null
    private var heldImage: Image? = null
    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0

    private var started = false
    private var busy = false
    private var style = "singkat"

    // Penjaga: memeriksa berkala apakah jendela melayang masih terpasang.
    private val watchdog = object : Runnable {
        override fun run() {
            ensureOverlayVisible()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Satu-satunya jalan berhenti: tombol "Hentikan" / "Matikan" yang ditekan user.
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        started = true

        // intent == null berarti service dihidupkan ulang oleh sistem setelah sempat
        // dimatikan (START_STICKY). Dalam kasus itu bubble tetap muncul lagi, hanya
        // izin screenshot yang hilang sampai user menekan Mulai Askly lagi.
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val withProjection = data != null

        startAsForeground(withProjection)

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izin tampil di atas app lain belum aktif", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        style = getSharedPreferences(PREFS, MODE_PRIVATE).getString("style", "singkat") ?: "singkat"

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        screenDpi = dm.densityDpi

        if (withProjection) {
            try {
                setupProjection(code, data!!)
            } catch (e: Exception) {
                Toast.makeText(this, "Screenshot tidak bisa diaktifkan: ${e.message}", Toast.LENGTH_LONG).show()
                releaseProjection()
            }
        }

        buildBubble()
        buildPanel()
        showBubble()
        handler.postDelayed(watchdog, WATCHDOG_MS)
        return START_STICKY
    }

    private fun startAsForeground(withProjection: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Askly", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Hentikan",
            stopIntent
        ).build()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Askly aktif")
            .setContentText("Tap bubble ? untuk bertanya")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(stopAction)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            val type = when {
                withProjection -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else -> 0
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------- Screenshot (MediaProjection) ----------

    private fun setupProjection(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { releaseProjection() }
            }
        }, handler)
        projection = p

        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener(ImageReader.OnImageAvailableListener { rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                heldImage?.close()
                heldImage = img
            }
        }, handler)
        reader = r

        vDisplay = p.createVirtualDisplay(
            "askly-shot", screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
    }

    private fun releaseProjection() {
        val p = projection
        projection = null
        try { heldImage?.close() } catch (e: Exception) { }
        heldImage = null
        try { vDisplay?.release() } catch (e: Exception) { }
        vDisplay = null
        try { reader?.close() } catch (e: Exception) { }
        reader = null
        try { p?.stop() } catch (e: Exception) { }
        if (::shotBtn.isInitialized) shotBtn.alpha = 0.4f
    }

    private fun imageToBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val bw = rowStride / pixelStride
        val bmp = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
        val buf = plane.buffer
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)
        return if (bw != img.width) Bitmap.createBitmap(bmp, 0, 0, img.width, img.height) else bmp
    }

    private fun grabScreenBase64(): String? {
        val img = heldImage ?: return null
        var bmp = imageToBitmap(img)
        val maxSide = 1280
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) {
            bmp = Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
            )
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ---------- UI mengambang ----------

    private fun overlayParams(w: Int, h: Int, flags: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )

    private fun dragListener(
        lp: WindowManager.LayoutParams,
        target: View,
        onClick: (() -> Unit)?
    ): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        return View.OnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    try { wm.updateViewLayout(target, lp) } catch (ex: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onClick?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    private fun buildBubble() {
        val size = dp(52)
        val tv = TextView(this).apply {
            text = "?"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6C5CE7"))
            }
        }
        val lp = overlayParams(
            size, size,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(200)
        }
        tv.setOnTouchListener(dragListener(lp, tv) { openPanel() })
        bubble = tv
        bubbleParams = lp
    }

    private fun buildPanel() {
        val pad = dp(12)
        val dark = Color.parseColor("#222222")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CCCCCC"))
            }
        }

        // Header (bisa digeser)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Askly (geser)"
            setTextColor(dark)
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        styleToggle = TextView(this).apply {
            text = styleLabel()
            setTextColor(Color.parseColor("#6C5CE7"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                style = if (style == "singkat") "lengkap" else "singkat"
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("style", style).apply()
                text = styleLabel()
            }
        }
        header.addView(styleToggle)

        // Tombol matikan Askly sepenuhnya. Perlu dua kali tekan supaya tidak tertutup tidak sengaja.
        var confirmStop = false
        val stopBtn = TextView(this).apply {
            text = "Matikan"
            setTextColor(Color.parseColor("#D63031"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        stopBtn.setOnClickListener {
            if (!confirmStop) {
                confirmStop = true
                stopBtn.text = "Yakin?"
                handler.postDelayed({
                    confirmStop = false
                    stopBtn.text = "Matikan"
                }, 3000)
            } else {
                stopByUser()
            }
        }
        header.addView(stopBtn)

        val close = TextView(this).apply {
            text = "\u2715"
            setTextColor(dark)
            textSize = 18f
            setPadding(dp(10), dp(6), dp(6), dp(6))
            setOnClickListener { closePanel() }
        }
        header.addView(close)
        root.addView(header)

        input = EditText(this).apply {
            hint = "Tulis pertanyaan..."
            setTextColor(dark)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 4
        }
        root.addView(input)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sendBtn = Button(this).apply {
            text = "Kirim"
            isAllCaps = false
            setOnClickListener { ask(false) }
        }
        shotBtn = Button(this).apply {
            text = "Tanya layar"
            isAllCaps = false
            alpha = if (projection == null) 0.4f else 1f
            setOnClickListener { ask(true) }
        }
        row.addView(sendBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(shotBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        answer = TextView(this).apply {
            setTextColor(dark)
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(dp(4), dp(8), dp(4), dp(4))
            text = "Jawaban akan muncul di sini."
        }
        val scroll = ScrollView(this)
        scroll.addView(answer)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (screenH * 0.3f).toInt()
            )
        )

        val lp = overlayParams(
            (screenW * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW * 0.04f).toInt()
            y = dp(80)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        header.setOnTouchListener(dragListener(lp, root, null))

        panel = root
        panelParams = lp
    }

    private fun styleLabel() = if (style == "singkat") "Singkat \u21C4" else "Lengkap \u21C4"

    private fun showBubble() {
        val b = bubble ?: return
        if (!bubbleShown) {
            try { wm.addView(b, bubbleParams) } catch (e: Exception) { }
            bubbleShown = true
        }
    }

    private fun hideBubble() {
        val b = bubble ?: return
        if (bubbleShown) {
            try { wm.removeView(b) } catch (e: Exception) { }
            bubbleShown = false
        }
    }

    private fun openPanel() {
        val p = panel ?: return
        hideBubble()
        if (!panelShown) {
            try { wm.addView(p, panelParams) } catch (e: Exception) { }
            panelShown = true
        }
        p.visibility = View.VISIBLE
        input.requestFocus()
    }

    private fun closePanel() {
        val p = panel ?: return
        if (panelShown) {
            try { wm.removeView(p) } catch (e: Exception) { }
            panelShown = false
        }
        showBubble()
    }

    // Kalau jendela melayang terlepas karena sebab lain (bukan karena user), pasang lagi.
    private fun ensureOverlayVisible() {
        if (!Settings.canDrawOverlays(this)) return
        if (panelShown) {
            val p = panel ?: return
            if (!p.isAttachedToWindow) {
                try { wm.addView(p, panelParams) } catch (e: Exception) { }
            }
        } else {
            val b = bubble ?: return
            if (!b.isAttachedToWindow) {
                bubbleShown = false
                showBubble()
            }
        }
    }

    // Dipanggil hanya dari tombol Matikan yang ditekan user.
    private fun stopByUser() {
        stopSelf()
    }

    // ---------- Bertanya ----------

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun setBusy(b: Boolean) {
        busy = b
        sendBtn.isEnabled = !b
        shotBtn.isEnabled = !b
    }

    private fun ask(withShot: Boolean) {
        if (busy) return
        val q = input.text.toString().trim()
        if (!withShot && q.isEmpty()) {
            toast("Tulis pertanyaan dulu")
            return
        }
        if (withShot && projection == null) {
            toast("Screenshot tidak aktif. Mulai ulang Askly dan izinkan rekam layar.")
            return
        }
        setBusy(true)
        answer.text = "Memproses..."

        if (!withShot) {
            send(q, null)
            return
        }

        // Sembunyikan panel supaya tidak ikut terpotret, lalu ambil frame terbaru.
        val p = panel
        p?.visibility = View.INVISIBLE
        handler.postDelayed({
            val b64 = try {
                grabScreenBase64()
            } catch (e: Exception) {
                null
            }
            p?.visibility = View.VISIBLE
            if (b64 == null) {
                finishAnswer("Gagal mengambil layar. Coba lagi.")
            } else {
                send(q, b64)
            }
        }, 450)
    }

    private fun send(question: String, imageB64: String?) {
        io.execute {
            val result = try {
                callWorker(question, imageB64)
            } catch (e: Exception) {
                "Gagal: ${e.message ?: "tidak ada koneksi"}"
            }
            handler.post { finishAnswer(result) }
        }
    }

    private fun finishAnswer(text: String) {
        answer.text = text
        setBusy(false)
    }

    private fun callWorker(question: String, imageB64: String?): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (url.isBlank()) return "Isi URL Worker di halaman pengaturan dulu."

        val body = JSONObject()
            .put("question", question)
            .put("style", style)
        if (imageB64 != null) body.put("image", imageB64)

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (token.isNotBlank()) conn.setRequestProperty("X-App-Token", token)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).optString("error")
                } catch (e: Exception) {
                    ""
                }
                return "Error $code: ${if (msg.isNotBlank()) msg else text.take(200)}"
            }
            val ans = JSONObject(text).optString("answer")
            return if (ans.isBlank()) "(jawaban kosong)" else ans
        } finally {
            conn.disconnect()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideBubble()
        panel?.let {
            if (panelShown) {
                try { wm.removeView(it) } catch (e: Exception) { }
                panelShown = false
            }
        }
        releaseProjection()
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "askly"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.byhasstudio.askly.STOP"
        private const val WATCHDOG_MS = 3000L
    }
}
