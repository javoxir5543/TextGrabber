package com.textgrabber.app

import android.content.Context
import android.media.MediaPlayer
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EdgeTtsClient(private val context: Context) {
    interface Callback {
        fun onStart()
        fun onDone()
        fun onError(e: Throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .readTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()
    private val cacheDir = File(context.cacheDir, "edge_tts").apply { mkdirs() }

    @Volatile private var currentWs: WebSocket? = null
    @Volatile private var currentPlayer: MediaPlayer? = null
    @Volatile private var speakGeneration = 0

    fun speak(text: String, voice: String = "uz-UZ-MadinaNeural", ratePercent: Int = 0, cb: Callback) {
        val gen = ++speakGeneration
        stopInternal(closeWs = true)
        val cleanText = TextSanitizer.sanitize(text)
        if (cleanText.isBlank()) { cb.onError(Exception("empty")); return }
        val ttsText = convertNewUzbekAlphabet(cleanText)

        scope.launch {
            try {
                Log.d("EdgeTts", "=== START: voice=$voice textLen=${ttsText.length} ===")
                val file = downloadMp3(ttsText, voice, ratePercent, gen)
                if (file != null && gen == speakGeneration) {
                    withContext(Dispatchers.Main) { playFile(file, gen, cb) }
                } else if (gen == speakGeneration) {
                    withContext(Dispatchers.Main) { cb.onError(Exception("No audio (chunks=0)")) }
                }
            } catch (e: Throwable) {
                Log.e("EdgeTts", "exception", e)
                withContext(Dispatchers.Main) { cb.onError(e) }
            }
        }
    }

    private fun formatTimestamp(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun makeSsml(voice: String, ratePercent: Int, text: String): String {
        val rate = if (ratePercent == 0) "+0%" else (if (ratePercent > 0) "+${ratePercent}%" else "${ratePercent}%")
        val escaped = escapeXml(text)
        // Ovoz nomidan tilni aniqlaymiz (masalan: uz-UZ-MadinaNeural -> uz-UZ)
        val lang = voice.split("-").take(2).joinToString("-").ifBlank { "en-US" }
        Log.d("EdgeTts", "SSML_DEBUG_TEXT: ${escaped.take(60)}")
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
                "<voice name='$voice'>" +
                "<prosody pitch='+0Hz' rate='$rate' volume='+0%'>" +
                "$escaped" +
                "</prosody>" +
                "</voice>" +
                "</speak>"
    }

    private suspend fun downloadMp3(ttsText: String, voice: String, ratePercent: Int, gen: Int): File? = withContext(Dispatchers.IO) {
        try {
            val token = EdgeTtsTokenGenerator.generateV2Token()
            val version = EdgeTtsTokenGenerator.getVersion()
            val connId = UUID.randomUUID().toString().replace("-", "")
            val baseUrl = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
            val url = "$baseUrl?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4" +
                    "&ConnectionId=$connId" +
                    "&Sec-MS-GEC=$token" +
                    "&Sec-MS-GEC-Version=$version"

            val request = Request.Builder().url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-WebSocket-Version", "13")
                .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
                .build()

            val chunks = mutableListOf<ByteArray>()
            val latch = CountDownLatch(1)
            var err: Throwable? = null

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("EdgeTts", "WS OPEN")
                    val config = "X-Timestamp:${formatTimestamp()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                    webSocket.send(config)

                    val ssml = makeSsml(voice, ratePercent, ttsText)
                    val requestId = UUID.randomUUID().toString().replace("-", "")
                    val ssmlMsg = "X-RequestId:$requestId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${formatTimestamp()}Z\r\nPath:ssml\r\n\r\n$ssml"
                    webSocket.send(ssmlMsg)
                    Log.d("EdgeTts", "SSML sent")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("turn.end")) {
                        Log.d("EdgeTts", "turn.end received -> closing")
                        latch.countDown() // Darhol kutishni to'xtatamiz (3 soniya kutmimiz)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val arr = bytes.toByteArray()
                    if (arr.size < 2) return
                    val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                    if (arr.size < 2 + headerLen) return
                    val header = String(arr, 2, headerLen)
                    val audio = arr.copyOfRange(2 + headerLen, arr.size)
                    if (header.contains("Path:audio") && audio.isNotEmpty()) {
                        chunks.add(audio)
                        Log.d("EdgeTts", "AUDIO +${audio.size} (total ${chunks.sumOf { it.size }})")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    err = t
                    Log.e("EdgeTts", "WS FAILURE: code=${response?.code} err=${t.message}")
                    latch.countDown()
                }
            }

            currentWs = http.newWebSocket(request, listener)
            
            // turn.end kelishi bilan latch ochiladi va darhol davom etamiz
            val completed = latch.await(15, TimeUnit.SECONDS)
            try { currentWs?.close(1000, null) } catch (_: Exception) {}
            currentWs = null

            if (err != null || chunks.isEmpty()) {
                Log.w("EdgeTts", "FAIL: err=${err?.message} chunks=${chunks.size} completed=$completed")
                return@withContext null
            }

            val file = File(cacheDir, "tts_${System.currentTimeMillis()}_${gen}.mp3")
            file.outputStream().use { os -> for (c in chunks) os.write(c) }
            Log.d("EdgeTts", "SAVED ${file.length()} bytes")
            file
        } catch (e: Throwable) {
            Log.e("EdgeTts", "downloadMp3 exception", e)
            null
        }
    }

    private fun playFile(file: File, gen: Int, cb: Callback) {
        if (gen != speakGeneration) { file.delete(); return }
        val mp = MediaPlayer()
        try {
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { p ->
                if (gen != speakGeneration) {
                    try { p.stop() } catch (_: Exception) {}
                    p.release(); file.delete(); return@setOnPreparedListener
                }
                currentPlayer = p
                p.start()
                cb.onStart()
            }
            mp.setOnCompletionListener { p ->
                try { p.release() } catch (_: Exception) {}
                file.delete()
                if (currentPlayer === p) currentPlayer = null
                cb.onDone()
            }
            mp.setOnErrorListener { p, w, e ->
                try { p.release() } catch (_: Exception) {}
                file.delete()
                if (currentPlayer === p) currentPlayer = null
                cb.onError(Exception("player $w/$e"))
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            try { mp.release() } catch (_: Exception) {}
            file.delete()
            cb.onError(e)
        }
    }

    fun stop() {
        speakGeneration++
        stopInternal(closeWs = true)
    }

    private fun stopInternal(closeWs: Boolean) {
        if (closeWs) {
            try { currentWs?.close(1000, null) } catch (_: Exception) {}
            currentWs = null
        }
        currentPlayer?.let { p ->
            try { if (p.isPlaying) p.stop() } catch (_: Exception) {}
            try { p.reset() } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
        currentPlayer = null
    }

    fun isSpeaking(): Boolean = currentPlayer?.isPlaying == true

        private fun convertNewUzbekAlphabet(s: String): String {
        return s
            // 1. Barcha apostrof variantlarini U+02BB ga almashtirish
            .replace("\u0027", "\u02BB")  // ASCII ' -> ʻ
            .replace("\u02BC", "\u02BB")  // ʼ -> ʻ
            .replace("\u2018", "\u02BB")  // ' -> ʻ
            .replace("\u2019", "\u02BB")  // ' -> ʻ
            // 2. Yangi o'zbek alifbosi -> U+02BB bilan
            .replace("Ö", "O\u02BB").replace("ö", "o\u02BB")
            .replace("Ğ", "G\u02BB").replace("ğ", "g\u02BB")
            .replace("Ş", "Sh").replace("ş", "sh")
            .replace("Ç", "Ch").replace("ç", "ch")
    }
    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
