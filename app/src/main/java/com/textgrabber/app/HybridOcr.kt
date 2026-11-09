package com.textgrabber.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

object HybridOcr {
    
    data class Result(val text: String, val words: List<TextSelectView.W>)

    fun createRecognizer(): TextRecognizer {
        return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun recognize(ctx: Context, bmp: Bitmap, recognizer: TextRecognizer?): Result {
        try {
            val rec = recognizer ?: createRecognizer()
            val image = InputImage.fromBitmap(bmp, 0)
            
            Log.d("OCR-DIAG", "ML Kit enter: rec=${recognizer != null}")
            
            // Tasks.await ni ishlatish uchun play-services-tasks kerak, u ML Kit bilan birga keladi
            val mlKitText = Tasks.await(rec.process(image), 10, TimeUnit.SECONDS)
            
            val wordsList = mutableListOf<TextSelectView.W>()
            val sb = StringBuilder()
            
            for (block in mlKitText.textBlocks) {
                for (line in block.lines) {
                    val lineIndex = line.boundingBox?.top ?: 0
                    for (element in line.elements) {
                        val text = element.text
                        val box = element.boundingBox
                        if (box != null && text.isNotBlank()) {
                            wordsList.add(
                                TextSelectView.W(
                                    text = text,
                                    box = RectF(box),
                                    line = lineIndex
                                )
                            )
                        }
                    }
                }
            }
            
            // Matnni birlashtirish (har bir qatorni yangi qatordan boshlash)
            var lastLine = -1
            for (w in wordsList) {
                if (lastLine != -1 && w.line != lastLine) {
                    sb.append("\n")
                } else if (sb.isNotEmpty()) {
                    sb.append(" ")
                }
                sb.append(w.text)
                lastLine = w.line
            }
            
            return Result(sb.toString().trim(), wordsList)
        } catch (e: Throwable) {
            Log.e("OCR-DIAG", "ML Kit failed", e)
            return Result("", emptyList())
        }
    }
}
