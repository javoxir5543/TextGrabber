package com.textgrabber.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

object OcrEngine {
    data class Result(val text: String, val words: List<TextSelectView.W>)
    data class Raw(val text: String, val rect: Rect, val conf: Float)

    private const val TAG = "OcrEngine"

    private var api: TessBaseAPI? = null

    /** Expected byte length per validated asset, so the hot path never re-reads ~4 MB. */
    private val installedLengths = HashMap<String, Long>()

    /**
     * Installs the tessdata files into filesDir so Tesseract (which needs real files) can
     * read them.
     *
     * IMPORTANT: this must NEVER throw. A failure here used to propagate out of [raw] and
     * silently disable ALL Tesseract OCR (only the ML Kit pass would still return text).
     *
     * Note on length detection: `AssetManager.openFd()` throws FileNotFoundException for
     * COMPRESSED (deflated) assets -- "This file can not be opened as a file descriptor;
     * it is probably compressed". AGP deflates .traineddata by default (there is no
     * noCompress rule for it), so openFd() is NOT usable here. We therefore measure the
     * real length by fully reading the asset once, which works for both stored and
     * deflated entries.
     */
    /**
     * Installs every tessdata asset. Returns true only if ALL assets ended up usable, so the
     * caller can tell "Tesseract is ready" from "Tesseract will silently return nothing".
     */
    private fun copyData(ctx: Context): Boolean {
        return try {
            val dir = File(ctx.filesDir, "tessdata")
            if (!dir.exists() && !dir.mkdirs()) {
                android.util.Log.w(TAG, "cannot create tessdata dir: $dir")
                return false
            }

            val assets = ctx.assets.list("tessdata")?.filter { it.isNotEmpty() } ?: return false
            if (assets.isEmpty()) {
                android.util.Log.w(TAG, "no tessdata assets bundled")
                return false
            }

            var allOk = true
            for (name in assets) {
                try {
                    if (!installAsset(ctx, dir, name)) {
                        allOk = false
                        android.util.Log.w(TAG, "tessdata asset not installed: $name")
                    }
                } catch (e: Throwable) {
                    // One bad asset must not abort the others.
                    allOk = false
                    e.printStackTrace()
                }
            }
            allOk
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    /** Copies one asset atomically (tmp + rename). Returns true if a usable file exists. */
    private fun installAsset(ctx: Context, dir: File, name: String): Boolean {
        val out = File(dir, name)
        val tmp = File(dir, "$name.tmp")

        // Clean up a stale/partial temp from an earlier interrupted run first.
        if (tmp.exists()) tmp.delete()

        // Fast path WITHOUT reading the asset: if a complete copy is already installed we are
        // done. The expected length must come from the asset, but a valid file is recognised by
        // re-reading only when the cheap size test is inconclusive. To avoid reading ~4 MB on
        // every OCR call we cache the known-good lengths per process.
        val known = installedLengths[name]
        if (known != null) {
            if (out.exists() && out.length() == known) return true
        }

        // Read the asset once; this both gives us the authoritative length and avoids
        // openFd() (which cannot handle deflated assets).
        val payload = ctx.assets.open("tessdata/$name").use { it.readBytes() }
        val expectedLen = payload.size.toLong()
        if (expectedLen <= 0L) return false
        installedLengths[name] = expectedLen

        // Already installed and complete -> nothing to do.
        if (out.exists() && out.length() == expectedLen) return true

        try {
            tmp.outputStream().use { it.write(payload) }
            if (tmp.length() != expectedLen) {
                tmp.delete()
                return false
            }
            if (out.exists() && !out.delete()) { /* fall through to rename attempt */ }
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            tmp.delete()
            return out.exists() && out.length() == expectedLen
        }
        return out.exists() && out.length() == expectedLen
    }

    @Synchronized
    fun raw(ctx: Context, bmp: Bitmap): List<Raw> {
        var a = api
        if (a == null) {
            // If tessdata cannot be installed, Tesseract would return nothing for every call.
            // Say so once, loudly, instead of failing silently (the original CRITICAL bug's
            // failure shape). ML Kit can still supply text.
            val installed = copyData(ctx)
            if (!installed) {
                android.util.Log.e(TAG, "tessdata not fully installed; Tesseract OCR disabled")
            }
            val fresh = TessBaseAPI()
            if (!fresh.init(ctx.filesDir.absolutePath, "rus+eng")) {
                // Do not leak the native engine we just built.
                try { fresh.recycle() } catch (e: Throwable) { e.printStackTrace() }
                android.util.Log.e(TAG, "TessBaseAPI.init failed; Tesseract OCR disabled")
                return emptyList()
            }
            fresh.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            api = fresh
            a = fresh
        }
        a.setImage(bmp)
        // Required by the API: recognition runs here so that getResultIterator() is
        // populated. The returned String is intentionally discarded (bug #16, documented).
        a.getUTF8Text()
        val iter = a.getResultIterator() ?: return emptyList()
        val out = mutableListOf<Raw>()
        iter.begin()
        do {
            val word = iter.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim() ?: ""
            if (word.isNotEmpty()) {
                out.add(Raw(word, iter.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD),
                    iter.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)))
            }
        } while (iter.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        iter.delete()
        return out
    }

    /**
     * Releases the native Tesseract engine (bug #18). Safe to call more than once.
     * A later [raw] call will re-initialise it lazily.
     * tesseract4android 4.7.0 exposes [TessBaseAPI.recycle] (the old end() is gone).
     * NOTE: FloatingService currently never touches OcrEngine; it could call this from
     * onDestroy() -- reported to the Lead, not edited here (out of scope).
     */
    @Synchronized
    fun recycle() {
        val a = api ?: return
        api = null
        try {
            a.recycle()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
