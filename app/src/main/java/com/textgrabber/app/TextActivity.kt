package com.textgrabber.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class TextActivity : AppCompatActivity() {
    private var tts: TtsManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TtsManager(this)
        setContentView(R.layout.activity_text)

        val tvText = findViewById<TextView>(R.id.tvText)
        val btnCopyAll = findViewById<Button>(R.id.btnCopyAll)

        val text = intent.getStringExtra("text") ?: ""
        tvText.text = text
        tvText.setTextColor(intent.getIntExtra("text_color", Color.BLACK))

        btnCopyAll.setOnClickListener {
            if (text.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OCR Text", text))
                Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try { FloatingService.visible?.invoke(false) } catch (e: Throwable) { e.printStackTrace() }
    }

    override fun onPause() {
        super.onPause()
        try { FloatingService.visible?.invoke(true) } catch (e: Throwable) { e.printStackTrace() }
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
