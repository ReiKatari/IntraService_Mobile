package com.lantaservice.intra.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
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
import kotlin.math.roundToInt

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

enum class CounterConfidence {
    HIGH,
    MEDIUM,
    NONE
}

data class CounterParseResult(
    val value: String?,
    val confidence: CounterConfidence,
    val matchedLine: String? = null,
    val matchedRowIndex: Int? = null
)

data class AvrDocumentOcrResult(
    val counter: CounterParseResult,
    val serialNumber: String? = null,
    val counterBoxNormalized: NormalizedRect? = null
)

data class OcrBoxLine(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String
)

data class ClusteredRow(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * Pure JVM parser extracting the printer/copier page counter written or printed on an AVR/config sheet.
 * Prioritizes total page/copy summary lines ("Всего напечатано страниц"), ignores sub-counters/settings,
 * never steals numbers from adjacent labeled rows, and supports Latin-OCR Cyrillic lookalikes.
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

    private val priorityTotalKeywordRegexes = listOf(
        Regex("всег[оo0]\\s+напе[ч4уивl][аa][тмm][аa][нпh][оo0](?:\\s+ст[рг]ан\\w*)?", RegexOption.IGNORE_CASE),
        Regex("всег[оo0]\\s+отпе[ч4уивl][аa][тмm][аa][нпh][оo0](?:\\s+(?:ст[рг]ан\\w*|копий|стр\\.?|отпечатк\\w*))?", RegexOption.IGNORE_CASE),
        Regex("всег[оo0]\\s+(?:страниц|копий|отпечатков)", RegexOption.IGNORE_CASE),
        Regex("итого\\s+(?:страниц|копий|отпечатков)", RegexOption.IGNORE_CASE),
        Regex("обще[еe]\\s+кол(?:ичество|-во)?\\s+(?:страниц|копий|отпечатков)", RegexOption.IGNORE_CASE),
        Regex("обще[еe]\\s+число\\s+(?:страниц|копий|отпечатков)", RegexOption.IGNORE_CASE),
        Regex("общий\\s+(?:с[ч4]етчик|счётчик|пробег)", RegexOption.IGNORE_CASE),
        Regex("(?:с[ч4]етчик|счётчик)\\s+(?:всего|итого)", RegexOption.IGNORE_CASE),
        Regex(
            "(?<![a-z])(?:total\\s*pages\\s*printed|total\\s*impressions|total\\s*billing\\s*impressions|" +
                "total\\s*prints?|total\\s*printed|total\\s*pages|total\\s*copies|total\\s*count(?:er)?|" +
                "total\\s*sheet\\s*count|cumulative\\s*page\\s*count|life\\s*count|engine\\s*page\\s*count|" +
                "page\\s*count(?:er)?|billing\\s*meter|total\\s*usage|meter\\s*1|total\\s*1)(?![a-z])",
            RegexOption.IGNORE_CASE
        )
    )

    private val strictCounterKeywordRegexes = listOf(
        // Russian AVR / service counter terms
        Regex("с[ч4]етчик(?:и|а|ов|у|е)?", RegexOption.IGNORE_CASE),
        Regex("счётчик(?:и|а|ов|у|е)?", RegexOption.IGNORE_CASE),
        Regex("пробег(?:\\s+(?:аппарата|принтера|мфу))?", RegexOption.IGNORE_CASE),
        Regex("кол(?:ичество|-во)?\\s+копий", RegexOption.IGNORE_CASE),
        Regex("число\\s+копий", RegexOption.IGNORE_CASE),
        Regex("копий\\s+(?:всего|итого|напечатано)", RegexOption.IGNORE_CASE),
        Regex("показания\\s+(?:с[ч4]етчика|счётчика|пробега|аппарата)", RegexOption.IGNORE_CASE),
        Regex("отпечатк(?:ов|и)", RegexOption.IGNORE_CASE),
        Regex("напечатано\\s+(?:страниц|копий)", RegexOption.IGNORE_CASE),

        // Exact full-word Latin-OCR homoglyphs of Russian counter labels (e.g. C4ET4WK, CYET4NK)
        Regex("(?<![a-zа-я])[cс][4чyу][eе][tт][4ч][uиnпwшl1i][kк](?![a-zа-я])", RegexOption.IGNORE_CASE),
        Regex("(?<![a-zа-я])(?:s?chetchik|probeg|npo6eg)(?![a-zа-я])", RegexOption.IGNORE_CASE),

        // Explicit English printer-report counter labels
        Regex("(?<![a-z])(?:meter\\s*reading|print\\s*count|copy\\s*count|counter\\b)", RegexOption.IGNORE_CASE)
    )

    private val excludedLineMarkers = listOf(
        // Sub-counters and non-total counters (scan/fax/ADF/jams) that must not replace main total counter
        "отсканир", "сканер", "факс", "апд", "замяти", "ошибок", "по умолчанию", "умен.", "увел.",
        "adf", "fax", "jam", "duplex", "scanner",
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

        // Pass 1: Prefer explicit total/summary page counters over per-mode sub-counters
        extractWithKeywordRegexes(lines, priorityTotalKeywordRegexes, checkExcluded = false)?.let {
            return it
        }
        // Pass 2: Standard AVR / counter keyword matching with sub-counter & settings filtering
        extractWithKeywordRegexes(lines, strictCounterKeywordRegexes, checkExcluded = true)?.let {
            return it
        }

        // Never guess standalone unlabelled numbers; trigger manual input prompt instead
        return CounterParseResult(null, CounterConfidence.NONE)
    }

    private fun extractWithKeywordRegexes(
        lines: List<String>,
        regexes: List<Regex>,
        checkExcluded: Boolean
    ): CounterParseResult? {
        for (idx in lines.indices) {
            val line = lines[idx].trim()
            if (line.isEmpty()) continue
            if (checkExcluded && isExcludedNonCounterLine(line)) continue

            val keywordMatch = findKeywordMatch(line, regexes) ?: continue
            val lowerLine = line.lowercase(Locale.ROOT)
            val skelLine = toHomoglyphSkeleton(lowerLine)
            val isTotalOrPrintedLine = regexes === priorityTotalKeywordRegexes ||
                listOf("всего", "итого", "общ", "total", "life", "напечатано страниц", "bcero")
                    .any { lowerLine.contains(it) || skelLine.contains(it) }
            val adjacentPrintedMax = if (isTotalOrPrintedLine) {
                findAdjacentPrintedSubCounterMax(lines, idx)
            } else {
                null
            }

            // Extract number strictly AFTER the counter keyword on the same line
            val afterKeyword = line.substring((keywordMatch.range.last + 1).coerceAtMost(line.length))
            val sameLineNums = extractPlausibleNumbersInLine(afterKeyword)
            if (sameLineNums.isNotEmpty()) {
                val chosenSameLine = if (adjacentPrintedMax != null) {
                    sameLineNums.firstOrNull { (it.toLongOrNull() ?: 0L) >= adjacentPrintedMax }
                } else {
                    sameLineNums.first()
                }
                if (chosenSameLine != null) {
                    return CounterParseResult(chosenSameLine, CounterConfidence.HIGH, line, matchedRowIndex = idx)
                }
            }

            // Tabular fallback: accept next line ONLY if it is a pure value line (never steal numbers from another labeled row!)
            if (idx + 1 < lines.size) {
                val nextLine = lines[idx + 1].trim()
                if (nextLine.isNotEmpty() &&
                    isValueOnlyLine(nextLine) &&
                    (!checkExcluded || !isExcludedNonCounterLine(nextLine))
                ) {
                    val nextLineNum = extractPlausibleNumberFromSegment(nextLine, preferRightmost = false)
                    val nextLong = nextLineNum?.toLongOrNull() ?: 0L
                    if (nextLineNum != null && (adjacentPrintedMax == null || nextLong >= adjacentPrintedMax)) {
                        return CounterParseResult(
                            value = nextLineNum,
                            confidence = CounterConfidence.HIGH,
                            matchedLine = "$line | $nextLine",
                            matchedRowIndex = idx + 1
                        )
                    }
                }
            }
        }
        return null
    }

    /**
     * Checks neighboring rows (-3..+3) for explicit printed sub-counters (e.g. "Отпечатано ч/б стр. с копии = 155"),
     * so a priority Total counter ("Всего напечатано страниц") never accepts an impossible smaller value (like "12" shifted up).
     */
    private fun findAdjacentPrintedSubCounterMax(lines: List<String>, centerIdx: Int): Long? {
        val printedSubCues = listOf(
            "отпечатано", "отпечатан", "напечатано", "с копии", "printed", "copy print", "print count"
        )
        val nonPrintExclusions = listOf(
            "отсканир", "сканер", "scan", "факс", "fax", "апд", "adf",
            "замяти", "jam", "ошиб", "код", "dpi", "память", "памят",
            "мб", "кб", "гб", "формат", "плотност", "макс", "разреш",
            "умен", "увел", "таймаут", "верси", "дата", "даты", "ip"
        )
        var maxSub: Long? = null
        val fromIdx = max(0, centerIdx - 3)
        val toIdx = minOf(lines.lastIndex, centerIdx + 3)
        for (i in fromIdx..toIdx) {
            if (i == centerIdx) continue
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase(Locale.ROOT)
            val skel = toHomoglyphSkeleton(lower)
            if (nonPrintExclusions.any { lower.contains(it) || skel.contains(it) }) continue
            val cue = printedSubCues.firstOrNull { lower.contains(it) || skel.contains(it) } ?: continue
            val pos = maxOf(lower.indexOf(cue), skel.indexOf(cue))
            val tail = if (pos >= 0) line.substring((pos + cue.length).coerceAtMost(line.length)) else line
            val num = extractPlausibleNumberFromSegment(tail, preferRightmost = true)?.toLongOrNull() ?: continue
            if (num > 0L && (maxSub == null || num > maxSub)) {
                maxSub = num
            }
        }
        return maxSub
    }

    internal fun isValueOnlyLine(line: String): Boolean {
        val stripped = line.lowercase(Locale.ROOT)
            .replace(Regex("(?iu)\\b(?:стр\\.?|коп\\.?|pcs|copies|pages|p\\.)\\b"), " ")
            .replace("=", " ")
            .replace(":", " ")
            .trim()
        if (stripped.isEmpty()) return false
        var digits = 0
        var letters = 0
        for (ch in stripped) {
            when {
                ch.isDigit() -> digits++
                ch.isLetter() -> letters++
            }
        }
        return digits >= 1 && letters <= 3
    }

    private fun findKeywordMatch(line: String, regexes: List<Regex>): MatchResult? {
        val lower = line.lowercase(Locale.ROOT)
        for (regex in regexes) {
            regex.find(lower)?.let { return it }
        }
        val skeleton = toHomoglyphSkeleton(lower)
        for (regex in regexes) {
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
                'g' -> 'д'
                'h' -> 'н'
                'k' -> 'к'
                'm' -> 'м'
                'n' -> 'п'
                'o' -> 'о'
                'p' -> 'р'
                'r' -> 'г'
                't' -> 'т'
                'u' -> 'и'
                'v' -> 'в'
                'w' -> 'ш'
                'x' -> 'х'
                'y' -> 'у'
                '0' -> if (prevIsLetter || nextIsLetter) 'о' else ch
                '1' -> if (prevIsLetter && nextIsLetter) 'и' else ch
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

    private const val ROI_PREFS = "intra_avr_roi_prefs"
    private const val KEY_LEFT = "roi_left"
    private const val KEY_TOP = "roi_top"
    private const val KEY_RIGHT = "roi_right"
    private const val KEY_BOTTOM = "roi_bottom"

    fun saveAvrTemplateRoi(context: Context, roi: NormalizedRect) {
        context.getSharedPreferences(ROI_PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_LEFT, roi.left.coerceIn(0f, 1f))
            .putFloat(KEY_TOP, roi.top.coerceIn(0f, 1f))
            .putFloat(KEY_RIGHT, roi.right.coerceIn(0f, 1f))
            .putFloat(KEY_BOTTOM, roi.bottom.coerceIn(0f, 1f))
            .apply()
    }

    fun loadAvrTemplateRoi(context: Context): NormalizedRect? {
        val prefs = context.getSharedPreferences(ROI_PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LEFT)) return null
        val l = prefs.getFloat(KEY_LEFT, 0f)
        val t = prefs.getFloat(KEY_TOP, 0f)
        val r = prefs.getFloat(KEY_RIGHT, 0f)
        val b = prefs.getFloat(KEY_BOTTOM, 0f)
        if (r - l < 0.04f || b - t < 0.03f) return null
        return NormalizedRect(l, t, r, b)
    }

    suspend fun recognizeDigitsInCropRegion(
        context: Context,
        imageFile: File,
        roi: NormalizedRect
    ): String? = withContext(Dispatchers.Default) {
        if (!imageFile.exists()) return@withContext null
        val fullBmp = decodeOrientedBitmap(imageFile) ?: return@withContext null
        val x0 = (roi.left.coerceIn(0f, 0.95f) * fullBmp.width).roundToInt()
        val y0 = (roi.top.coerceIn(0f, 0.95f) * fullBmp.height).roundToInt()
        val x1 = (roi.right.coerceIn(0.05f, 1f) * fullBmp.width).roundToInt().coerceAtLeast(x0 + 16)
        val y1 = (roi.bottom.coerceIn(0.05f, 1f) * fullBmp.height).roundToInt().coerceAtLeast(y0 + 16)
        val cw = (x1 - x0).coerceAtMost(fullBmp.width - x0).coerceAtLeast(16)
        val ch = (y1 - y0).coerceAtMost(fullBmp.height - y0).coerceAtLeast(16)

        val cropped = Bitmap.createBitmap(fullBmp, x0, y0, cw, ch)
        if (cropped !== fullBmp) fullBmp.recycle()

        // Upscale handwritten crop if small so ML Kit reads pen strokes cleanly
        val scale = if (minOf(cropped.width, cropped.height) < 260) {
            260f / max(1, minOf(cropped.width, cropped.height)).toFloat()
        } else {
            1f
        }
        val targetBmp = if (scale > 1.05f) {
            Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * scale).roundToInt().coerceAtMost(1400),
                (cropped.height * scale).roundToInt().coerceAtMost(1400),
                true
            ).also { if (it !== cropped) cropped.recycle() }
        } else {
            cropped
        }

        val bwRes = PhotoEnhancerEngine.enhance(targetBmp, EnhanceMode.DOCUMENT_SCAN_BW)
        val ocrCandidates = mutableListOf<String>()

        processBitmapOcr(bwRes.bitmap)?.let { vt ->
            extractDigitTokens(vt.text).forEach { ocrCandidates += it }
        }
        if (ocrCandidates.isEmpty()) {
            processBitmapOcr(targetBmp)?.let { vt ->
                extractDigitTokens(vt.text).forEach { ocrCandidates += it }
            }
        }

        if (bwRes.bitmap !== targetBmp) bwRes.bitmap.recycle()
        targetBmp.recycle()

        ocrCandidates.firstOrNull { it.length in 1..9 }
    }

    suspend fun recognizeCounterFromFile(
        context: Context,
        imageFile: File
    ): CounterParseResult = recognizeDocumentFieldsFromFile(context, imageFile).counter

    suspend fun recognizeDocumentFieldsFromFile(
        context: Context,
        imageFile: File
    ): AvrDocumentOcrResult = withContext(Dispatchers.Default) {
        if (!imageFile.exists()) {
            return@withContext AvrDocumentOcrResult(CounterParseResult(null, CounterConfidence.NONE), null)
        }

        val (imgW, imgH) = readOrientedDimensions(imageFile)
        var bestCounter = CounterParseResult(null, CounterConfidence.NONE)
        var bestCounterBox: NormalizedRect? = null
        var bestSerial: String? = null

        // Pass 1: Standard OCR on prepared/flattened document image
        val inputImage = runCatching {
            InputImage.fromFilePath(context, Uri.fromFile(imageFile))
        }.getOrNull() ?: return@withContext AvrDocumentOcrResult(bestCounter, null)

        val visionText = processInputImageOcr(inputImage)
        if (visionText != null) {
            val pass1 = extractDocumentFieldsFromBoxedLines(visionText.toOcrBoxLines(), imgW, imgH)
            if (pass1.counter.confidence == CounterConfidence.HIGH) {
                bestCounter = pass1.counter
                bestCounterBox = pass1.counterBoxNormalized
            }
            if (!pass1.serialNumber.isNullOrBlank()) {
                bestSerial = pass1.serialNumber
            }
            if (bestCounter.confidence == CounterConfidence.HIGH && !bestSerial.isNullOrBlank()) {
                return@withContext AvrDocumentOcrResult(bestCounter, bestSerial, bestCounterBox)
            }
        }

        // Decode full oriented bitmap once for targeted 3x row strips / clarity full pass
        val oriented = decodeOrientedBitmap(imageFile)
        if (oriented != null) {
            try {
                // Pass 2a: Targeted 3x upscaled strip around counter label with gentle clarity enhancement
                if (bestCounter.confidence != CounterConfidence.HIGH && visionText != null) {
                    val counterBox = findCounterKeywordLineBox(visionText)
                    if (counterBox != null) {
                        val stripRect = computeRowStripRect(counterBox, oriented.width, oriented.height)
                        val stripRes = recognizeStripFields(oriented, stripRect)
                        if (stripRes != null && stripRes.counter.confidence == CounterConfidence.HIGH) {
                            bestCounter = stripRes.counter
                            bestCounterBox = stripRes.counterBoxNormalized
                                ?.let { mapStripRoiToFull(it, stripRect, oriented.width, oriented.height) }
                                ?: rectToNormalized(stripRect, oriented.width, oriented.height)
                        }
                        if (bestSerial.isNullOrBlank() && !stripRes?.serialNumber.isNullOrBlank()) {
                            bestSerial = stripRes?.serialNumber
                        }
                    }
                }

                // Pass 2b: Targeted 3x upscaled strip around serial label
                if (bestSerial.isNullOrBlank() && visionText != null) {
                    val serialBox = findSerialKeywordLineBox(visionText)
                    if (serialBox != null) {
                        val stripRect = computeRowStripRect(serialBox, oriented.width, oriented.height)
                        val stripRes = recognizeStripFields(oriented, stripRect)
                        if (!stripRes?.serialNumber.isNullOrBlank()) {
                            bestSerial = stripRes?.serialNumber
                        }
                    }
                }

                // Pass 3: Gentle full-page TEXT_CLARITY OCR pass if anything is still missing
                if (bestCounter.confidence != CounterConfidence.HIGH || bestSerial.isNullOrBlank()) {
                    val clarityFull = PhotoEnhancerEngine.enhance(oriented, EnhanceMode.TEXT_CLARITY)
                    val clarityText = processBitmapOcr(clarityFull.bitmap)
                    if (clarityFull.bitmap !== oriented) clarityFull.bitmap.recycle()
                    if (clarityText != null) {
                        val pass3 = extractDocumentFieldsFromBoxedLines(
                            clarityText.toOcrBoxLines(),
                            oriented.width,
                            oriented.height
                        )
                        if (bestCounter.confidence != CounterConfidence.HIGH &&
                            pass3.counter.confidence == CounterConfidence.HIGH
                        ) {
                            bestCounter = pass3.counter
                            bestCounterBox = pass3.counterBoxNormalized
                        }
                        if (bestSerial.isNullOrBlank() && !pass3.serialNumber.isNullOrBlank()) {
                            bestSerial = pass3.serialNumber
                        }
                    }
                }
            } finally {
                oriented.recycle()
            }
        }

        AvrDocumentOcrResult(bestCounter, bestSerial, bestCounterBox)
    }

    private fun Text.toOcrBoxLines(): List<OcrBoxLine> {
        return textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: Rect(0, 0, 0, 0)
            val txt = line.text.trim()
            if (txt.isEmpty()) null else OcrBoxLine(box.left, box.top, box.right, box.bottom, txt)
        }
    }

    fun clusterBoxedLinesIntoRows(items: List<OcrBoxLine>): List<ClusteredRow> {
        if (items.isEmpty()) return emptyList()
        val sortedByY = items.sortedBy { (it.top + it.bottom) * 0.5f }
        val rows = mutableListOf<MutableList<OcrBoxLine>>()
        for (item in sortedByY) {
            val itemCenterY = (item.top + item.bottom) * 0.5f
            val itemH = max(1, item.bottom - item.top).toFloat()
            val targetRow = rows.lastOrNull()?.let { prevRow ->
                val prevCenterY = prevRow.map { (it.top + it.bottom) * 0.5f }.average().toFloat()
                val prevAvgH = prevRow.map { max(1, it.bottom - it.top) }.average().toFloat()
                val refH = minOf(prevAvgH, itemH)
                val tolerance = max(4f, refH * 0.42f)
                if (abs(itemCenterY - prevCenterY) <= tolerance) prevRow else null
            }
            if (targetRow != null) {
                targetRow += item
            } else {
                rows += mutableListOf(item)
            }
        }
        return rows.map { row ->
            val ordered = row.sortedBy { it.left }
            ClusteredRow(
                text = ordered.joinToString(" ") { it.text },
                left = ordered.minOf { it.left },
                top = ordered.minOf { it.top },
                right = ordered.maxOf { it.right },
                bottom = ordered.maxOf { it.bottom }
            )
        }
    }

    fun extractDocumentFieldsFromBoxedLines(
        items: List<OcrBoxLine>,
        imageWidth: Int,
        imageHeight: Int
    ): AvrDocumentOcrResult {
        if (items.isEmpty()) {
            return AvrDocumentOcrResult(CounterParseResult(null, CounterConfidence.NONE), null, null)
        }

        val fullRows = clusterBoxedLinesIntoRows(items)
        val rawRows = items.map { ClusteredRow(it.text, it.left, it.top, it.right, it.bottom) }

        // Detect 2-column config/test reports (e.g. HP M1212nf Config Page) via widest central gutter
        val columnRows = if (items.size >= 4) {
            val minLeft = items.minOf { it.left }
            val maxRight = items.maxOf { it.right }
            val spanW = max(1, maxRight - minLeft)
            val avgW = items.map { max(1, it.right - it.left) }.average()
            if (spanW >= 180 && avgW <= spanW * 0.54) {
                val sortedCenters = items.map { (it.left + it.right) * 0.5f }.sorted()
                val loBound = minLeft + spanW * 0.34f
                val hiBound = minLeft + spanW * 0.66f
                var bestGap = 0f
                var bestSplitX = minLeft + spanW * 0.48f
                for (i in 0 until sortedCenters.lastIndex) {
                    val mid = (sortedCenters[i] + sortedCenters[i + 1]) * 0.5f
                    val gap = sortedCenters[i + 1] - sortedCenters[i]
                    if (mid in loBound..hiBound && gap > bestGap) {
                        bestGap = gap
                        bestSplitX = mid
                    }
                }
                val splitX = if (bestGap >= max(12f, spanW * 0.04f)) bestSplitX else minLeft + spanW * 0.48f
                val leftBoxes = items.filter { (it.left + it.right) * 0.5f < splitX }
                val rightBoxes = items.filter { (it.left + it.right) * 0.5f >= splitX }
                if (leftBoxes.size >= 2 && rightBoxes.size >= 2) {
                    alignTabularColumnRows(leftBoxes) + alignTabularColumnRows(rightBoxes)
                } else emptyList()
            } else emptyList()
        } else emptyList()

        val alignedFullRows = alignTabularColumnRows(items)
        val rowSets = listOf(columnRows, alignedFullRows, fullRows, rawRows).filter { it.isNotEmpty() }
        var matchedCounter = CounterParseResult(null, CounterConfidence.NONE)
        var matchedRowBox: ClusteredRow? = null
        for (rows in rowSets) {
            val parsed = AvrCounterParser.extractCounterFromLines(rows.map { it.text })
            if (parsed.confidence == CounterConfidence.HIGH) {
                matchedCounter = parsed
                val rowIdx = parsed.matchedRowIndex
                if (rowIdx != null && rowIdx in rows.indices) {
                    matchedRowBox = rows[rowIdx]
                }
                break
            }
        }

        val counterBoxNormalized = if (matchedRowBox != null && imageWidth > 0 && imageHeight > 0) {
            val padX = imageWidth * 0.015f
            val padY = imageHeight * 0.008f
            val l = ((matchedRowBox.left - padX) / imageWidth.toFloat()).coerceIn(0f, 0.96f)
            val t = ((matchedRowBox.top - padY) / imageHeight.toFloat()).coerceIn(0f, 0.96f)
            val r = ((matchedRowBox.right + padX) / imageWidth.toFloat()).coerceIn(l + 0.04f, 1f)
            val b = ((matchedRowBox.bottom + padY) / imageHeight.toFloat()).coerceIn(t + 0.03f, 1f)
            NormalizedRect(l, t, r, b)
        } else {
            null
        }

        val serial = rowSets.firstNotNullOfOrNull { rows ->
            EquipmentScanParser.extractLabeledSerialFromOcrLines(rows.map { it.text })
        }
        return AvrDocumentOcrResult(matchedCounter, serial, counterBoxNormalized)
    }

    /**
     * When a table column has left-side labels facing a right-side stack of numeric "= N" values
     * that drifted vertically (bowed/tilted paper, e.g. "12" shifted slightly toward "2320"),
     * estimate the best uniform vertical offset (shiftY) so every label pairs 1-to-1 with its real value.
     */
    private fun alignTabularColumnRows(colItems: List<OcrBoxLine>): List<ClusteredRow> {
        val baseRows = clusterBoxedLinesIntoRows(colItems)
        if (colItems.size < 6) return baseRows

        val colMinX = colItems.minOf { it.left }
        val colMaxX = colItems.maxOf { it.right }
        val colW = max(1, colMaxX - colMinX)
        if (colW < 80) return baseRows

        val pureValueRegex = Regex("^=?\\s*(?:\\d{1,3}(?:[\\s,.']\\d{3})+|\\d{1,8})\\s*$")
        val valueMidX = colMinX + colW * 0.52f
        val valueBoxes = colItems
            .filter { it.left >= valueMidX && pureValueRegex.matches(it.text.trim()) }
            .sortedBy { (it.top + it.bottom) * 0.5f }
        if (valueBoxes.size < 4) return baseRows

        val labelItems = colItems.filter { it !in valueBoxes }
        val labelRows = clusterBoxedLinesIntoRows(labelItems)
            .filter { row -> !pureValueRegex.matches(row.text.trim()) }
        if (labelRows.size < 4) return baseRows

        val valGaps = (0 until valueBoxes.lastIndex).mapNotNull { i ->
            val dy = ((valueBoxes[i + 1].top + valueBoxes[i + 1].bottom) - (valueBoxes[i].top + valueBoxes[i].bottom)) * 0.5f
            if (dy in 6f..45f) dy else null
        }.sorted()
        val pitchY = if (valGaps.isNotEmpty()) valGaps[valGaps.size / 2] else return baseRows

        fun buildPairs(shiftY: Float): Pair<Double, List<Pair<ClusteredRow, OcrBoxLine>>> {
            val pairs = mutableListOf<Pair<ClusteredRow, OcrBoxLine>>()
            var vStart = 0
            var score = 0.0
            val maxDist = pitchY * 0.45f
            for (lRow in labelRows) {
                val lCy = (lRow.top + lRow.bottom) * 0.5f
                var bestJ = -1
                var bestErr = Float.MAX_VALUE
                for (j in vStart until valueBoxes.size) {
                    val vCy = (valueBoxes[j].top + valueBoxes[j].bottom) * 0.5f + shiftY
                    val err = abs(lCy - vCy)
                    if (err <= maxDist && err < bestErr) {
                        bestErr = err
                        bestJ = j
                    } else if (vCy > lCy + maxDist) {
                        break
                    }
                }
                if (bestJ >= 0) {
                    pairs += lRow to valueBoxes[bestJ]
                    score += 10.0 - (bestErr / max(1f, pitchY)) * 4.0
                    vStart = bestJ + 1
                }
            }
            // Check section continuity inside the matched range
            if (pairs.size >= 3) {
                val firstL = labelRows.indexOf(pairs.first().first)
                val lastL = labelRows.indexOf(pairs.last().first)
                val expectedLabels = (lastL - firstL + 1).coerceAtLeast(1)
                val unmatchedLabels = expectedLabels - pairs.size
                score -= unmatchedLabels * 5.0
                val unmatchedVals = (valueBoxes.size - pairs.size).coerceAtLeast(0)
                score -= unmatchedVals * 6.0

                // Semantic penalty if a total-printed label got a value strictly smaller than an adjacent print sub-counter
                val syntheticLines = pairs.map { "${it.first.text} ${it.second.text}" }
                val totalRes = AvrCounterParser.extractCounterFromLines(syntheticLines)
                if (totalRes.confidence == CounterConfidence.HIGH) {
                    score += 6.0
                } else {
                    val hasTotalLabel = pairs.any {
                        val low = it.first.text.lowercase(Locale.ROOT)
                        low.contains("всего") || low.contains("total")
                    }
                    if (hasTotalLabel) score -= 25.0
                }
            }
            return score to pairs
        }

        val step = 2f
        val maxShift = (pitchY * 0.90f).coerceAtMost(24f)
        var bestScore = Double.NEGATIVE_INFINITY
        var bestPairs: List<Pair<ClusteredRow, OcrBoxLine>> = emptyList()
        var bestShift = 0f

        var s = -maxShift
        while (s <= maxShift + 0.1f) {
            val (sc, pr) = buildPairs(s)
            if (sc > bestScore) {
                bestScore = sc
                bestPairs = pr
                bestShift = s
            }
            s += step
        }

        val (zeroScore, zeroPairs) = buildPairs(0f)
        if (abs(bestShift) < 3f && zeroPairs.size >= bestPairs.size) {
            return baseRows
        }
        if (bestPairs.size < 3 || bestScore <= zeroScore + 1.5) {
            return baseRows
        }

        val pairedMap = bestPairs.associate { it.first to it.second }
        val pairedValueSet = bestPairs.map { it.second }.toSet()
        val result = mutableListOf<ClusteredRow>()
        for (lRow in labelRows) {
            val vBox = pairedMap[lRow]
            if (vBox != null) {
                result += ClusteredRow(
                    text = "${lRow.text} ${vBox.text}",
                    left = minOf(lRow.left, vBox.left),
                    top = minOf(lRow.top, vBox.top),
                    right = maxOf(lRow.right, vBox.right),
                    bottom = maxOf(lRow.bottom, vBox.bottom)
                )
            } else {
                result += lRow
            }
        }
        // Append any leftover unpaired values only if needed, keeping order clean
        for (vBox in valueBoxes) {
            if (vBox !in pairedValueSet) {
                result += ClusteredRow(vBox.text, vBox.left, vBox.top, vBox.right, vBox.bottom)
            }
        }
        return result.sortedBy { (it.top + it.bottom) * 0.5f }
    }

    private suspend fun recognizeStripFields(
        oriented: Bitmap,
        stripRect: Rect
    ): AvrDocumentOcrResult? {
        val cw = stripRect.width().coerceAtLeast(16)
        val ch = stripRect.height().coerceAtLeast(16)
        val strip = Bitmap.createBitmap(oriented, stripRect.left, stripRect.top, cw, ch)
        val upscaled = Bitmap.createScaledBitmap(
            strip,
            (cw * 3).coerceAtMost(2000),
            (ch * 3).coerceAtMost(720),
            true
        )
        if (upscaled !== strip) strip.recycle()
        val stripW = upscaled.width
        val stripH = upscaled.height

        fun filterCleanBoxes(visionText: Text): List<OcrBoxLine> {
            return visionText.toOcrBoxLines().filter { box ->
                box.top > 2 && box.bottom < (stripH - 2)
            }
        }

        // Probe 1: Raw clean 3x upscaled strip (avoids CLAHE haloes on already B&W scans)
        val rawText = processBitmapOcr(upscaled)
        val rawRes = rawText?.let { extractDocumentFieldsFromBoxedLines(filterCleanBoxes(it), stripW, stripH) }
        if (rawRes != null && rawRes.counter.confidence == CounterConfidence.HIGH) {
            upscaled.recycle()
            return rawRes
        }

        // Probe 2: Enhanced contrast 3x strip fallback
        val clarityStrip = PhotoEnhancerEngine.enhance(upscaled, EnhanceMode.TEXT_CLARITY)
        if (clarityStrip.bitmap !== upscaled) upscaled.recycle()
        val clarityText = processBitmapOcr(clarityStrip.bitmap)
        clarityStrip.bitmap.recycle()
        val clarityRes = clarityText?.let { extractDocumentFieldsFromBoxedLines(filterCleanBoxes(it), stripW, stripH) }
        return when {
            clarityRes != null && clarityRes.counter.confidence == CounterConfidence.HIGH -> clarityRes
            clarityRes != null && !clarityRes.serialNumber.isNullOrBlank() -> clarityRes
            else -> rawRes
        }
    }

    private fun computeRowStripRect(box: Rect, imgW: Int, imgH: Int): Rect {
        val l = (box.left - box.width() * 0.25f).roundToInt().coerceIn(0, (imgW - 32).coerceAtLeast(0))
        val t = (box.top - box.height() * 1.8f).roundToInt().coerceIn(0, (imgH - 16).coerceAtLeast(0))
        val r = (box.right + imgW * 0.48f).roundToInt().coerceIn((l + 32).coerceAtMost(imgW), imgW)
        val b = (box.bottom + box.height() * 1.6f).roundToInt().coerceIn((t + 16).coerceAtMost(imgH), imgH)
        return Rect(l, t, r, b)
    }

    private fun mapStripRoiToFull(
        stripRoi: NormalizedRect,
        stripRect: Rect,
        imgW: Int,
        imgH: Int
    ): NormalizedRect {
        val sw = stripRect.width().toFloat()
        val sh = stripRect.height().toFloat()
        val l = ((stripRect.left + stripRoi.left * sw) / imgW.toFloat()).coerceIn(0f, 0.96f)
        val t = ((stripRect.top + stripRoi.top * sh) / imgH.toFloat()).coerceIn(0f, 0.96f)
        val r = ((stripRect.left + stripRoi.right * sw) / imgW.toFloat()).coerceIn(l + 0.04f, 1f)
        val b = ((stripRect.top + stripRoi.bottom * sh) / imgH.toFloat()).coerceIn(t + 0.03f, 1f)
        return NormalizedRect(l, t, r, b)
    }

    private fun rectToNormalized(rect: Rect, imgW: Int, imgH: Int): NormalizedRect {
        val l = (rect.left.toFloat() / max(1, imgW).toFloat()).coerceIn(0f, 0.96f)
        val t = (rect.top.toFloat() / max(1, imgH).toFloat()).coerceIn(0f, 0.96f)
        val r = (rect.right.toFloat() / max(1, imgW).toFloat()).coerceIn(l + 0.04f, 1f)
        val b = (rect.bottom.toFloat() / max(1, imgH).toFloat()).coerceIn(t + 0.03f, 1f)
        return NormalizedRect(l, t, r, b)
    }

    private fun findCounterKeywordLineBox(visionText: Text): Rect? {
        val strongHints = listOf(
            "всего", "bcero", "bcer0", "итого", "total", "life count",
            "напечат", "haneyat", "hane4at", "отпечат", "impressions",
            "счет", "счёт", "c4et", "cyet", "chetchik", "пробег", "page count", "meter"
        )
        var fallbackBox: Rect? = null
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lower = line.text.lowercase(Locale.ROOT)
                if (lower.contains("четкост") || lower.contains("чёткост") ||
                    lower.contains("отсканир") || lower.contains("факс") ||
                    lower.contains("замяти") || lower.contains("ошибок")
                ) {
                    continue
                }
                if (lower.contains("всего") || lower.contains("bcero") || lower.contains("bcer0") ||
                    lower.contains("total") || lower.contains("итого")
                ) {
                    return line.boundingBox
                }
                if (fallbackBox == null && strongHints.any { lower.contains(it) }) {
                    fallbackBox = line.boundingBox
                }
            }
        }
        return fallbackBox
    }

    private fun findSerialKeywordLineBox(visionText: Text): Rect? {
        val hints = listOf(
            "серий", "сер.", "сер ", "cep", "serial", "s/n", "s / n", "зав.", "завод"
        )
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lower = line.text.lowercase(Locale.ROOT)
                if (hints.any { lower.contains(it) }) {
                    return line.boundingBox
                }
            }
        }
        return null
    }

    private fun readOrientedDimensions(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val rawW = opts.outWidth.coerceAtLeast(1)
        val rawH = opts.outHeight.coerceAtLeast(1)
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val swap = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        return if (swap) rawH to rawW else rawW to rawH
    }

    private fun extractDigitTokens(raw: String): List<String> {
        val grouped = Regex("\\b\\d{1,3}(?:[\\s,.']\\d{3})+\\b|\\b\\d{1,9}\\b")
        return grouped.findAll(raw)
            .map { it.value.filter(Char::isDigit) }
            .filter { it.isNotEmpty() && it.length <= 9 }
            .sortedByDescending { it.length }
            .toList()
    }

    private suspend fun processBitmapOcr(bmp: Bitmap): Text? {
        val input = runCatching { InputImage.fromBitmap(bmp, 0) }.getOrNull() ?: return null
        return processInputImageOcr(input)
    }

    private suspend fun processInputImageOcr(inputImage: InputImage): Text? {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return runCatching {
            suspendCancellableCoroutine { cont ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { result ->
                        if (cont.isActive) cont.resume(result)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resumeWith(Result.failure(it))
                    }
            }
        }.getOrNull()
    }

    private fun decodeOrientedBitmap(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val rot = runCatching {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rot == 0f) return bmp
        val m = Matrix().apply { postRotate(rot) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also {
            if (it !== bmp) bmp.recycle()
        }
    }
}
