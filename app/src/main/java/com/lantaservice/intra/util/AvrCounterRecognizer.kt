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
 * Strictly requires an explicit counter label, ignores printer configuration settings
 * (e.g. font sharpening / DPI / memory), and never guesses standalone unlabelled numbers.
 */
object AvrCounterParser {

    private val dateRegex = Regex("\\b\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}\\b|\\b\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}\\b")
    private val timeRegex = Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b")
    private val phoneRegex = Regex("(?:\\+7|8)[\\s(-]*\\d{3}[\\s)-]*\\d{3}[\\s-]*\\d{2}[\\s-]*\\d{2}")
    private val moneyRegex = Regex("\\b\\d+(?:[\\s.,]\\d{3})*[.,]\\d{2}\\b")
    private val docNumberRegex = Regex("(?i)(?:№|no\\.?|#)\\s*\\d{1,8}\\b")
    private val unitNumberRegex = Regex("(?i)\\b\\d+(?:[.,]\\d+)?\\s*(?:dpi|мм|mm|см|cm|мб|mb|гб|gb|кб|kb|руб|₽|%|сек|мин|hz|гц|вт|°)\\b")
    private val alphaNumSerialRegex = Regex("\\b(?:[A-Za-zА-Яа-я]+\\d+\\w*|\\d+[A-Za-zА-Яа-я]+)\\b")
    private val numberCandidateRegex = Regex("\\b\\d{1,3}(?:[\\s,.']\\d{3})+\\b|\\b\\d{1,8}\\b")

    private val strictCounterKeywordRegexes = listOf(
        // Russian AVR / service counter terms
        Regex("с[ч4]етчик(?:и|а|ов|у|е)?", RegexOption.IGNORE_CASE),
        Regex("счётчик(?:и|а|ов|у|е)?", RegexOption.IGNORE_CASE),
        Regex("пробег(?:\\s+(?:аппарата|принтера|мфу))?", RegexOption.IGNORE_CASE),
        Regex("кол(?:ичество|-во)?\\s+копий", RegexOption.IGNORE_CASE),
        Regex("число\\s+копий", RegexOption.IGNORE_CASE),
        Regex("всего\\s+копий", RegexOption.IGNORE_CASE),
        Regex("итого\\s+копий", RegexOption.IGNORE_CASE),
        Regex("копий\\s+(?:всего|итого|напечатано)", RegexOption.IGNORE_CASE),
        Regex("показания\\s+(?:с[ч4]етчика|счётчика|пробега|аппарата)", RegexOption.IGNORE_CASE),
        Regex("отпечатк(?:ов|и)", RegexOption.IGNORE_CASE),
        Regex("напечатано\\s+(?:страниц|копий)", RegexOption.IGNORE_CASE),

        // Exact full-word Latin-OCR homoglyphs of Russian counter labels (e.g. C4ET4WK, CYET4NK)
        Regex("(?<![a-zа-я])[cс][4чyу][eе][tт][4ч][uиnпwшl1i][kк](?![a-zа-я])", RegexOption.IGNORE_CASE),
        Regex("(?<![a-zа-я])(?:s?chetchik|probeg|npo6eg)(?![a-zа-я])", RegexOption.IGNORE_CASE),

        // Explicit English printer-report counter labels
        Regex("(?<![a-z])(?:page\\s*count|total\\s*pages|total\\s*impressions|total\\s*copies|total\\s*count|life\\s*count|meter\\s*reading|print\\s*count|copy\\s*count|counter\\b)", RegexOption.IGNORE_CASE)
    )

    private val excludedLineMarkers = listOf(
        // Device settings / configuration pages (Lexmark, Kyocera, HP, Ricoh, Xerox, Canon)
        "конфигурац", "меню", "четкост", "чёткост", "шрифт", "резкост",
        "яркост", "контраст", "плотност", "разрешен", "масштаб", "формат",
        "лоток", "таймаут", "память", "прошивк", "верси", "настройк", "параметр",
        "dpi", " мб", " гб", " кб", "font", "enhancement", "sharpness", "darkness",
        "contrast", "timeout", "memory", "firmware", "configuration", "config",
        "menu", "setting", "tray", "lexmark", "kyocera", "ricoh", "xerox", "canon", "brother",
        // Legal / requisites / address / metadata lines
        "инн", "кпп", "огрн", "бик", "р/с", "к/с", "окпо",
        "тел", "факс", "phone", "индекс", "ул.", "д.",
        "москва", "санкт", "договор", "заявк", "inn", "ogrn"
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

        for (idx in lines.indices) {
            val line = lines[idx].trim()
            if (line.isEmpty() || isExcludedNonCounterLine(line)) continue

            val keywordMatch = findKeywordMatch(line) ?: continue

            // Extract number strictly AFTER the counter keyword on the same line
            val afterKeyword = line.substring((keywordMatch.range.last + 1).coerceAtMost(line.length))
            val sameLineNum = extractPlausibleNumberFromSegment(afterKeyword, preferRightmost = false)
            if (sameLineNum != null) {
                return CounterParseResult(sameLineNum, CounterConfidence.HIGH, line)
            }

            // Tabular fallback: check immediately following line if it's not a printer setting/metadata line
            if (idx + 1 < lines.size) {
                val nextLine = lines[idx + 1].trim()
                if (nextLine.isNotEmpty() && !isExcludedNonCounterLine(nextLine)) {
                    val nextLineNum = extractPlausibleNumberFromSegment(nextLine, preferRightmost = false)
                    if (nextLineNum != null) {
                        return CounterParseResult(nextLineNum, CounterConfidence.HIGH, "$line | $nextLine")
                    }
                }
            }
        }

        // Never guess standalone unlabelled numbers; trigger manual input prompt instead
        return CounterParseResult(null, CounterConfidence.NONE)
    }

    private fun findKeywordMatch(line: String): MatchResult? {
        val lower = line.lowercase(Locale.ROOT)
        for (regex in strictCounterKeywordRegexes) {
            regex.find(lower)?.let { return it }
        }
        val skeleton = toHomoglyphSkeleton(lower)
        for (regex in strictCounterKeywordRegexes) {
            regex.find(skeleton)?.let { return it }
        }
        return null
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
        val lower = line.lowercase(Locale.ROOT)
        val skeleton = toHomoglyphSkeleton(lower)
        return excludedLineMarkers.any { marker ->
            lower.contains(marker) || skeleton.contains(marker)
        }
    }

    private fun extractPlausibleNumberFromSegment(segment: String, preferRightmost: Boolean): String? {
        val candidates = extractPlausibleNumbersInLine(segment)
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
        cleaned = unitNumberRegex.replace(cleaned, " ")
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
