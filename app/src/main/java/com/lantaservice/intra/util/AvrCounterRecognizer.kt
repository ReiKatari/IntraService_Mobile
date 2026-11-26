package com.lantaservice.intra.util

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max

enum class CounterConfidence {
    HIGH,
    MEDIUM,
    NONE
}

data class CounterParseResult(
    val value: String?,
    val confidence: CounterConfidence,
    val matchedLine: String? = null
)

/**
 * Pure JVM parser extracting the printer/copier page counter written or printed on an AVR form.
 * Handles Russian labels, English labels, and Latin-OCR homoglyphs of Russian labels,
 * while strictly filtering out dates, times, phone numbers, INN/OGRN, postal codes, and act numbers.
 */
object AvrCounterParser {

    private val dateRegex = Regex("\\b\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}\\b|\\b\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}\\b")
    private val timeRegex = Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b")
    private val phoneRegex = Regex("(?:\\+7|8)[\\s(-]*\\d{3}[\\s)-]*\\d{3}[\\s-]*\\d{2}[\\s-]*\\d{2}")
    private val moneyRegex = Regex("\\b\\d+(?:[\\s.,]\\d{3})*[.,]\\d{2}\\b")
    private val docNumberRegex = Regex("(?i)(?:№|no\\.?|#)\\s*\\d{1,8}\\b")
    private val percentRegex = Regex("\\b\\d+\\s*%")
    private val alphaNumSerialRegex = Regex("\\b(?:[A-Za-zА-Яа-я]+\\d+\\w*|\\d+[A-Za-zА-Яа-я]+)\\b")
    private val numberCandidateRegex = Regex("\\b\\d{1,3}(?:[\\s,.']\\d{3})+\\b|\\b\\d{1,8}\\b")

    private val keywordPatterns = listOf(
        "счетчик", "счётчик", "копий", "копии", "копия", "пробег",
        "показани", "напечатан", "отпечат", "всего", "итого", "общий",
        "c4et", "cyet", "chet", "kon", "kop", "npo6", "prob", "pokaz", "nokaz",
        "bceg", "vseg", "utog", "itog", "o6w", "obsh",
        "counter", "total", "copies", "meter", "impressions", "page count", "print count"
    )

    private val excludedLineMarkers = listOf(
        "инн", "кпп", "огрн", "бик", "р/с", "к/с", "окпо",
        "тел", "факс", "phone", "индекс", "ул.", "д.", "обл.",
        "г.", "москва", "санкт", "договор", "заявк", "inn", "ogrn"
    )

    fun formatCounterDisplay(digits: String): String {
        val clean = digits.filter { it.isDigit() }
        if (clean.length <= 3) return clean
        val sb = StringBuilder()
        var count = 0
        for (i in clean.length - 1 downTo 0) {
            sb.append(clean[i])
            count++
            if (count % 3 == 0 && i > 0) {
                sb.append(' ')
            }
        }
        return sb.reverse().toString()
    }

    fun extractCounterFromLines(lines: List<String>): CounterParseResult {
        if (lines.isEmpty()) return CounterParseResult(null, CounterConfidence.NONE)

        // Pass 1: Look for explicit counter label + number on same line or immediately below/next line
        for (idx in lines.indices) {
            val line = lines[idx].trim()
            if (line.isEmpty()) continue
            if (containsKeyword(line)) {
                val sameLineNum = extractPlausibleNumberFromLine(line, preferRightmost = true)
                if (sameLineNum != null) {
                    return CounterParseResult(sameLineNum, CounterConfidence.HIGH, line)
                }
                if (idx + 1 < lines.size) {
                    val nextLine = lines[idx + 1].trim()
                    if (!isExcludedNonCounterLine(nextLine)) {
                        val nextLineNum = extractPlausibleNumberFromLine(nextLine, preferRightmost = false)
                        if (nextLineNum != null) {
                            return CounterParseResult(nextLineNum, CounterConfidence.HIGH, "$line | $nextLine")
                        }
                    }
                }
            }
        }

        // Pass 2: Fallback when label wasn't read clearly, but there is a single unambiguous counter-like integer
        val standalonePool = mutableListOf<Pair<String, String>>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || isExcludedNonCounterLine(trimmed)) continue
            extractPlausibleNumbersInLine(trimmed).forEach { digits ->
                if (digits.length in 2..8) {
                    standalonePool += digits to trimmed
                }
            }
        }
        val distinctValues = standalonePool.map { it.first }.distinct()
        if (distinctValues.size == 1) {
            val hit = standalonePool.first()
            return CounterParseResult(hit.first, CounterConfidence.MEDIUM, hit.second)
        }

        return CounterParseResult(null, CounterConfidence.NONE)
    }

    private fun containsKeyword(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        val skeleton = toHomoglyphSkeleton(lower)
        return keywordPatterns.any { kw ->
            lower.contains(kw) || skeleton.contains(kw)
        }
    }

    private fun toHomoglyphSkeleton(lower: String): String {
        val sb = StringBuilder(lower.length)
        for (i in lower.indices) {
            val ch = lower[i]
            val prevIsLetter = i > 0 && lower[i - 1].isLetter()
            val nextIsLetter = i + 1 < lower.length && lower[i + 1].isLetter()
            val mapped = when (ch) {
                'a' -> 'а'
                'b' -> 'в'
                'c' -> 'с'
                'e' -> 'е'
                'h' -> 'н'
                'k' -> 'к'
                'm' -> 'м'
                'n' -> 'п'
                'o' -> 'о'
                'p' -> 'р'
                'r' -> 'г'
                't' -> 'т'
                'u' -> 'и'
                'w' -> 'ш'
                'x' -> 'х'
                'y' -> 'у'
                '4' -> if (prevIsLetter || nextIsLetter) 'ч' else ch
                '6' -> if (prevIsLetter || nextIsLetter) 'б' else ch
                else -> ch
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    private fun isExcludedNonCounterLine(line: String): Boolean {
        val skeleton = toHomoglyphSkeleton(line.lowercase(Locale.ROOT))
        val lower = line.lowercase(Locale.ROOT)
        return excludedLineMarkers.any { marker ->
            lower.contains(marker) || skeleton.contains(marker)
        }
    }

    private fun extractPlausibleNumberFromLine(line: String, preferRightmost: Boolean): String? {
        val candidates = extractPlausibleNumbersInLine(line)
        if (candidates.isEmpty()) return null
        return if (preferRightmost) candidates.last() else candidates.first()
    }

    private fun extractPlausibleNumbersInLine(rawLine: String): List<String> {
        var cleaned = rawLine
        cleaned = dateRegex.replace(cleaned, " ")
        cleaned = timeRegex.replace(cleaned, " ")
        cleaned = phoneRegex.replace(cleaned, " ")
        cleaned = moneyRegex.replace(cleaned, " ")
        cleaned = docNumberRegex.replace(cleaned, " ")
        cleaned = percentRegex.replace(cleaned, " ")
        cleaned = alphaNumSerialRegex.replace(cleaned, " ")

        val results = mutableListOf<String>()
        numberCandidateRegex.findAll(cleaned).forEach { match ->
            val rawToken = match.value.trim()
            val digits = rawToken.filter { it.isDigit() }
            if (digits.isNotEmpty() && digits.length <= 8) {
                results += digits
            }
        }
        return results
    }
}

/**
 * On-device ML Kit OCR recognizer for AVR photos.
 * Clusters recognized text boxes into horizontal visual rows before parsing the copy counter.
 */
object AvrCounterRecognizer {

    suspend fun recognizeCounterFromFile(
        context: Context,
        imageFile: File
    ): CounterParseResult = withContext(Dispatchers.Default) {
        if (!imageFile.exists()) {
            return@withContext CounterParseResult(null, CounterConfidence.NONE)
        }

        val inputImage = runCatching {
            InputImage.fromFilePath(context, Uri.fromFile(imageFile))
        }.getOrNull() ?: return@withContext CounterParseResult(null, CounterConfidence.NONE)

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val visionText: Text = runCatching {
            suspendCancellableCoroutine { cont ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { result ->
                        if (cont.isActive) cont.resume(result)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resumeWith(Result.failure(it))
                    }
            }
        }.getOrNull() ?: return@withContext CounterParseResult(null, CounterConfidence.NONE)

        val boxedLines = visionText.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: Rect(0, 0, 0, 0)
                val txt = line.text.trim()
                if (txt.isEmpty()) null else Triple(box, txt, box.exactCenterY())
            }

        if (boxedLines.isEmpty()) {
            return@withContext CounterParseResult(null, CounterConfidence.NONE)
        }

        // Cluster boxes into visual rows so "Счётчик" on the left pairs with "12450" on the right
        val sortedByY = boxedLines.sortedBy { it.third }
        val rows = mutableListOf<MutableList<Triple<Rect, String, Float>>>()
        for (item in sortedByY) {
            val targetRow = rows.lastOrNull()?.let { prevRow ->
                val prevCenter = prevRow.map { it.third }.average().toFloat()
                val avgHeight = prevRow.map { max(1, it.first.height()) }.average().toFloat()
                val tolerance = max(18f, max(avgHeight, item.first.height().toFloat()) * 0.65f)
                if (abs(item.third - prevCenter) <= tolerance) prevRow else null
            }
            if (targetRow != null) {
                targetRow += item
            } else {
                rows += mutableListOf(item)
            }
        }

        val visualRowStrings = rows.map { row ->
            row.sortedBy { it.first.left }.joinToString(" ") { it.second }
        }
        val rowResult = AvrCounterParser.extractCounterFromLines(visualRowStrings)
        if (rowResult.confidence == CounterConfidence.HIGH) {
            return@withContext rowResult
        }

        val rawLines = boxedLines.map { it.second }
        val rawResult = AvrCounterParser.extractCounterFromLines(rawLines)
        if (rawResult.confidence != CounterConfidence.NONE) {
            rawResult
        } else {
            rowResult
        }
    }
}
