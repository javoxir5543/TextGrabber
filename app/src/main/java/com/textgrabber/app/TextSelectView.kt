package com.textgrabber.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import com.github.chrisbanes.photoview.PhotoView

class TextSelectView @JvmOverloads constructor(
    c: Context, a: AttributeSet? = null
) : View(c, a) {

    data class W(val text: String, val box: RectF, val line: Int)

    /** Above this word count the outline pass is limited to the visible viewport. */
    private companion object {
        const val OUTLINE_VIEWPORT_LIMIT = 400
    }

    var selectMode = false
        set(v) { field = v; if (!v) sel.clear(); invalidate() }

    private var words = listOf<W>()
    private var pv: PhotoView? = null
    private val sel = mutableSetOf<Int>()
    private var lastX = 0f
    private var lastY = 0f
    private val fill = Paint().apply { color = 0x663399FF }
    private val outline = Paint().apply {
        style = Paint.Style.STROKE; color = 0x88FFFFFF.toInt(); strokeWidth = 1.5f
    }

    // --- per-gesture caches (bug #11) -------------------------------------------------
    private val inv = Matrix()            // inverted display matrix, cached per gesture
    private var invValid = false
    private val scratch = RectF()         // reused scratch rect, no per-word allocation

    // Reused in onDraw to avoid allocating per frame.
    private val viewRect = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    fun setWords(list: List<W>, photoView: PhotoView) {
        pv = photoView; words = list; sel.clear()
        photoView.setOnMatrixChangeListener {
            invValid = false
            invalidate()
        }
        invValid = false
        invalidate()
    }

    /** MainActivity-owned: clears stale highlights when the image is cleared (bug #6). */
    fun clearWords() {
        words = emptyList()
        sel.clear()
        invValid = false
        invalidate()
    }

    /** Applies the user's "Matn rangi / Text color" preference (bug #2). */
    fun setHighlightColor(color: Int) {
        // Keep translucency so the underlying text stays readable.
        fill.color = (color and 0x00FFFFFF) or 0x66000000
        invalidate()
    }

    private fun matrix(): Matrix {
        val m = Matrix()
        pv?.getDisplayMatrix(m)
        return m
    }

    /** Cached inverse display matrix; recomputed only once per gesture. */
    private fun inverseMatrix(): Matrix {
        if (!invValid) {
            invValid = matrix().invert(inv)
        }
        return inv
    }

    override fun onDraw(cv: Canvas) {
        if (!selectMode) return
        if (pv == null) return                  // bug #13: never draw raw coords with no image
        if (words.isEmpty()) return
        val m = matrix()
        cv.save()
        cv.concat(m)

        // bug #12: outlines are a selection aid; on dense pages only outline what is visible.
        val visibleOnly = sel.isNotEmpty() && words.size > OUTLINE_VIEWPORT_LIMIT
        if (visibleOnly) {
            viewRect.set(0f, 0f, width.toFloat(), height.toFloat())
            val invM = Matrix()
            if (m.invert(invM)) invM.mapRect(viewRect)
        }
        for (w in words) {
            if (visibleOnly && !RectF.intersects(w.box, viewRect)) continue
            cv.drawRect(w.box, outline)
        }

        for (i in sel) cv.drawRect(words[i].box, fill)
        cv.restore()
    }

    private fun hit(x: Float, y: Float): Int {
        val p = floatArrayOf(x, y)
        inverseMatrix().mapPoints(p)
        for (i in words.indices) {
            scratch.set(words[i].box)
            scratch.inset(-10f, -10f)
            if (scratch.contains(p[0], p[1])) return i
        }
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!selectMode) return false
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                sel.clear()
                lastX = e.x; lastY = e.y
                invValid = false          // fresh gesture -> fresh matrix
                addHitAt(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX; val dy = e.y - lastY
                val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val steps = maxOf(1, (dist / 6f).toInt())
                for (st in 1..steps) {
                    val t = st.toFloat() / steps
                    addHitAt(lastX + dx * t, lastY + dy * t)
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                // copySelected() olib tashlandi — foydalanuvchi "Nusxalash" tugmasini o'zi bosadi
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        // Touch handling only runs while selectMode is on; this keeps the a11y contract.
        return super.performClick()
    }

    private fun addHitAt(x: Float, y: Float) {
        val i = hit(x, y)
        if (i >= 0) sel.add(i)
    }

    private fun copySelected() {
        if (sel.isEmpty()) return
        val sb = StringBuilder()
        var prev = -1
        for (k in sel.sorted()) {
            val w = words[k]
            if (prev != -1) sb.append(if (w.line != prev) "\n" else " ")
            sb.append(w.text); prev = w.line
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ocr", sb.toString()))
        Toast.makeText(context, context.getString(R.string.toast_selected_copied, sb.toString().take(40)), Toast.LENGTH_SHORT).show()
    }

    fun getSelectedStartIndex(): Int = sel.minOrNull() ?: -1

    fun getTextFrom(index: Int): String {
        if (index < 0 || index >= words.size) return ""
        return words.drop(index).joinToString(" ") { it.text }
    }

    fun getSelectionText(): String {
        if (sel.isEmpty()) return ""
        return sel.sorted().joinToString(" ") { words[it].text }
    }

    /** Faqatgina tanlangan (belgilangan) so'zlarni qaytaradi */
    fun getSelectedTextOnly(): String {
        if (sel.isEmpty()) return ""
        val sb = StringBuilder()
        // Tanlangan so'z indekslarini tartiblab, ularni birlashtiramiz
        val sortedIndices = sel.sorted()
        for (i in sortedIndices) {
            if (i in words.indices) {
                sb.append(words[i].text).append(" ")
            }
        }
        return sb.toString().trim()
    }

}
