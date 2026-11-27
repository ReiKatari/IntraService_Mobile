package com.lantaservice.intra.util

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.Locale

@Immutable
@Serializable
data class EquipmentScanResult(
    val serialNumber: String = "",
    val inventoryNumber: String = "",
    val modelName: String = "",
    val rawSource: String = "",
    val matchesTicket: Boolean? = null,
    val matchNote: String = "",
    val candidateSerials: List<String> = emptyList(),
    val skippedCodes: List<String> = emptyList(),
    val detectedVendor: String = ""
) {
    fun formatSummaryForComment(): String {
        val parts = buildList {
            if (modelName.isNotBlank()) add("Модель: $modelName")
            if (serialNumber.isNotBlank()) add("S/N: $serialNumber")
            if (inventoryNumber.isNotBlank()) add("Инв. №: $inventoryNumber")
        }
        val base = parts.joinToString(", ")
        return if (matchNote.isNotBlank() && matchesTicket != null) {
            "$base ($matchNote)"
        } else {
            base
        }
    }
}

/**
 * Pure JVM parser extracting MFP/printer model, serial number (S/N),
 * and inventory tag from ML Kit barcodes (DataMatrix/Code128/QR) or label OCR,
 * filtering out regulatory compliance IDs & OEM sticker part numbers,
 * then cross-checking against the ticket title and description.
 */
object EquipmentScanParser {

    private val brandRegex = Regex(
        "(?iu)\\b(Lexmark|Kyocera|Hewlett[\\s-]?Packard|HP|Ricoh|Xerox|Canon|Brother|Konica\\s+Minolta|Pantum|Epson|Samsung|Sharp|Oki|Toshiba|Sindoh|Avision|Bulat|Develop|Zebra|Honeywell|Godex|Bixolon|Citizen|Dymo|Xprinter|Rongta|АТОЛ|Katyusha|Катюша)\\b([A-Za-zА-Яа-я0-9\\s/-]{1,28})?"
    )
    private val serialLabelRegex = Regex(
        "(?iu)(?:s\\s*/\\s*n|sn|serial(?:\\s*no\\.?)?|сер\\.?\\s*№?|серийный(?:\\s*номер)?|зав\\.?\\s*№?)\\s*[:#=-]?\\s*([A-Z0-9-]{6,24})"
    )
    private val invLabelRegex = Regex(
        "(?iu)(?:инв\\.?\\s*№\\s*|инв\\.?\\s*[:#=]\\s*|инв\\.?\\s+|inv(?:entory)?(?:\\s*no\\.?)?\\s*[:#=]\\s*|inv(?:entory)?(?:\\s*no\\.?)?\\s+|asset\\s*(?:id|tag)?\\s*[:#=]?\\s*)([A-Z0-9-]{4,20})"
    )
    private val standaloneSerialRegex = Regex("^(?=.*[A-Z])(?=.*\\d)[A-Z0-9-]{8,24}$|^\\d{10,18}$")

    // OEM label revision / FRU / HP Regulatory Model Numbers (RMN) & electrical ratings
    private val oemLabelRevisionRegex = Regex("^\\d{2}[A-Z]\\d{4}-\\d{3}$|^[A-Z0-9]{4,12}-0\\d{2}$")
    private val regulatoryModelNumberRegex = Regex(
        "^(?:BOISB|FCLSD|SHNGC|SEOLA|VCVRA|SNPRC|SDGOB|SNPRB|Q75|CE|CF|CC)[-A-Z0-9]*-\\d{2,3}$|^[A-Z]{4,6}-?\\d{4}-\\d{2,3}$"
    )
    private val electricalRatingRegex = Regex(
        "(?i)^\\d{2,3}(?:[-/~]\\d{2,3})?\\s*[VvAaWw]$|^\\d+(?:[.,/]\\d+)?\\s*(?:HZ|MA|KW|VA|VAC|VDC)$|^\\d{2,3}[-/~]\\d{2,3}V.*$"
    )
    private val macAddressRegex = Regex("^([0-9A-F]{2}[:-]){5}[0-9A-F]{2}$")
    private val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val imdaApprovalRegex = Regex("^DA\\d{5,8}$")
    private val regulatoryLineRegex = Regex(
        "(?iu)\\b(?:imda|standards?|complies|fcc\\s*id|cmiit|anatel|rohs|ic\\s*:|eac|rmn|regulatory\\s*model|part\\s*no|p/?n|fru|rev)\\b"
    )
    private val partNumberPrefixRegex = Regex("(?iu)^(?:p/?n|pn|fru|part|rev|rmn)[:#=-].*$")

    /**
     * Printer/MFP serial numbers never contain letter O (always digit zero 0).
     */
    fun normalizePrinterSerial(raw: String): String {
        return raw.trim()
            .replace(" ", "")
            .uppercase(Locale.ROOT)
            .replace('O', '0')
            .replace('О', '0')
    }

    fun normalizeVendorName(raw: String): String {
        return when (val up = raw.trim().uppercase(Locale.ROOT).replace("-", " ").replace(Regex("\\s+"), " ")) {
            "HEWLETT PACKARD", "HP" -> "HP"
            "KATYUSHA", "КАТЮША" -> "Катюша"
            "KONICA MINOLTA" -> "Konica Minolta"
            "OKI" -> "OKI"
            "TSC" -> "TSC"
            "RISO" -> "RISO"
            "F+ IMAGING" -> "F+ imaging"
            "TRIUMPH ADLER" -> "Triumph-Adler"
            "UTAX" -> "UTAX"
            "АТОЛ" -> "АТОЛ"
            else -> up.lowercase(Locale.ROOT).split(" ")
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
        }
    }

    fun isNonSerialLabelCode(
        code: String,
        currentLine: String = "",
        previousLine: String = ""
    ): Boolean {
        val up = code.trim().uppercase(Locale.ROOT)
        if (up.isEmpty()) return true
        if (up.startsWith("HTTP://") || up.startsWith("HTTPS://") || up.startsWith("WWW.")) return true
        if (macAddressRegex.matches(up) || ipv4Regex.matches(up)) return true
        if (oemLabelRevisionRegex.matches(up) || regulatoryModelNumberRegex.matches(up)) return true
        if (electricalRatingRegex.matches(up)) return true
        if (partNumberPrefixRegex.matches(up)) return true

        val contextText = "$previousLine $currentLine"
        val hasExplicitSerialLabel = serialLabelRegex.containsMatchIn(contextText)
        if (!hasExplicitSerialLabel) {
            if (imdaApprovalRegex.matches(up)) {
                return contextText.contains("imda", ignoreCase = true) ||
                    contextText.contains("standard", ignoreCase = true) ||
                    contextText.contains("compl", ignoreCase = true) ||
                    currentLine.isBlank()
            }
            if (regulatoryLineRegex.containsMatchIn(contextText)) {
                return true
            }
        }
        return false
    }

    fun findRejectedBarcodeLabel(barcodeValues: List<String>): String? {
        for (raw in barcodeValues) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val compact = trimmed.uppercase(Locale.ROOT).replace(" ", "")
            if (isNonSerialLabelCode(compact, trimmed)) {
                return trimmed
            }
        }
        return null
    }

    fun parse(
        barcodeValues: List<String>,
        ocrLines: List<String>,
        ticketText: String,
        catalogEntries: List<PrinterCatalogEntry> = PrinterModelCatalog.currentEntries()
    ): EquipmentScanResult? {
        val cleanBarcodes = barcodeValues.map { it.trim() }.filter { it.isNotEmpty() }
        val cleanOcr = ocrLines.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanBarcodes.isEmpty() && cleanOcr.isEmpty()) return null

        var inventoryNumber = ""
        var modelName = ""
        var detectedVendor = ""
        val explicitSerials = mutableListOf<String>()
        val barcodeSerials = mutableListOf<String>()
        val ocrFallbackSerials = mutableListOf<String>()
        val skippedCodes = mutableListOf<String>()
        val acceptedSources = mutableListOf<String>()

        // 1. Check barcodes first (ignoring OEM label part numbers like 36S2890-004, RMN, electrical ratings)
        for (raw in cleanBarcodes) {
            val upper = raw.uppercase(Locale.ROOT).trim()
            var barcodeMatchedSerial = false
            serialLabelRegex.find(upper)?.let { m ->
                val rawSn = m.groupValues[1].trim()
                if (!isNonSerialLabelCode(rawSn, raw)) {
                    explicitSerials += normalizePrinterSerial(rawSn)
                    barcodeMatchedSerial = true
                    acceptedSources += raw.take(24)
                } else {
                    skippedCodes += rawSn
                }
            }
            invLabelRegex.find(upper)?.let { m ->
                if (inventoryNumber.isEmpty()) inventoryNumber = m.groupValues[1].trim()
            }
            if (!barcodeMatchedSerial) {
                val compact = upper.replace(" ", "")
                if (isNonSerialLabelCode(compact, raw)) {
                    skippedCodes += raw
                } else if (standaloneSerialRegex.matches(compact)) {
                    barcodeSerials += normalizePrinterSerial(compact)
                    acceptedSources += raw.take(24)
                }
            }
            val (v, mod) = extractVendorAndModel(raw, catalogEntries)
            if (detectedVendor.isEmpty() && !v.isNullOrBlank()) detectedVendor = v
            if (modelName.isEmpty() && !mod.isNullOrBlank()) modelName = mod
        }

        // 2. Enrich from label OCR lines (never stop at a bare vendor line; keep scanning for actual model)
        for (idx in cleanOcr.indices) {
            val line = cleanOcr[idx]
            val prevLine = if (idx > 0) cleanOcr[idx - 1] else ""
            val (v, mod) = extractVendorAndModel(line, catalogEntries)
            if (detectedVendor.isEmpty() && !v.isNullOrBlank()) detectedVendor = v
            if (modelName.isEmpty() && !mod.isNullOrBlank()) modelName = mod

            serialLabelRegex.find(line)?.let { m ->
                val rawSn = m.groupValues[1].uppercase(Locale.ROOT).trim()
                if (!isNonSerialLabelCode(rawSn, line, prevLine)) {
                    explicitSerials += normalizePrinterSerial(rawSn)
                } else {
                    skippedCodes += rawSn
                }
            }
            if (line.contains("s/n", ignoreCase = true) && idx + 1 < cleanOcr.size) {
                val nextLine = cleanOcr[idx + 1].trim()
                val nextTok = nextLine.uppercase(Locale.ROOT).replace(" ", "")
                if (standaloneSerialRegex.matches(nextTok) && !isNonSerialLabelCode(nextTok, nextLine, line)) {
                    explicitSerials += normalizePrinterSerial(nextTok)
                }
            }
            if (inventoryNumber.isEmpty()) {
                invLabelRegex.find(line)?.let { m ->
                    inventoryNumber = m.groupValues[1].uppercase(Locale.ROOT).trim()
                }
            }
        }

        if (modelName.isEmpty() && catalogEntries.isNotEmpty()) {
            val combinedText = (cleanBarcodes + cleanOcr).joinToString("\n")
            val matchedCatalog = PrinterModelCatalogEngine.matchModelFromText(catalogEntries, combinedText)
            if (!matchedCatalog.isNullOrBlank()) {
                modelName = matchedCatalog
            }
        }

        // 3. Fallback OCR standalone alphanumeric tokens (filtering out regulatory/RMN/voltage & model tokens)
        val modelUpper = modelName.uppercase(Locale.ROOT)
        val invUpper = inventoryNumber.uppercase(Locale.ROOT)
        for (idx in cleanOcr.indices) {
            val line = cleanOcr[idx]
            val prevLine = if (idx > 0) cleanOcr[idx - 1] else ""
            line.split(Regex("[\\s,;]+")).forEach { tok ->
                val up = tok.trim().uppercase(Locale.ROOT)
                if (up.isEmpty()) return@forEach
                val isModelToken = modelUpper.isNotEmpty() && modelUpper.contains(up)
                val isInvToken = invUpper.isNotEmpty() && up == invUpper
                if (isModelToken || isInvToken) return@forEach

                if (isNonSerialLabelCode(up, line, prevLine)) {
                    if (standaloneSerialRegex.matches(up)) skippedCodes += up
                } else if (standaloneSerialRegex.matches(up)) {
                    ocrFallbackSerials += normalizePrinterSerial(up)
                }
            }
        }

        val allCandidates = (explicitSerials + barcodeSerials + ocrFallbackSerials)
            .map { normalizePrinterSerial(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        val serialNumber = allCandidates.firstOrNull().orEmpty()

        if (serialNumber.isEmpty() && inventoryNumber.isEmpty() && modelName.isEmpty() && detectedVendor.isEmpty()) {
            return null
        }

        // 4. Verify against ticket description/assets
        val normalizedTicket = ticketText.lowercase(Locale.ROOT)
        val (matches, note) = evaluateTicketMatch(
            normalizedTicket = normalizedTicket,
            modelName = modelName,
            serialNumber = serialNumber,
            inventoryNumber = inventoryNumber
        )

        val src = when {
            acceptedSources.isNotEmpty() && cleanOcr.isNotEmpty() -> "Штрихкод + OCR"
            acceptedSources.isNotEmpty() -> "Штрихкод (${acceptedSources.first()})"
            else -> "OCR шильдика"
        }

        return EquipmentScanResult(
            serialNumber = serialNumber,
            inventoryNumber = inventoryNumber,
            modelName = modelName,
            rawSource = src,
            matchesTicket = matches,
            matchNote = note,
            candidateSerials = allCandidates,
            skippedCodes = skippedCodes.distinct(),
            detectedVendor = detectedVendor
        )
    }

    /**
     * Extracts expected equipment model, serial number, and inventory number mentioned
     * in a ticket's title/description before scanning, so the engineer knows what to look for.
     */
    fun extractExpectedFromTicket(
        ticketText: String,
        catalogEntries: List<PrinterCatalogEntry> = PrinterModelCatalog.currentEntries()
    ): EquipmentScanResult? {
        if (ticketText.isBlank()) return null
        val lines = ticketText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var serialNumber = ""
        var inventoryNumber = ""
        var modelName = ""
        var detectedVendor = ""
        for (line in lines) {
            val (v, mod) = extractVendorAndModel(line, catalogEntries)
            if (detectedVendor.isEmpty() && !v.isNullOrBlank()) detectedVendor = v
            if (modelName.isEmpty() && !mod.isNullOrBlank()) modelName = mod

            if (serialNumber.isEmpty()) {
                serialLabelRegex.find(line)?.let { m ->
                    val sn = m.groupValues[1].uppercase(Locale.ROOT).trim()
                    if (!isNonSerialLabelCode(sn, line)) {
                        serialNumber = normalizePrinterSerial(sn)
                    }
                }
            }
            if (inventoryNumber.isEmpty()) {
                invLabelRegex.find(line)?.let { m ->
                    inventoryNumber = m.groupValues[1].uppercase(Locale.ROOT).trim()
                }
            }
        }
        if (modelName.isEmpty() && catalogEntries.isNotEmpty()) {
            modelName = PrinterModelCatalogEngine.matchModelFromText(catalogEntries, ticketText).orEmpty()
        }
        if (serialNumber.isEmpty() && inventoryNumber.isEmpty() && modelName.isEmpty()) return null
        return EquipmentScanResult(
            serialNumber = serialNumber,
            inventoryNumber = inventoryNumber,
            modelName = modelName,
            rawSource = "Из описания заявки",
            matchesTicket = null,
            matchNote = "",
            detectedVendor = detectedVendor
        )
    }

    /**
     * Combines an earlier scan (for example, S/N from barcode) with additional barcode/OCR lines
     * (for example, model from plate OCR) and re-evaluates the ticket match.
     */
    fun merge(
        existing: EquipmentScanResult?,
        barcodeValues: List<String>,
        ocrLines: List<String>,
        ticketText: String,
        catalogEntries: List<PrinterCatalogEntry> = PrinterModelCatalog.currentEntries()
    ): EquipmentScanResult? {
        val fresh = parse(barcodeValues, ocrLines, ticketText, catalogEntries)
        if (existing == null) return fresh
        if (fresh == null) return existing

        val mergedSerial = existing.serialNumber.ifBlank { fresh.serialNumber }.let {
            if (it.isNotBlank()) normalizePrinterSerial(it) else ""
        }
        val mergedInv = existing.inventoryNumber.ifBlank { fresh.inventoryNumber }
        val mergedModel = existing.modelName.ifBlank { fresh.modelName }
        val mergedVendor = existing.detectedVendor.ifBlank { fresh.detectedVendor }
        val mergedCandidates = (existing.candidateSerials + fresh.candidateSerials)
            .map { normalizePrinterSerial(it) }
            .filter { it.isNotBlank() }
            .distinct()
        val mergedSkipped = (existing.skippedCodes + fresh.skippedCodes).distinct()
        val (matches, note) = evaluateTicketMatch(
            normalizedTicket = ticketText.lowercase(Locale.ROOT),
            modelName = mergedModel,
            serialNumber = mergedSerial,
            inventoryNumber = mergedInv
        )
        val mergedSource = listOf(existing.rawSource, fresh.rawSource)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" + ")

        return EquipmentScanResult(
            serialNumber = mergedSerial,
            inventoryNumber = mergedInv,
            modelName = mergedModel,
            rawSource = mergedSource.ifBlank { fresh.rawSource },
            matchesTicket = matches,
            matchNote = note,
            candidateSerials = mergedCandidates,
            skippedCodes = mergedSkipped,
            detectedVendor = mergedVendor
        )
    }

    /**
     * Builds a normalized [EquipmentScanResult] from manual engineer entry or quick edits,
     * evaluating match against the ticket description.
     */
    fun buildManualResult(
        modelName: String,
        serialNumber: String,
        inventoryNumber: String,
        ticketText: String,
        rawSource: String = "Ручной ввод",
        detectedVendor: String = ""
    ): EquipmentScanResult? {
        val cleanModel = modelName.trim()
        val cleanSerial = normalizePrinterSerial(serialNumber)
        val cleanInv = inventoryNumber.trim().uppercase(Locale.ROOT)
        if (cleanModel.isEmpty() && cleanSerial.isEmpty() && cleanInv.isEmpty()) return null

        val (matches, note) = evaluateTicketMatch(
            normalizedTicket = ticketText.lowercase(Locale.ROOT),
            modelName = cleanModel,
            serialNumber = cleanSerial,
            inventoryNumber = cleanInv
        )
        return EquipmentScanResult(
            serialNumber = cleanSerial,
            inventoryNumber = cleanInv,
            modelName = cleanModel,
            rawSource = rawSource,
            matchesTicket = matches,
            matchNote = note,
            detectedVendor = detectedVendor
        )
    }

    /**
     * Returns `(normalizedVendor, concreteModel)` where `concreteModel` is NEVER a bare vendor
     * name like "HEWLETT PACKARD" without a real model code.
     */
    private fun extractVendorAndModel(
        line: String,
        catalogEntries: List<PrinterCatalogEntry> = emptyList()
    ): Pair<String?, String?> {
        if (catalogEntries.isNotEmpty()) {
            PrinterModelCatalogEngine.matchModelFromText(catalogEntries, line)?.let { matched ->
                val vendorPrefix = brandRegex.find(matched)?.groupValues?.getOrNull(1)?.let(::normalizeVendorName)
                return vendorPrefix to matched
            }
        }
        val m = brandRegex.find(line) ?: return null to null
        val vendor = normalizeVendorName(m.groupValues[1])
        val tail = m.groupValues[2].trim()
            .split(Regex("\\s+(?:s/n|sn|сер|инв|rev|mac|ip|rmn)\\b", RegexOption.IGNORE_CASE))
            .firstOrNull()
            .orEmpty()
            .trim()
            .take(28)

        if (tail.isNotEmpty() && catalogEntries.isNotEmpty()) {
            PrinterModelCatalogEngine.matchModelFromText(catalogEntries, "$vendor $tail")?.let { matched ->
                return vendor to matched
            }
        }
        val isCorporateWordOnly = tail.equals("inc", true) ||
            tail.equals("company", true) ||
            tail.equals("corp", true) ||
            tail.equals("ltd", true) ||
            tail.equals("gmbh", true)
        val hasModelDigits = tail.any { it.isDigit() }
        val concreteModel = if (!isCorporateWordOnly && hasModelDigits && tail.length >= 2) {
            "$vendor $tail"
        } else {
            null
        }
        return vendor to concreteModel
    }

    private fun evaluateTicketMatch(
        normalizedTicket: String,
        modelName: String,
        serialNumber: String,
        inventoryNumber: String
    ): Pair<Boolean?, String> {
        if (normalizedTicket.isBlank()) {
            return null to "Данные считаны"
        }

        val normTicketSerials = normalizedTicket.uppercase(Locale.ROOT)
            .replace('O', '0')
            .replace('О', '0')
        if (serialNumber.isNotBlank() && normTicketSerials.contains(normalizePrinterSerial(serialNumber))) {
            return true to "Серийный номер совпадает с заявкой"
        }
        if (inventoryNumber.isNotBlank() && normalizedTicket.contains(inventoryNumber.lowercase(Locale.ROOT))) {
            return true to "Инвентарный номер совпадает с заявкой"
        }

        val ticketModel = extractVendorAndModel(normalizedTicket).second.orEmpty()
        if (modelName.isNotBlank()) {
            val scannedTokens = modelName.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.length >= 3 }
            val modelCoreMatch = scannedTokens.size >= 2 &&
                scannedTokens.all { tok -> normalizedTicket.contains(tok) }
            if (modelCoreMatch) {
                return true to "Модель аппарата совпадает с заявкой"
            }
            if (ticketModel.isNotBlank()) {
                return false to "Внимание: в заявке указан другой аппарат ($ticketModel)"
            }
        }

        return null to "Оборудование считано и готово к добавлению в заявку"
    }
}
