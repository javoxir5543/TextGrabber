package com.textgrabber.app

import android.content.Context
import android.graphics.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object HybridOcr {
    private val cyr = Regex("[\\u0400-\\u04FF]")
    private val lat = Regex("[A-Za-z]")
    private val toCyr = mapOf(
        'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н', 'K' to 'К', 'M' to 'М',
        'O' to 'О', 'P' to 'Р', 'T' to 'Т', 'X' to 'Х',
        'a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'x' to 'х', 'y' to 'у',
        'l' to 'л', 'n' to 'п', 'b' to 'ь', 'i' to 'и', 'u' to 'и', 'k' to 'к', 'm' to 'м',
        't' to 'т', 'r' to 'г', 's' to 'с', 'v' to 'в', 'w' to 'в', 'd' to 'д', 'f' to 'ф',
        'g' to 'г', 'j' to 'й', 'q' to 'й', 'z' to 'з')
    private val toLat = toCyr.entries.associate { it.value to it.key }

    /**
     * Latin letters whose shape has NO Cyrillic counterpart (Cyrillic has no q/w/j/v/f-style
     * glyphs). Finding one is strong evidence the word really is Latin -- this is what protects
     * real English tokens such as "jQuery", "Vue", "V2Ray" from being "repaired" into Cyrillic.
     */
    private val LATIN_ONLY = "qQwWfFjJvV".toSet()

    /**
     * Cyrillic letters that have NO Latin look-alike in [toCyr]. A homeoglyph-only rewrite
     * (e.g. "balcony" -> "ва1сопу"-style) proves nothing, so it is rejected; the presence of
     * one of these characters means the OCR genuinely saw Cyrillic, not a Latin word.
     *
     * Excluded on purpose: ё is Latin 'e' confusion, і/ј are Latin 'i'/'j' look-alikes,
     * and ҳ/қ/ғ/ў are Uzbek-specific letters that a Latin screen never renders.
     */
    private val CYR_NO_HOMOGLYPH = (
        "БГДЖЗИЙЛПУФЦЧШЩЪЫЬЭЮЯ" +
        "бгджзийлпуфцчшщъыьэюя"
        ).toSet()

    private class Cand(val text: String, val rect: RectF, val conf: Float)

    /** Fixed score for ML Kit words: it exposes no confidence, but is the better reader here. */
    private const val MLKIT_CONF = 100f

    /** Splits an ML Kit element into its individual words (see the merge loop). */
    private val WHITESPACE = Regex("\\s+")

    /**
     * Picks the better reading for one on-screen word.
     *
     * ML Kit is the default authority (it reads stylized UI fonts far better than Tesseract's
     * rus+eng model). Tesseract is only preferred when ML Kit's result is plainly unusable:
     *   - empty / punctuation-only, or
     *   - a single character, while Tesseract produced a longer word, or
     *   - the ORIGINAL ML Kit string is script-mixed garbage that clean() could not resolve
     *     while Tesseract's reading is script-pure.
     * Everything else keeps ML Kit.
     */
    private fun pickBetter(tessText: String, mlText: String): String {
        if (mlText.isBlank()) return tessText
        val mlLetters = mlText.count { it.isLetter() }
        if (mlLetters == 0) return tessText
        val tessLetters = tessText.count { it.isLetter() }
        // A pure number/date is genuine content -- ML Kit wins.
        if (mlText.all { it.isDigit() || !it.isLetterOrDigit() }) return mlText
        // ML Kit fragmented the word into a single character while Tesseract saw a real word.
        if (mlLetters == 1 && tessLetters >= 3) return tessText
        // Tesseract produced a longer, script-pure Cyrillic word where ML Kit returned
        // something shorter and script-mixed: Tesseract is the better reading.
        val mlMixed = cyr.containsMatchIn(mlText) && lat.containsMatchIn(mlText)
        val tessMixed = cyr.containsMatchIn(tessText) && lat.containsMatchIn(tessText)
        if (mlMixed && !tessMixed && tessLetters > mlLetters) return tessText
        // Default: ML Kit is the authority for stylized UI text.
        return mlText
    }

    /**
     * Recognizer models are ~1.4 MB of TFLite weights; creating one per screenshot is slow
     * and leaks native resources. The caller owns a single client and closes it on destroy.
     * Creating it lazily here (instead of in MainActivity.onCreate) keeps app start-up cheap.
     */
    fun createRecognizer(): TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Normalises one raw Tesseract word.
     *
     * This is a pure function of its input, deliberately taking no page-level state. HybridOcr
     * is a singleton and MainActivity can have two OCR threads alive at once (onNewIntent
     * spawns a new one per capture), so object-level state here was a data race: one thread
     * resetting a counter could silently zero another thread's view of the page.
     */
    private fun clean(raw: String): String? {
        var t = raw.trim().trim { !it.isLetterOrDigit() }
        if (t.isEmpty()) return null
        var nc = cyr.findAll(t).count()
        val nl = lat.findAll(t).count()

        if (nc > 0 && nl > 0) {
            // MIXED-SCRIPT WORD: decide the intended script from STRONG evidence.
            //
            // Homoglyph counts alone are misleading: Tesseract's noise output for an English
            // word is mostly Cyrillic lookalikes (it reads "GitHub" as "GитНиь", where
            // и,т,Н,ь are Cyrillic), so a naive Cyrillic-majority vote "repairs" the garbage
            // into confident-looking Cyrillic and hides the fact that the reading is wrong.
            // Genuine Cyrillic is instead evidenced by Cyrillic letters that have NO Latin
            // lookalike (Б,Ж,Ц,Ш,Щ,Ю,Я,...), and genuine Latin by Latin-only letters (q,w,j,v,f).
            val cyrStrong = t.count { it in CYR_NO_HOMOGLYPH }
            val latStrong = t.count { it in LATIN_ONLY }
            when {
                latStrong > cyrStrong -> {
                    // Latin-only letters dominate -> it is a Latin word with stray Cyrillic
                    // lookalikes (very common for stylized/condensed fonts).
                    t = t.map { toLat[it] ?: it }.joinToString("")
                }
                cyrStrong > 0 -> {
                    // Real Cyrillic letters present -> repair the stray Latin lookalikes.
                    t = t.map { toCyr[it] ?: it }.joinToString("")
                }
                else -> {
                    // No strong evidence either way: the word is made of nothing but
                    // homoglyphs, so we CANNOT tell the script. Re-writing it would fabricate
                    // a plausible-looking word from noise, which then survives the ML Kit
                    // merge as a "confident" candidate. Leave it untouched so the raw, ugly
                    // reading stays visible and evidently-low-quality to the caller/merge.
                    // (clean() must never invent certainty it does not have.)
                }
            }
            nc = cyr.findAll(t).count()
        } else if (nc == 0 && nl > 0) {
            // PURE LATIN WORD: do NOT transliterate it into Cyrillic.
            //
            // An earlier version of this file rewrote a pure-Latin word to Cyrillic when the
            // page looked Cyrillic. That was measured to be catastrophic: 90 of 152 common
            // tech/UI words were corrupted, because almost EVERY Latin word maps onto at least
            // one Cyrillic letter that has no Latin lookalike (l->л, n->п, m->м, i->и, d->д,
            // g->г, r->г, z->з), so the "it converted into real Cyrillic" test never rejected
            // anything. Real identifiers were destroyed:
            //     GitHub -> GитНиь, Python -> Рутhоп, Docker -> Dоскег,
            //     Kubernetes -> Киьегпетес, Telegram -> Телеггам, Android -> Апдгоид
            // and the damage was user-visible whenever ML Kit did not also see the word
            // (recognizer timeout, or a word ML Kit missed).
            //
            // There is no reliable way to tell a Latin word that was misread from a real Latin
            // word using character shapes alone -- the information simply is not in the string.
            // Latin is therefore left exactly as Tesseract produced it, and correcting a
            // misread word is left to ML Kit, which actually reads the pixels. This is strictly
            // better than guessing: a Latin identifier is preserved, and a genuinely misread
            // Cyrillic word still gets fixed by the ML Kit merge when ML Kit sees it.
        }

        // Digits -> Cyrillic letters (5 -> Б, 0 -> О, 3 -> З, 4 -> Ч), for Tesseract's habit of
        // reading a bold Cyrillic letter as the digit that shares its shape.
        //
        // This must be CONSERVATIVE. A first attempt relaxed the rule to "a letter on at least
        // one side", which silently rewrote genuine digits in real Russian tokens and labels:
        //     ПАО5 -> ПАОБ, ГТО5 -> ГТОБ, МЧС5 -> МЧСБ, ЕГЭ5 -> ЕГЭБ, ГИБДД0 -> ГИБДДО,
        //     ЖКХ5 -> ЖКХБ, Раздел3 -> РазделЗ, Класс4 -> КлассЧ, Корпус0 -> КорпусО,
        //     Модель5 -> МодельБ, А4 -> АЧ
        // A trailing number after a word ("Раздел 3", "Класс 4", "оценка 5") is extremely
        // common, so a one-sided rule is far too aggressive.
        //
        // Requirements kept (ALL must hold):
        //   - the digit is strictly BETWEEN two LETTERS (so "Модель5", "ПАО5", "А4" are safe),
        //   - BOTH neighbours are Cyrillic (so "Б5C" with a Latin C is not rewritten),
        //   - the token contains no other digit (so "5Б5" does not cascade).
        // A word that is genuinely nothing but a misread letter pair ("БС" -> "5С") is left for
        // the ML Kit merge, which reads the glyphs properly.
        if (nc > 0) {
            val digitsInToken = t.count { it.isDigit() }
            val sb = StringBuilder(t.length)
            for (i in t.indices) {
                val ch = t[i]
                val dig = when (ch) {
                    '5' -> 'Б'; '0' -> 'О'; '3' -> 'З'; '4' -> 'Ч'
                    else -> null
                }
                val leftCyr = i > 0 && cyr.matches(t[i - 1].toString())
                val rightCyr = i < t.lastIndex && cyr.matches(t[i + 1].toString())
                if (dig != null && digitsInToken == 1 && leftCyr && rightCyr) sb.append(dig)
                else sb.append(ch)
            }
            t = sb.toString()
        }
        return t
    }

    /**
     * Decides whether two word boxes refer to the SAME on-screen word.
     *
     * The old rule was `intersect / min(area) > 0.5`, which FAILS exactly where we need it:
     * Tesseract and ML Kit size their boxes very differently for stylized UI text (Tesseract
     * tends to return a much taller/wider box), so the intersection covers well under half of
     * the smaller box even though both boxes clearly describe the same word. When the pairing
     * is rejected, the two candidates stay SEPARATE and the mangled Tesseract reading is shown
     * instead of ML Kit's correct one.
     *
     * This uses several complementary tests so a genuine match survives box disagreement:
     *  1. Intersection over Union (IoU)        -- boxes broadly agree.
     *  2. Intersection over smaller area       -- one box contains the other.
     *  3. Centre containment                   -- each centre lies in the other's box
     *                                             (handles large size mismatches).
     *  4. Strong vertical + horizontal overlap -- same line, same glyph run.
     */
    /**
     * Minimal normalisation for ML Kit output.
     *
     * ML Kit reads stylized UI fonts correctly, so its script decision is trusted as-is. Only
     * obviously-safe fixes are applied: trimming, dropping pure punctuation, and the
     * unambiguous bold-font digit repairs (5->Б, 0->О) for words that ML Kit already read as
     * Cyrillic. We deliberately do NOT run the transliteration/script-guessing used for
     * Tesseract, because that is what corrupted "GitHub" -> "GитНиь" and "БС" -> "5С".
     */
    private fun cleanMlKit(raw: String): String? {
        var t = raw.trim()
        if (t.isEmpty()) return null
        // Punctuation-only tokens are noise.
        if (t.none { it.isLetterOrDigit() }) return null

        val hasCyr = cyr.containsMatchIn(t)
        val hasLat = lat.containsMatchIn(t)

        // ML Kit occasionally returns a MIXED-script word for a single-script one
        // ("Прuвет", "Gооgle"). Repair it using the same strong-evidence rule as clean():
        // settle on the script that has unambiguous letters (Cyrillic letters with no Latin
        // lookalike vs Latin-only letters). If neither side has unambiguous evidence the word
        // is left alone rather than guessed at.
        if (hasCyr && hasLat) {
            val cyrStrong = t.count { it in CYR_NO_HOMOGLYPH }
            val latStrong = t.count { it in LATIN_ONLY }
            // A single stray lookalike letter is an OCR glitch ("Прuвет", "Покупкi"), but a RUN
            // of >= 2 letters of one script is a real word embedded in the other: the ".ru"/".com"
            // TLD, "@mail", or a brand name ("Мegafon"). Re-writing such a run destroys genuine
            // content, so the presence of a multi-letter run of either script means we must not
            // mass-convert the token. Measured damage from a version that ignored this:
            //     "Дом.ru" -> "Дом.ги", "Иван@mail.ru" -> "Иван@маил.ги"
            //     "Откройте Telegram" -> "Откройте Телеггам"
            // A run of >= 2 letters of one script inside the other is treated as a real embedded
            // word (".ru", "@mail", "egafon") and is never rewritten.
            //
            // DELIBERATE ASYMMETRY: this means an OCR glitch that misreads TWO OR MORE adjacent
            // Cyrillic glyphs as Latin ("Teкст", "Coобщения") is left unrepaired, while a
            // single-glyph glitch ("Прuвет", "Покупкi") is fixed. That is the intended
            // trade-off, not an oversight: two adjacent Cyrillic glyphs both reading as Latin is
            // rare, whereas real embedded Latin words (domains, brands, "@mail") are extremely
            // common -- and rewriting those is exactly what destroyed "Дом.ru" -> "Дом.ги".
            // Unrepairable-by-heuristic words are still corrected by ML Kit itself, which reads
            // the glyphs directly and is the authority for this whole pass.
            val latinRun = longestRun(t) { lat.matches(it.toString()) }
            val cyrRun = longestRun(t) { cyr.matches(it.toString()) }
            // A run of >= 2 letters of one script inside the other is a real embedded word
            // (".ru", "@mail", "egafon"), not an OCR glitch. This check comes FIRST: the
            // evidence-based arms below would otherwise rewrite the whole token and destroy it,
            // because a Cyrillic word carrying a short Latin suffix is always Cyrillic-strong.
            val hasLatinWord = latinRun >= 2
            val hasCyrWord = cyrRun >= 2
            when {
                hasLatinWord && !hasCyrWord -> t = t.map { toLat[it] ?: it }.joinToString("")
                hasCyrWord && !hasLatinWord -> t = t.map { toCyr[it] ?: it }.joinToString("")
                hasLatinWord && hasCyrWord -> {
                    // Both scripts carry a real multi-letter word: this is a legitimate mixed
                    // token (e.g. a Russian word next to a Latin brand). Do not touch it.
                }
                // Neither script has a multi-letter run: the token is a single word with an
                // isolated misread glyph ("Прuвет", "Покупкi", "Gооgle").
                latStrong > cyrStrong -> t = t.map { toLat[it] ?: it }.joinToString("")
                cyrStrong > latStrong && cyrStrong > 0 -> t = t.map { toCyr[it] ?: it }.joinToString("")
                else -> {
                    // Isolated lookalikes only, no strong evidence either way. Prefer the
                    // script that dominates, since every letter here is shape-ambiguous and the
                    // majority is the better guess for a single misread glyph.
                    t = if (cyr.findAll(t).count() > lat.findAll(t).count()) {
                        t.map { toCyr[it] ?: it }.joinToString("")
                    } else {
                        t.map { toLat[it] ?: it }.joinToString("")
                    }
                }
            }
        }

        // Glyph repairs only inside a word that is now Cyrillic-only. ML Kit reads glyphs
        // directly, so it needs far less correction than Tesseract; we repair only the two
        // bold-font confusions that actually matter (5 -> Б, 0 -> О) and only under the same
        // conservative conditions as clean(): strictly between two Cyrillic letters, with no
        // other digit in the token. A one-sided rule was measured to corrupt real tokens
        // ("ПАО5" -> "ПАОБ", "ГИБДД0" -> "ГИБДДО").
        if (cyr.containsMatchIn(t) && !lat.containsMatchIn(t)) {
            val digitsInToken = t.count { it.isDigit() }
            val sb = StringBuilder(t.length)
            for (i in t.indices) {
                val ch = t[i]
                val dig = when (ch) { '5' -> 'Б'; '0' -> 'О'; else -> null }
                val leftCyr = i > 0 && cyr.matches(t[i - 1].toString())
                val rightCyr = i < t.lastIndex && cyr.matches(t[i + 1].toString())
                if (dig != null && digitsInToken == 1 && leftCyr && rightCyr) sb.append(dig)
                else sb.append(ch)
            }
            t = sb.toString()
        }
        return t
    }

    /**
     * Length of the longest consecutive run of characters satisfying [pred] within [s].
     *
     * Used to tell an isolated misread glyph ("Прuвет" -- the Latin run is the single 'u')
     * apart from a genuinely embedded word in the other script ("Дом.ru" -- the Latin run is
     * "ru", "@mail" is a 4-letter run, "Мegafon" is a 6-letter run).
     */
    private inline fun longestRun(s: String, pred: (Char) -> Boolean): Int {
        var best = 0
        var cur = 0
        for (c in s) {
            if (pred(c)) { cur++; if (cur > best) best = cur } else cur = 0
        }
        return best
    }

    private fun overlap(a: RectF, b: RectF): Boolean {
        val i = RectF()
        val intersects = i.setIntersect(a, b)
        val minArea = minOf(a.width() * a.height(), b.width() * b.height())
        val inter = if (intersects) i.width() * i.height() else 0f
        if (inter > 0f && minArea > 0f) {
            // (1) IoU and (2) containment ratio
            val union = a.width() * a.height() + b.width() * b.height() - inter
            if (union > 0f && inter / union > 0.35f) return true
            if (inter / minArea > 0.5f) return true
        }
        // (3) Centre containment, guarded by real vertical overlap.
        //
        // Without the vertical guard this arm is far too permissive: any large box (e.g. an ML
        // Kit element that spans a whole line) contains the centre of EVERY Tesseract word it
        // covers, so all those words would collapse onto one candidate and be lost. Requiring
        // the two boxes to share most of their vertical extent keeps the arm useful for the case
        // it was added for (same word, disagreeing box sizes) while refusing to merge a big box
        // with words that are merely inside it.
        if (inter > 0f) {
            val vShared = i.height() / minOf(a.height(), b.height())
            if (vShared > 0.5f &&
                (a.contains(b.centerX(), b.centerY()) || b.contains(a.centerX(), a.centerY()))
            ) return true
            // (4) same line and substantial horizontal overlap
            val hOverlap = i.width() / minOf(a.width(), b.width())
            if (vShared > 0.6f && hOverlap > 0.3f) return true
        }
        return false
    }

    /**
     * Target size (px, longest side) of the bitmap handed to Tesseract. Tesseract's accuracy on
     * small text scales with the glyph height in pixels, so we normalise the INPUT rather than
     * relying on a fixed multiplier: the decode side may legally sample down for memory safety
     * without that decision silently starving the recogniser of resolution.
     */
    private const val TESS_TARGET_MAX_SIDE = 2000

    /** Clamped scale so the prepared bitmap lands near [TESS_TARGET_MAX_SIDE] on the long side. */
    private fun scaleFor(src: Bitmap): Float {
        val longSide = maxOf(src.width, src.height)
        if (longSide <= 0) return 1f
        val adaptive = TESS_TARGET_MAX_SIDE.toFloat() / longSide
        // Never downscale below 1x (upscaling helps Tesseract) and never upscale past ~2x,
        // which would only waste memory without adding information.
        return adaptive.coerceIn(1f, 2f)
    }

    private fun prep(src: Bitmap, scale: Float, invert: Boolean): Bitmap {
        val w = (src.width * scale).toInt()
        val h = (src.height * scale).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = 1.6f
        val t = 128f * (1 - c)
        val s = if (invert) -c else c
        val o = if (invert) 255f - t else t
        val cm = ColorMatrix(floatArrayOf(
            0.299f * s, 0.587f * s, 0.114f * s, 0f, o,
            0.299f * s, 0.587f * s, 0.114f * s, 0f, o,
            0.299f * s, 0.587f * s, 0.114f * s, 0f, o,
            0f, 0f, 0f, 1f, 0f))
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = ColorMatrixColorFilter(cm)
        Canvas(out).drawBitmap(src, null, Rect(0, 0, w, h), p)
        return out
    }

    /**
     * @param bmp the already-decoded screenshot (MainActivity samples it for memory safety);
     *            the internal scale is derived so Tesseract still receives ~
     *            [TESS_TARGET_MAX_SIDE] px on the long side.
     * @param recognizer caller-owned single ML Kit client; never created or closed here.
     */
    fun recognize(ctx: Context, bmp: Bitmap, recognizer: TextRecognizer?): OcrEngine.Result {
        val scale = scaleFor(bmp)
        val cands = mutableListOf<Cand>()

        // Pass 1: normal contrast. A second inverted pass genuinely helps white-on-dark UIs,
        // so it always runs -- but its bitmap is allocated and recycled one at a time, so
        // only one ~5.8 MB ARGB_8888 copy is live alongside the source bitmap.
        for (inv in listOf(false, true)) {
            val p = try {
                prep(bmp, scale, inv)
            } catch (e: OutOfMemoryError) {
                android.util.Log.e("OCR-DIAG", "ocr stage failed", e)
                // The inverted pass is optional; the NORMAL pass is not. If the normal pass
                // cannot even be allocated, fall back to the inverted one rather than returning
                // nothing.
                if (!inv) continue else break
            }
            try {
                val raws = OcrEngine.raw(ctx, p)
                // Clean + collect. clean() is per-word; it deliberately does no
                // document-level script guessing (see its pure-Latin branch).
                for (w in raws) {
                    if (w.conf < 55f) continue
                    val t = clean(w.text) ?: continue
                    val r = RectF(w.rect.left / scale, w.rect.top / scale,
                        w.rect.right / scale, w.rect.bottom / scale)
                    val idx = cands.indexOfFirst { overlap(it.rect, r) }
                    if (idx < 0) cands.add(Cand(t, r, w.conf))
                    else if (w.conf > cands[idx].conf) cands[idx] = Cand(t, r, w.conf)
                }
            } catch (e: Throwable) { android.util.Log.e("OCR-DIAG", "ocr stage failed", e) }
            p.recycle()
        }

        // ML Kit pass. Tasks.await blocks this (background) thread for up to 10 s; on timeout
        // the underlying task is not cancelled and keeps running until it finishes on its own
        // executor. That is acceptable here: the result is simply dropped by the catch below.
        //
        // ML Kit is the AUTHORITY here: it reads stylized UI fonts (bold/condensed/all-caps)
        // far more reliably than Tesseract's rus+eng model, which confuses lookalike glyphs
        // ("Инструменты" -> "VНСТруМеНТьл", "Покупки" -> "локупкп", "GitHub" -> "GитНиь").
        // No character-level heuristic in clean() can undo that damage -- the true word is
        // only recoverable from ML Kit. So whenever ML Kit reports a word that covers the same
        // region, ML Kit's reading WINS, except where Tesseract is clearly better.
        try {
            val rec = recognizer
            android.util.Log.i("OCR-DIAG", "ML Kit enter: rec=${rec != null}")
            if (rec != null) {
                val task = rec.process(InputImage.fromBitmap(bmp, 0))
                val t = try {
                    Tasks.await(task, 10, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Throwable) {
                    android.util.Log.e("OCR-DIAG", "ML Kit await FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    throw e
                }
                for (b in t.textBlocks) for (l in b.lines) for (e in l.elements) {
                    val bb = e.boundingBox ?: continue
                    val rawMl = e.text.trim()
                    // ML Kit's reading is already the trustworthy one, so it gets only the
                    // MINIMAL normalisation (trim + digit fixes), never the destructive
                    // script-guessing that Tesseract's noisy output needs. Running full
                    // clean() here previously mangled correct ML Kit text: it rewrote
                    // "GitHub" into "GитНиь" and "КИБЕРПОРТАЛ БС" into "КИБЕРПОРТАЛ 5С".
                    val txt = cleanMlKit(rawMl) ?: continue
                    val r = RectF(bb)
                    // If ML Kit returned a multi-token element (it does for CJK and some
                    // joined/stylized text) its box spans several words, so merging on the whole
                    // box would collapse -- and delete -- every Tesseract word underneath it.
                    // Splitting the element into its words and merging each on its own share of
                    // the box keeps every word: no loss, and no duplicated text either.
                    val tokens = rawMl.split(WHITESPACE).filter { it.isNotBlank() }
                    if (tokens.size > 1) {
                        val piece = r.width() / tokens.size
                        for (ti in tokens.indices) {
                            val sub = cleanMlKit(tokens[ti]) ?: continue
                            val sr = RectF(r.left + piece * ti, r.top, r.left + piece * (ti + 1), r.bottom)
                            val si = cands.indexOfFirst { overlap(it.rect, sr) }
                            if (si < 0) cands.add(Cand(sub, sr, MLKIT_CONF))
                            else {
                                val tess = cands[si]
                                val tessHasCyr = cyr.containsMatchIn(tess.text)
                                val mlHasCyr = cyr.containsMatchIn(sub)
                                val chosen = when {
                                    tessHasCyr && !mlHasCyr -> tess.text
                                    mlHasCyr && !tessHasCyr -> sub
                                    else -> pickBetter(tess.text, sub)
                                }
                                cands[si] = Cand(chosen, sr, if (chosen == sub) MLKIT_CONF else tess.conf)
                            }
                        }
                        continue
                    }
                    val idx = cands.indexOfFirst { overlap(it.rect, r) }
                    if (idx < 0) {
                        // No Tesseract counterpart: take ML Kit's word outright.
                        cands.add(Cand(txt, r, MLKIT_CONF))
                    } else {
                        val tess = cands[idx]
                        // ML Kit is the AUTHORITY. If ML Kit gives a script-pure word
                        // (all Cyrillic OR all Latin) and Tesseract gives mixed-script
                        // garbage (e.g. "VHCTpyMeHTbl"), ML Kit wins unconditionally.
                        val tessHasCyr = cyr.containsMatchIn(tess.text)
                        val mlHasCyr = cyr.containsMatchIn(txt)
                        val chosen = when {
                            // Tesseract topdi kirill, ML Kit topmadi -> Tesseract haq
                            tessHasCyr && !mlHasCyr -> tess.text
                            // ML Kit topdi kirill, Tesseract topmadi -> ML Kit haq
                            mlHasCyr && !tessHasCyr -> txt
                            // Ikkalasi bir xil tilda -> pickBetter hal qiladi
                            else -> pickBetter(tess.text, txt)
                        }
                        cands[idx] = Cand(chosen, r, if (chosen == txt) MLKIT_CONF else tess.conf)
                    }
                }
            }
        } catch (e: Throwable) { android.util.Log.e("OCR-DIAG", "ocr stage failed", e) }


        val sorted = cands.sortedBy { it.rect.centerY() }
        val lines = mutableListOf<MutableList<Cand>>()
        for (w in sorted) {
            val last = lines.lastOrNull()
            if (last != null &&
                Math.abs(w.rect.centerY() - last.map { it.rect.centerY() }.average()) < w.rect.height() * 0.6
            ) last.add(w) else lines.add(mutableListOf(w))
        }

        val words = mutableListOf<TextSelectView.W>()
        val sb = StringBuilder()
        lines.forEachIndexed { li, line ->
            line.sortBy { it.rect.left }
            line.forEach { words.add(TextSelectView.W(it.text, it.rect, li)) }
            if (li > 0) sb.append("\n")
            sb.append(line.joinToString(" ") { it.text })
        }
        return OcrEngine.Result(sb.toString(), words)
    }
}
