package com.textgrabber.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var ttsManager: TtsManager
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, FloatingService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, getString(R.string.toast_floating_started), Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, getString(R.string.toast_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        ttsManager = TtsManager(this)
        mainScope.launch { ttsManager.awaitReady() }

        // Ruxsatlar va sozlamalar
        setupUI()

        // Yangilanishni tekshirish (faqat release buildda ishlaydi deyishimiz mumkin, lekin hozir debugda ham ishlayveradi)
        UpdateChecker.checkForUpdates(this)
    }

    private fun setupUI() {
        // 1. INTERFEYS TILI (UI)
        val spinnerUiLang = findViewById<Spinner>(R.id.spinnerUiLang)
        val uiLangOptions = arrayOf(getString(R.string.ui_lang_uz), getString(R.string.ui_lang_ru), getString(R.string.ui_lang_en))
        val uiLangCodes = arrayOf("uz", "ru", "en")
        
        spinnerUiLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, uiLangOptions)
        
        // Joriy tilni aniqlash (AppCompat orqali yoki tizim tilidan)
        val activeLang = AppCompatDelegate.getApplicationLocales()[0]?.language 
            ?: resources.configuration.locales[0].language
        
        // 1.1 Spinner'ni to'g'ri indeksga qo'yish (LISTENER'DAN OLDIN!)
        val currentIndex = uiLangCodes.indexOf(activeLang).coerceAtLeast(0)
        spinnerUiLang.setSelection(currentIndex, false) // false = animatsiyasiz
        
        // 1.2 Keyin listener'ni ulaymiz
        spinnerUiLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedCode = uiLangCodes[position]
                if (selectedCode != activeLang) {
                    // AppCompat o'zi Activity'ni qayta yuklaydi, recreate() shart emas!
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selectedCode))
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 2. BILDIRISHNOMA
        val chkNotification = findViewById<CheckBox>(R.id.chkNotification)
        chkNotification.isChecked = prefs.getBoolean("show_notification", true)
        chkNotification.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_notification", isChecked).apply()
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // 3. TUGMALAR
        findViewById<Button>(R.id.btnFloating).setOnClickListener { checkOverlayAndStart() }
        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, getString(R.string.toast_stopped_short), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 4. O'QISH TILI (TTS)
        val spinnerTtsLang = findViewById<Spinner>(R.id.spinnerTtsLang)
        val langOptions = arrayOf(
            getString(R.string.tts_lang_auto),
            getString(R.string.tts_lang_uz),
            getString(R.string.tts_lang_ru),
            getString(R.string.tts_lang_en)
        )
        val langCodes = arrayOf("auto", "uz", "ru", "en")
        spinnerTtsLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langOptions)
        
        val savedLang = prefs.getString("tts_language", "auto") ?: "auto"
        spinnerTtsLang.setSelection(langCodes.indexOf(savedLang).coerceAtLeast(0))
        spinnerTtsLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedLang = langCodes[position]
                prefs.edit().putString("tts_language", selectedLang).apply()
                // Test tugmasining yozuvini TTS tiliga moslab yangilaymiz
                updateTestButtonLabel(selectedLang)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // Dastlabki holat uchun ham bir marta chaqiramiz
        updateTestButtonLabel(savedLang)

        // 5. EDGE TTS VA OVOZLAR
        val chkEdgeTts = findViewById<CheckBox>(R.id.chkEdgeTts)
        val edgeVoiceGroup = findViewById<RadioGroup>(R.id.edgeVoiceGroup)
        val radioMadina = findViewById<RadioButton>(R.id.radioMadina)
        val radioSardor = findViewById<RadioButton>(R.id.radioSardor)

        chkEdgeTts.isChecked = prefs.getBoolean("edge_tts_enabled", true)
        edgeVoiceGroup.visibility = if (chkEdgeTts.isChecked) android.view.View.VISIBLE else android.view.View.GONE
        chkEdgeTts.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("edge_tts_enabled", isChecked).apply()
            edgeVoiceGroup.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }

        val savedVoice = prefs.getString("edge_voice", "uz-UZ-MadinaNeural") ?: "uz-UZ-MadinaNeural"
        if (savedVoice == "uz-UZ-SardorNeural") radioSardor.isChecked = true else radioMadina.isChecked = true
        edgeVoiceGroup.setOnCheckedChangeListener { _, checkedId ->
            val v = if (checkedId == R.id.radioSardor) "uz-UZ-SardorNeural" else "uz-UZ-MadinaNeural"
            prefs.edit().putString("edge_voice", v).apply()
        }

        // 6. TEZLIK (100% Sukut bo'yicha)
        val rateSeek = findViewById<SeekBar>(R.id.rateSeekBar)
        val rateLabel = findViewById<TextView>(R.id.rateLabel)
        val savedRate = prefs.getInt("tts_rate", 100)
        rateSeek.progress = savedRate
        rateLabel.text = getString(R.string.rate_label, savedRate)
        rateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p.coerceIn(30, 130)
                rateLabel.text = getString(R.string.rate_label, v)
                prefs.edit().putInt("tts_rate", v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 7. TEST TUGMASI
        findViewById<Button>(R.id.btnTestTts).setOnClickListener {
            mainScope.launch {
                Toast.makeText(this@MainActivity, getString(R.string.toast_preparing), Toast.LENGTH_SHORT).show()
                ttsManager.awaitReady()
                val prefLang = prefs.getString("tts_language", "auto") ?: "auto"
                val effectiveLang = if (prefLang == "auto") {
                    AppCompatDelegate.getApplicationLocales()[0]?.language 
                        ?: resources.configuration.locales[0].language
                } else { prefLang }
                val testText = when (effectiveLang) {
                    "ru" -> "Привет мир"
                    "en" -> "Hello world"
                    else -> "Assalomu alaykum"
                }
                ttsManager.speak(testText)
            }
        }
    }
    private fun checkOverlayAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.toast_overlay_permission), Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun updateTestButtonLabel(ttsLang: String) {
        val btnTest = findViewById<Button>(R.id.btnTestTts)
        // "auto" bo'lsa, interfeys tilidagi yozuvni ishlatamiz
        val effectiveLang = if (ttsLang == "auto") {
            AppCompatDelegate.getApplicationLocales()[0]?.language 
                ?: resources.configuration.locales[0].language
        } else {
            ttsLang
        }
        btnTest.text = when (effectiveLang) {
            "ru" -> getString(R.string.btn_test_tts_ru)
            "en" -> getString(R.string.btn_test_tts_en)
            else -> getString(R.string.btn_test_tts_uz)
        }
    }

}
