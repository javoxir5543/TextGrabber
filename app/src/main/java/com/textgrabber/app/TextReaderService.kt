package com.textgrabber.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.accessibility.AccessibilityNodeInfo

class TextReaderService : AccessibilityService() {
    companion object { var instance: TextReaderService? = null }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        return extractTextList(root, 0, Int.MAX_VALUE).joinToString(". ")
    }

    /** Tugma turgan joydan pastdagi matnlarni ro'yxat qilib olish */
    fun getScreenTextListFrom(startY: Int): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val screenHeight = resources.displayMetrics.heightPixels
        val maxY = (screenHeight * 0.88).toInt() // Pastki panelni o'qimaymiz
        return extractTextList(root, startY, maxY)
    }

    fun getSelectedText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        extractRealSelection(root, sb)
        return sb.toString().trim()
    }

    private fun extractTextList(node: AccessibilityNodeInfo?, minY: Int, maxY: Int): List<String> {
        if (node == null) return emptyList()
        val result = mutableListOf<String>()
        val visited = HashSet<String>()

        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val texts = listOfNotNull(n.text?.toString()?.trim(), n.contentDescription?.toString()?.trim())

            for (raw in texts) {
                if (raw.isNotEmpty() && shouldInclude(n, raw, n.text == null)) {
                    val rect = Rect()
                    n.getBoundsInScreen(rect)
                    var actualTop = rect.top
                    
                    // Agar WebView koordinatasi 0 bo'lsa, ota-onasinikini olamiz
                    if (actualTop == 0 && rect.bottom == 0) {
                        n.parent?.let { p -> p.getBoundsInScreen(rect); actualTop = rect.top }
                    }

                    if (actualTop >= minY - 50 && actualTop <= maxY && !visited.contains(raw)) {
                        result.add(raw)
                        visited.add(raw)
                    }
                }
            }
            for (i in 0 until n.childCount) traverse(n.getChild(i))
        }
        traverse(node)
        return result
    }

    private fun shouldInclude(node: AccessibilityNodeInfo, text: String, isDesc: Boolean): Boolean {
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText", true)) return false // Qidiruv satrini o'qimaymiz
        if (className.contains("Image", true) || className.contains("Button", true)) {
            if (text.length < 25) return false // Kichik ikonkalar va tugmalarni tashlaymiz
        }
        if (isDesc && node.isClickable && text.length < 25) return false

        val lower = text.lowercase().trim()
        
        // 1. URL, domenlar va yashirin yozuvlarni tashlash
        if (lower.startsWith("http") || lower.contains("://") || text.length <= 3) return false
        if (Regex("""^[a-zA-Z0-9.-]+\.(uz|ru|com|org|net|io|info|gov|edu)$""", RegexOption.IGNORE_CASE).matches(lower)) return false
        if (Regex("""^\+\d{1,2}$""").matches(lower)) return false // "+2", "+15" kabi
        
        // 2. "Kun.uz +2" kabi qo'shilib ketgan matnlarni tashlash
        if (Regex("""^[a-zA-Z0-9.-]+\.(uz|ru|com)\s*\+\d+$""", RegexOption.IGNORE_CASE).matches(lower)) return false
        
        // 3. Reklama va tavsiya kartochkalarini tashlash (masalan: "2026 yil birinchi...")
        if (Regex("""^\d{4}\s""").containsMatchIn(text) && text.length < 100) return false
        
        if (text.count { it.isLetter() } < 2) return false

        // 3. Tugmalar va menyular (kengaytirilgan)
        val uiExact = setOf(
            "back", "forward", "new tab", "tabs", "menu", "ask a question", "share", "copy", "search", "images", "videos", "news", "maps",
            "назад", "вперед", "новая вкладка", "вкладки", "меню", "поделиться", "копировать", "поиск", "картинки", "видео", "новости", "карты",
            "orqaga", "oldinga", "yangi oyna", "oynalar", "menyu", "ulashish", "nusxalash", "qidirish", "rasmlar", "videolar", "yangiliklar", "xaritalar"
        )
        if (lower in uiExact) return false

        // 4. Reklama/Tavsiya bloklarini chetlab o'tish
        var p = node.parent
        var depth = 0
        while (p != null && depth < 3) {
            if (p.isClickable && p.className?.contains("ViewGroup") == true && text.length < 60) {
                return false // Reklama kartasi
            }
            p = p.parent
            depth++
        }

        return true
    }

    private fun extractRealSelection(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        val text = node.text?.toString()
        if (text != null && text.isNotEmpty()) {
            if (node.isEditable) {
                val s = node.textSelectionStart; val e = node.textSelectionEnd
                if (s >= 0 && e > s) sb.append(text.substring(s.coerceIn(0, text.length), e.coerceIn(0, text.length))).append(" ")
            } else if (node.isSelected && text.length > 2) sb.append(text).append(" ")
        }
        for (i in 0 until node.childCount) extractRealSelection(node.getChild(i), sb)
    }

    /** Auto-scroll bilan butun matnni yig'ish (Chrome va uzun sahifalar uchun) */
    fun getAllScreenTextFromYWithScroll(startY: Int): List<String> {
        val allTexts = mutableListOf<String>()
        val visited = HashSet<String>()

        val root0 = rootInActiveWindow ?: return emptyList()
        extractTextListWithRange(root0, allTexts, visited, startY, Int.MAX_VALUE)
        Log.d("TextReaderService", "Initial grab: ${allTexts.size} texts")

        var attempts = 0
        var totalScrolls = 0

        while (attempts < 20) {
            val prevSize = allTexts.size
            val newRoot = rootInActiveWindow ?: break
            
            // 1. Avval Accessibility orqali skroll qilib ko'ramiz
            val scrollable = findScrollableNode(newRoot)
            var scrolled = false
            if (scrollable != null) {
                scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }

            // 2. Agar ishlamasa, Gesture (Swipe)
            if (!scrolled) {
                val metrics = resources.displayMetrics
                val swipePath = Path()
                swipePath.moveTo(metrics.widthPixels / 2f, metrics.heightPixels * 0.8f)
                swipePath.lineTo(metrics.widthPixels / 2f, metrics.heightPixels * 0.2f)
                
                val gestureBuilder = GestureDescription.Builder()
                gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 0, 400))
                
                val latch = CountDownLatch(1)
                dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { latch.countDown() }
                    override fun onCancelled(g: GestureDescription?) { latch.countDown() }
                }, null)
                latch.await(1, TimeUnit.SECONDS)
            }
            totalScrolls++
            Thread.sleep(1000) // 1 soniya — sahifa yangilanishi uchun

            val updatedRoot = rootInActiveWindow
            if (updatedRoot != null) {
                extractTextListWithRange(updatedRoot, allTexts, visited, Int.MIN_VALUE, Int.MAX_VALUE)
            }

            Log.d("TextReaderService", "After scroll $totalScrolls: ${allTexts.size} texts (was $prevSize)")
            
            // BITTA skrolldan keyin yangi matn kelmasa, darhol to'xtaymiz (2 marta kutmimiz)
            if (allTexts.size == prevSize) {
                Log.d("TextReaderService", "No new text after scroll $totalScrolls, breaking.")
                break
            }
            attempts++
        }
        
        // MUHIM: O'qish fon rejimida bo'lgani uchun, foydalanuvchi joyiga qaytamiz
        if (totalScrolls > 0) {
            Log.d("TextReaderService", "Returning to original position ($totalScrolls scrolls back)")
            for (i in 0 until totalScrolls) {
                val curRoot = rootInActiveWindow
                val scrollable = findScrollableNode(curRoot)
                if (scrollable != null) {
                    // Ba'zi ilovalarda ACTION_SCROLL_BACKWARD ishlamaydi, Gesture (Swipe down) yuboramiz
                    val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    if (!scrolled) {
                        val metrics = resources.displayMetrics
                        val swipePath = Path()
                        swipePath.moveTo(metrics.widthPixels / 2f, metrics.heightPixels * 0.2f)
                        swipePath.lineTo(metrics.widthPixels / 2f, metrics.heightPixels * 0.8f)
                        val gestureBuilder = GestureDescription.Builder()
                        gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 0, 300))
                        val latch = CountDownLatch(1)
                        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                            override fun onCompleted(g: GestureDescription?) { latch.countDown() }
                            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
                        }, null)
                        latch.await(1, TimeUnit.SECONDS)
                    }
                }
                Thread.sleep(400) // Har bir qaytishda sahifa yuklanishini kutish
            }
        }
        
        Log.d("TextReaderService", "Auto-scroll done: ${allTexts.size} texts, $totalScrolls scrolls")
        return allTexts
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val r = findScrollableNode(node.getChild(i))
            if (r != null) return r
        }
        return null
    }

    private fun extractTextListWithRange(node: AccessibilityNodeInfo?, out: MutableList<String>, visited: MutableSet<String>, minY: Int, maxY: Int) {
        if (node == null) return
        val texts = listOfNotNull(node.text?.toString()?.trim(), node.contentDescription?.toString()?.trim())
        for (raw in texts) {
            if (raw.isNotEmpty() && shouldInclude(node, raw, node.text == null) && !visited.contains(raw)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                var actualTop = rect.top
                if (actualTop == 0 && rect.bottom == 0) {
                    node.parent?.let { p -> p.getBoundsInScreen(rect); actualTop = rect.top }
                }
                if (actualTop >= minY - 50 && actualTop <= maxY) {
                    out.add(raw)
                    visited.add(raw)
                }
            }
        }
        for (i in 0 until node.childCount) {
            extractTextListWithRange(node.getChild(i), out, visited, minY, maxY)
        }
    }

}
