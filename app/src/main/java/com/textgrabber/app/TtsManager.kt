package com.textgrabber.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Locale

class TtsManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var edgeClient: EdgeTtsClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    
    var onTextUpdate: ((String) -> Unit)? = null
    var onSpeechFinished: (() -> Unit)? = null

    @Volatile private var ttsManagerGeneration = 0

    private data class VoiceChoice(val locale: String, val voice: String)

    suspend fun awaitReady() {}

    fun speak(text: String, ratePercent: Int = 100) {
        if (text.isEmpty()) return
        speakList(listOf(text), ratePercent)
    }

    fun speakList(textList: List<String>, ratePercent: Int = 100) {
        if (textList.isEmpty()) return

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val rate = prefs.getInt("tts_rate", ratePercent)
        val edgeEnabled = prefs.getBoolean("edge_tts_enabled", true)

        if (!edgeEnabled) {
            Toast.makeText(context, context.getString(R.string.toast_online_disabled), Toast.LENGTH_SHORT).show()
            return
        }

        if (!isNetworkAvailable()) {
            Toast.makeText(context, context.getString(R.string.toast_no_internet), Toast.LENGTH_SHORT).show()
            return
        }

        if (edgeClient == null) edgeClient = EdgeTtsClient(context)
        
        ttsManagerGeneration++
        val currentGen = ttsManagerGeneration
        edgeClient?.stop()

        val selection = prefs.getString("tts_language", "auto") ?: "auto"
        val isAutoMode = selection.trim().lowercase(Locale.ROOT) == "auto"
        
        val sampleText = textList.take(3).joinToString(" ")
        val choice = resolveVoice(selection, sampleText)
        
        val edgeVoice = prefs.getString("edge_voice", "uz-UZ-MadinaNeural") ?: "uz-UZ-MadinaNeural"
        val finalVoice = if (choice.locale == "uz-UZ" && edgeVoice == "uz-UZ-SardorNeural") edgeVoice else choice.voice
        val edgeRate = rate - 100

        val cleanTextList = textList.map {
            it.replace(Regex("[\\r\\n\\t\\u200B]+"), " ")
              .replace(Regex("[\\p{So}\\p{Cs}•·|_*#~^]"), " ")
              .replace(Regex("\\s{2,}"), " ")
              .trim()
        }.filter { it.count { ch -> ch.isLetterOrDigit() } >= 2 }

        val combinedText = cleanTextList.joinToString(". ")
        val safeChunks = splitTextIntoSafeChunks(combinedText, 800)

        Log.i("TtsManager", "speakList: blocks=${textList.size}, chunks=${safeChunks.size}, voice=$finalVoice, autoMode=$isAutoMode")
        
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TextGrabber::TTS").apply { acquire(10 * 60 * 1000L) }
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TextGrabber::Wifi").apply { acquire() }
        } catch (e: Exception) { Log.e("TtsManager", "Lock error", e) }

        if (isAutoMode) {
            speakNextBlockAuto(safeChunks, 0, edgeRate, currentGen)
        } else {
            speakNextBlock(safeChunks, 0, finalVoice, edgeRate, choice.locale, currentGen)
        }
    }

    private fun speakNextBlock(textList: List<String>, index: Int, voice: String, rate: Int, locale: String, gen: Int) {
        if (gen != ttsManagerGeneration) return
        if (index >= textList.size) {
            Log.i("TtsManager", "All blocks played")
            releaseLocks()
            return
        }

        var block = textList[index]
        block = when (locale) {
            "uz-UZ" -> TextSanitizer.convertDigits(TextSanitizer.convertOrdinals(TextSanitizer.cyrillicToLatinUz(block), "uz"), "uz")
            "ru-RU" -> TextSanitizer.convertDigits(TextSanitizer.convertOrdinals(block, "ru"), "ru")
            "en-US" -> TextSanitizer.convertDigits(TextSanitizer.convertOrdinals(block, "en"), "en")
            else -> block
        }

        edgeClient?.speak(block, voice, rate, object : EdgeTtsClient.Callback {
            override fun onStart() {
                onTextUpdate?.invoke(block)
            }
            override fun onDone() {
                if (gen == ttsManagerGeneration) speakNextBlock(textList, index + 1, voice, rate, locale, gen)
            }
            override fun onError(e: Throwable) {
                Log.e("TtsManager", "edge tts error", e)
                if (gen == ttsManagerGeneration) speakNextBlock(textList, index + 1, voice, rate, locale, gen)
            }
        })
    }

    private fun speakNextBlockAuto(textList: List<String>, index: Int, rate: Int, gen: Int) {
        if (gen != ttsManagerGeneration) return
        if (index >= textList.size) {
            Log.i("TtsManager", "Auto: All blocks played")
            releaseLocks()
            return
        }

        var block = textList[index]
        val detectedLang = detectLang(block)
        val voice = when (detectedLang) {
            "ru" -> "ru-RU-SvetlanaNeural"
            "en" -> "en-US-AriaNeural"
            else -> "uz-UZ-MadinaNeural"
        }
        val locale = when (detectedLang) {
            "ru" -> "ru-RU"
            "en" -> "en-US"
            else -> "uz-UZ"
        }

        block = when (locale) {
            "uz-UZ" -> TextSanitizer.convertDigits(TextSanitizer.convertOrdinals(TextSanitizer.cyrillicToLatinUz(block), "uz"), "uz")
            "ru-RU" -> TextSanitizer.convertDigits(TextSanitizer.convertOrdinals(block, "ru"), "ru")
            "en-US" -> TextSanitizer.convertDigits(TextSanitizer.convertOrdinals(block, "en"), "en")
            else -> block
        }

        Log.i("TtsManager", "AUTO chunk ${index + 1}/${textList.size} lang=$detectedLang voice=$voice")

        edgeClient?.speak(block, voice, rate, object : EdgeTtsClient.Callback {
            override fun onStart() {
                onTextUpdate?.invoke(block)
            }
            override fun onDone() {
                if (gen == ttsManagerGeneration) speakNextBlockAuto(textList, index + 1, rate, gen)
            }
            override fun onError(e: Throwable) {
                Log.e("TtsManager", "edge tts error", e)
                if (gen == ttsManagerGeneration) speakNextBlockAuto(textList, index + 1, rate, gen)
            }
        })
    }

    private fun splitTextIntoSafeChunks(text: String, maxLen: Int): List<String> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        for (s in sentences) {
            if (s.length > maxLen) {
                var start = 0
                while (start < s.length) {
                    val end = minOf(start + maxLen, s.length)
                    var cut = end
                    if (end < s.length) { val lastSpace = s.lastIndexOf(' ', end); if (lastSpace > start) cut = lastSpace }
                    chunks.add(s.substring(start, cut).trim()); start = cut
                }
            } else {
                if (current.length + s.length > maxLen) { if (current.isNotEmpty()) { chunks.add(current.toString().trim()); current = StringBuilder() } }
                current.append(s).append(" ")
            }
        }
        if (current.isNotEmpty()) chunks.add(current.toString().trim())
        return chunks
    }

    fun stop() {
        ttsManagerGeneration++
        edgeClient?.stop()
        releaseLocks()
        // onSpeechFinished bu yerda chaqirilmaydi! 
        // Aks holda "O'qish" bosilganda tugma darhol "O'qish" ga qaytib qoladi.
    }

    fun shutdown() {
        stop()
        edgeClient = null
    }

    fun isSpeaking(): Boolean = edgeClient?.isSpeaking() ?: false

    private fun resolveVoice(selection: String, text: String): VoiceChoice {
        val language = when (selection.trim().lowercase(Locale.ROOT)) {
            "uz" -> "uz"
            "ru" -> "ru"
            "en" -> "en"
            else -> when (detectLang(text).lowercase(Locale.ROOT)) {
                "uz" -> "uz"
                "ru" -> "ru"
                "en" -> "en"
                else -> "uz"
            }
        }
        return when (language) {
            "ru" -> VoiceChoice("ru-RU", "ru-RU-SvetlanaNeural")
            "en" -> VoiceChoice("en-US", "en-US-AriaNeural")
            else -> VoiceChoice("uz-UZ", "uz-UZ-MadinaNeural")
        }
    }

    private fun detectLang(text: String): String {
        if (text.any { it in "ўқғҳЎҚҒҲ" }) return "uz"
        val newUz = "\u00F6\u011F\u015F\u00E7\u00D6\u011E\u015E\u00C7"
        if (text.any { it in newUz }) return "uz"
        val uzbSpecific = text.count { it == '\u02BB' || it == '\u02BC' || it == '\u2018' || it == '\u2019' }
        if (uzbSpecific > 0) return "uz"
        val uzPattern = Regex("(?i)\\b[og]'[a-z]").findAll(text).count()
        if (uzPattern >= 2) return "uz"

        val cyrCount = text.count { it in '\u0400'..'\u04FF' }
        val latCount = text.count { it in 'A'..'Z' || it in 'a'..'z' }

        if (cyrCount > 0 && cyrCount >= latCount) return "ru"
        if (cyrCount > 3 && cyrCount * 2 > latCount) return "ru"

        val uzWords = setOf(
            "va", "bu", "uchun", "bilan", "ham", "lekin", "yoki", "salom", "assalomu", "alaykum",
            "rahmat", "xayr", "men", "sen", "biz", "siz", "ular",
            "qanday", "nima", "qayerda", "qachon", "kim", "nega", "ozbek", "uzbek",
            "kitob", "maktab", "universitet", "shahar", "qishloq", "davlat", "xalq",
            "ozbekiston", "toshkent", "samarqand", "qandaysiz", "dunyo"
        )
        val lowerWords = text.lowercase().split(Regex("\\s+")).take(30)
        val uzMatches = lowerWords.count { it in uzWords }
        if (uzMatches >= 2) return "uz"
        if (uzMatches >= 1 && cyrCount == 0) return "uz"

        if (latCount > 0) return "en"
        return "uz"
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) { false }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
        } catch (e: Exception) {}
    }
}
