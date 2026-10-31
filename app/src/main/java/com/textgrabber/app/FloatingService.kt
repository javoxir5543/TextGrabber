package com.textgrabber.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.widget.Toast
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.*
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.text.TextRecognizer
import java.io.File
import java.io.FileOutputStream

class FloatingService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var floatingViewAdded = false
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var idleReader: ImageReader? = null
    private var latestImage: android.media.Image? = null
    private var latestFrameTime = 0L
    private var projectionCallback: MediaProjection.Callback? = null
    private var captureTimeout: Runnable? = null
    private var capturing = false
    private val main = Handler(Looper.getMainLooper())
    private var tts: TtsManager? = null
    private var longPressRunnable: Runnable? = null
    private var overlayView: View? = null
    private var recognizer: TextRecognizer? = null

    companion object {
        private const val TAG = "FloatingService"
        private const val CAPTURE_TIMEOUT_MS = 3000L
        private const val LONG_PRESS_MS = 600L
        var visible: ((Boolean) -> Unit)? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TtsManager(this)
        createFloatingButton()
        startForegroundServiceWithNotification()
    }

    private fun getRecognizer(): TextRecognizer =
        recognizer ?: HybridOcr.createRecognizer().also { recognizer = it }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") { stopSelf(); return START_NOT_STICKY }
        if (mediaProjection == null) {
            val code = intent?.getIntExtra("resultCode", 0) ?: 0
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent?.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("data")
            if (data == null) return START_NOT_STICKY
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = try { mpm.getMediaProjection(code, data) } catch (e: Exception) {
                Log.w(TAG, "getMediaProjection failed", e); null
            }
            if (projection == null) { restoreButton(); return START_NOT_STICKY }
            mediaProjection = projection
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    mediaProjection = null
                    virtualDisplay?.release(); virtualDisplay = null
                    idleReader?.close(); idleReader = null
                    cancelCapture()
                    restoreButton()
                }
            }
            projectionCallback = cb
            projection.registerCallback(cb, main)
            createDisplayOnce()
        }
        return START_STICKY
    }

    private fun createDisplayOnce() {
        if (virtualDisplay != null) return
        if (mediaProjection == null) { restoreButton(); return }
        val m = resources.displayMetrics
        val reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 3)
        idleReader = reader
        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                try { latestImage?.close() } catch (e: Exception) {}
                latestImage = img
                latestFrameTime = SystemClock.uptimeMillis()
            }
        }, main)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC, reader.surface, null, main)
        if (virtualDisplay == null) Log.e(TAG, "createVirtualDisplay returned NULL")
        else Log.i(TAG, "virtual display created ${m.widthPixels}x${m.heightPixels}")
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "ocr_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        val stopIntent = Intent(this, FloatingService::class.java).apply { action = "STOP_SERVICE" }
        val stopPi = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val showNotification = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("show_notification", true)
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
        if (showNotification)
            builder.addAction(android.R.drawable.ic_delete, getString(R.string.notif_stop_action), stopPi)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(1, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(1, builder.build())
        } catch (e: Exception) { Log.w(TAG, "startForeground failed", e) }
    }

    override fun onDestroy() {
        longPressRunnable?.let { main.removeCallbacks(it) }
        longPressRunnable = null
        try { tts?.shutdown() } catch (e: Exception) {}
        tts = null
        closeOverlay()
        cancelCapture()
        projectionCallback?.let { cb -> try { mediaProjection?.unregisterCallback(cb) } catch (e: Exception) {} }
        projectionCallback = null
        try { mediaProjection?.stop() } catch (e: Exception) {}
        mediaProjection = null
        try { virtualDisplay?.release() } catch (e: Exception) {}
        virtualDisplay = null
        try { latestImage?.close() } catch (e: Exception) {}
        latestImage = null
        try { idleReader?.close() } catch (e: Exception) {}
        idleReader = null
        try { recognizer?.close() } catch (e: Exception) {}
        recognizer = null
        if (this::floatingView.isInitialized && floatingViewAdded) {
            floatingViewAdded = false
            try { windowManager.removeView(floatingView) } catch (e: Exception) {}
        }
        visible = null
        super.onDestroy()
    }
    private fun createFloatingButton() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START; params.x = 100; params.y = 100
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_button, null)
        windowManager.addView(floatingView, params)
        floatingViewAdded = true
        visible = { show -> setButtonVisible(show) }
        floatingView.setOnTouchListener(object : View.OnTouchListener {
            private var ix = 0
            private var iy = 0
            private var tx = 0f
            private var ty = 0f
            private var longPressFired = false
            override fun onTouch(v: View?, e: MotionEvent?): Boolean {
                when (e?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY
                        longPressFired = false
                        longPressRunnable?.let { main.removeCallbacks(it) }
                        val lpr = Runnable { longPressFired = true; readScreenAloud() }
                        longPressRunnable = lpr
                        main.postDelayed(lpr, LONG_PRESS_MS)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        longPressRunnable?.let { main.removeCallbacks(it) }
                        longPressRunnable = null
                        val moved = Math.abs(e.rawX - tx) >= 10 || Math.abs(e.rawY - ty) >= 10
                        if (!longPressFired && !moved) startCapture()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val moved = Math.abs(e.rawX - tx) >= 10 || Math.abs(e.rawY - ty) >= 10
                        if (moved) {
                            longPressRunnable?.let { main.removeCallbacks(it) }
                            longPressRunnable = null
                        }
                        params.x = ix + (e.rawX - tx).toInt(); params.y = iy + (e.rawY - ty).toInt()
                        try { windowManager.updateViewLayout(floatingView, params) } catch (ex: Exception) {}
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun setButtonVisible(show: Boolean) {
        try {
            if (this::floatingView.isInitialized)
                floatingView.visibility = if (show) View.VISIBLE else View.GONE
        } catch (e: Exception) { Log.w(TAG, "setButtonVisible failed", e) }
    }

    private fun restoreButton() { main.post { setButtonVisible(true) } }

    private fun getButtonY(): Int {
        return try {
            val loc = IntArray(2)
            floatingView.getLocationOnScreen(loc)
            loc[1]
        } catch (e: Exception) { -1 }
    }

    private fun readScreenAloud() {
        val svc = TextReaderService.instance
        if (svc == null) {
            Toast.makeText(this, getString(R.string.accessibility_not_enabled), Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {}
            return
        }
        if (tts?.isSpeaking() == true) {
            tts?.stop()
            Toast.makeText(this, getString(R.string.toast_tts_stopped), Toast.LENGTH_SHORT).show()
            return
        }
        val selected = try { svc.getSelectedText() } catch (e: Exception) { "" }
        val buttonY = getButtonY()
        Toast.makeText(this, getString(R.string.toast_read_start), Toast.LENGTH_SHORT).show()
        Thread {
            val finalList: List<String> = if (selected.isNotBlank()) listOf(selected)
            else svc.getAllScreenTextFromYWithScroll(if (buttonY > 0) buttonY else 0)
            Log.i(TAG, "read: collected ${finalList.size} blocks")
            main.post {
                if (finalList.isEmpty()) {
                    Toast.makeText(this, getString(R.string.tts_no_text), Toast.LENGTH_SHORT).show()
                    return@post
                }
                tts?.stop()
                tts?.speakList(finalList)
            }
        }.start()
    }

    private fun cancelCapture() {
        captureTimeout?.let { main.removeCallbacks(it) }
        captureTimeout = null
        capturing = false
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val m = resources.displayMetrics
        val w = m.widthPixels
        val h = m.heightPixels
        var padded: Bitmap? = null
        var bmp: Bitmap? = null
        return try {
            val p = image.planes[0]
            val rowStride = p.rowStride
            val pixelStride = p.pixelStride
            val buffer = p.buffer
            buffer.rewind()
            val paddedWidth = rowStride / pixelStride
            val height = buffer.remaining() / rowStride
            if (paddedWidth <= 0 || height <= 0) {
                Log.w(TAG, "bad frame geometry paddedWidth=$paddedWidth h=$height")
                null
            } else {
                padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buffer)
                val full = Bitmap.createBitmap(padded, 0, 0, minOf(w, paddedWidth), minOf(h, height))
                bmp = if (full === padded) padded.copy(Bitmap.Config.ARGB_8888, false) else full
                bmp
            }
        } catch (t: Throwable) {
            Log.w(TAG, "frame conversion failed", t)
            null
        } finally {
            if (padded != null && padded !== bmp) padded.recycle()
        }
    }

    private fun startCapture() {
        if (capturing) return
        if (mediaProjection == null || virtualDisplay == null) { restoreButton(); return }
        capturing = true
        setButtonVisible(false)
        val t0 = SystemClock.uptimeMillis()
        val attempt = object : Runnable {
            override fun run() {
                if (!capturing) return
                val now = SystemClock.uptimeMillis()
                val img = latestImage
                val fresh = latestFrameTime >= t0 + 50
                if (img != null && (fresh || now - t0 > 1200)) {
                    val bmp = imageToBitmap(img)
                    if (bmp != null) {
                        Log.i(TAG, "captured ${bmp.width}x${bmp.height} fresh=$fresh")
                        capturing = false
                        captureTimeout = null
                        setButtonVisible(true)
                        saveAndShow(bmp)
                        return
                    }
                }
                if (now - t0 > CAPTURE_TIMEOUT_MS) {
                    Log.w(TAG, "capture timeout (latestImage=${img != null})")
                    capturing = false
                    captureTimeout = null
                    setButtonVisible(true)
                    return
                }
                captureTimeout = this
                main.postDelayed(this, 100)
            }
        }
        captureTimeout = attempt
        main.postDelayed(attempt, 150)
    }

    private fun saveAndShow(bitmap: Bitmap) {
        Thread {
            try {
                val file = File(cacheDir, "screen_capture.png")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (e: Exception) { Log.w(TAG, "could not save capture", e) }
            main.post { showOverlay(bitmap) }
        }.start()
    }
    private fun closeOverlay() {
        overlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        overlayView = null
    }

    private fun showOverlay(bitmap: Bitmap) {
        Log.i(TAG, "showOverlay called")
        try {
            closeOverlay()
            val themed = androidx.appcompat.view.ContextThemeWrapper(
                this, androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
            val root = LayoutInflater.from(themed).inflate(R.layout.overlay_capture, null)

            val photoView = root.findViewById<com.github.chrisbanes.photoview.PhotoView>(R.id.photoViewOverlay)
            val selectView = root.findViewById<TextSelectView>(R.id.selectViewOverlay)
            val btnClose = root.findViewById<android.widget.Button>(R.id.btnCloseOverlay)
            val btnToggleMode = root.findViewById<android.widget.Button>(R.id.btnToggleMode)
            val btnCopy = root.findViewById<android.widget.Button>(R.id.btnCopyOverlay)
            val btnRead = root.findViewById<android.widget.Button>(R.id.btnReadOverlay)

            photoView.setImageBitmap(bitmap)
                        var ocrDone = false

            val rec = getRecognizer()
            Thread {
                try {
                    Log.i(TAG, "OCR start")
                    val result = HybridOcr.recognize(applicationContext, bitmap, rec)
                    Log.i(TAG, "OCR done: words=${result.words.size}")
                    main.post {
                        try {
                            if (overlayView === root) {
                                selectView.setWords(result.words, photoView)
                                ocrDone = true
                            }
                        } catch (e: Exception) { Log.e(TAG, "setWords error", e) }
                    }
                } catch (e: Throwable) { Log.e(TAG, "OCR error", e) }
            }.start()

            fun currentText(): String {
                val startIdx = selectView.getSelectedStartIndex()
                return if (startIdx >= 0) selectView.getTextFrom(startIdx) else selectView.getTextFrom(0)
            }

            btnClose.setOnClickListener { closeOverlay() }

            // Standart: Zoom rejimi (selectMode = false)
            selectView.selectMode = false
            btnToggleMode.text = "🔍 Zoom rejimi"

            btnToggleMode.setOnClickListener {
                val isSelect = !selectView.selectMode
                selectView.selectMode = isSelect
                btnToggleMode.text = getString(if (isSelect) R.string.overlay_select_mode else R.string.overlay_zoom_mode)
                btnToggleMode.setBackgroundColor(if (isSelect) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
                Toast.makeText(this, getString(if (isSelect) R.string.toast_select_on else R.string.toast_zoom_on), Toast.LENGTH_SHORT).show()
            }

            btnCopy.setOnClickListener {
                if (!ocrDone) {
                    Toast.makeText(this, getString(R.string.toast_ocr_wait), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // FAQAT belgilangan so'zlarni olamiz
                val selected = selectView.getSelectedTextOnly()
                if (selected.isNotBlank()) {
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("TextGrabber", selected))
                    Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_select_first), Toast.LENGTH_SHORT).show()
                }
            }

            btnRead.setOnClickListener {
                if (!ocrDone) {
                    Toast.makeText(this, getString(R.string.toast_ocr_wait), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // Belgilangan bo'lsa — faqat belgilangan, aks holda — butun matn
                val selectedOnly = selectView.getSelectedTextOnly()
                val textToRead = if (selectedOnly.isNotBlank()) selectedOnly else selectView.getTextFrom(0)
                Log.i(TAG, "READ pressed: selected=${selectedOnly.length}, full=${textToRead.length}")
                if (textToRead.isNotBlank()) {
                    tts?.stop()
                    tts?.speak(textToRead)
                } else {
                    Toast.makeText(this, getString(R.string.toast_no_text), Toast.LENGTH_SHORT).show()
                }
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(root, lp)
            overlayView = root
        } catch (e: Exception) {
            Log.e(TAG, "Overlay error", e)
        }
    }
}
