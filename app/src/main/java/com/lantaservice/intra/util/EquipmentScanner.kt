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
    val skippedCodes: List<String> = emptyList()
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

    // OEM label revision / FRU part codes (e.g. 36S2890-004, 41X0252-001) & non-serial sticker metadata
    private val oemLabelRevisionRegex = Regex("^\\d{2}[A-Z]\\d{4}-\\d{3}$|^[A-Z0-9]{4,12}-0\\d{2}$")
    private val macAddressRegex = Regex("^([0-9A-F]{2}[:-]){5}[0-9A-F]{2}$")
    private val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val imdaApprovalRegex = Regex("^DA\\d{5,8}$")
    private val regulatoryLineRegex = Regex(
        "(?iu)\\b(?:imda|standards?|complies|fcc\\s*id|cmiit|anatel|rohs|ic\\s*:|eac|part\\s*no|p/?n|fru|rev)\\b"
    )
    private val partNumberPrefixRegex = Regex("(?iu)^(?:p/?n|pn|fru|part|rev)[:#=-].*$")

    fun isNonSerialLabelCode(
        code: String,
        currentLine: String = "",
        previousLine: String = ""
    ): Boolean {
        val up = code.trim().uppercase(Locale.ROOT)
        if (up.isEmpty()) return true
        if (up.startsWith("HTTP://") || up.startsWith("HTTPS://") || up.startsWith("WWW.")) return true
        if (macAddressRegex.matches(up) || ipv4Regex.matches(up)) return true
        if (oemLabelRevisionRegex.matches(up)) return true
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
        val explicitSerials = mutableListOf<String>()
        val barcodeSerials = mutableListOf<String>()
        val ocrFallbackSerials = mutableListOf<String>()
        val skippedCodes = mutableListOf<String>()
        val acceptedSources = mutableListOf<String>()

        // 1. Check barcodes first (ignoring OEM label part numbers like 36S2890-004)
        for (raw in cleanBarcodes) {
            val upper = raw.uppercase(Locale.ROOT).trim()
            var barcodeMatchedSerial = false
            serialLabelRegex.find(upper)?.let { m ->
                val sn = m.groupValues[1].trim()
                if (!isNonSerialLabelCode(sn, raw)) {
                    explicitSerials += sn
                    barcodeMatchedSerial = true
                    acceptedSources += raw.take(24)
                } else {
                    skippedCodes += sn
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
                    barcodeSerials += compact
                    acceptedSources += raw.take(24)
                }
            }
            if (modelName.isEmpty()) {
                extractModel(raw, catalogEntries)?.let { modelName = it }
            }
        }

        // 2. Enrich from label OCR lines
        for (idx in cleanOcr.indices) {
            val line = cleanOcr[idx]
            val prevLine = if (idx > 0) cleanOcr[idx - 1] else ""
            if (modelName.isEmpty()) {
                extractModel(line, catalogEntries)?.let { modelName = it }
            }
            serialLabelRegex.find(line)?.let { m ->
                val sn = m.groupValues[1].uppercase(Locale.ROOT).trim()
                if (!isNonSerialLabelCode(sn, line, prevLine)) {
                    explicitSerials += sn
                } else {
                    skippedCodes += sn
                }
            }
            if (line.contains("s/n", ignoreCase = true) && idx + 1 < cleanOcr.size) {
                val nextLine = cleanOcr[idx + 1].trim()
                val nextTok = nextLine.uppercase(Locale.ROOT).replace(" ", "")
                if (standaloneSerialRegex.matches(nextTok) && !isNonSerialLabelCode(nextTok, nextLine, line)) {
                    explicitSerials += nextTok
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
            modelName = PrinterModelCatalogEngine.matchModelFromText(catalogEntries, combinedText).orEmpty()
        }

        // 3. Fallback OCR standalone alphanumeric tokens (filtering out regulatory/label part numbers & model tokens)
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
                    ocrFallbackSerials += up
                }
            }
        }

        val allCandidates = (explicitSerials + barcodeSerials + ocrFallbackSerials)
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
        val serialNumber = allCandidates.firstOrNull().orEmpty()

        if (serialNumber.isEmpty() && inventoryNumber.isEmpty() && modelName.isEmpty()) {
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
            skippedCodes = skippedCodes.distinct()
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
        for (line in lines) {
            if (modelName.isEmpty()) {
                extractModel(line, catalogEntries)?.let { modelName = it }
            }
            if (serialNumber.isEmpty()) {
                serialLabelRegex.find(line)?.let { m ->
                    val sn = m.groupValues[1].uppercase(Locale.ROOT).trim()
                    if (!isNonSerialLabelCode(sn, line)) {
                        serialNumber = sn
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
            matchNote = ""
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

        val mergedSerial = existing.serialNumber.ifBlank { fresh.serialNumber }
        val mergedInv = existing.inventoryNumber.ifBlank { fresh.inventoryNumber }
        val mergedModel = existing.modelName.ifBlank { fresh.modelName }
        val mergedCandidates = (existing.candidateSerials + fresh.candidateSerials).distinct()
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
            skippedCodes = mergedSkipped
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
        rawSource: String = "Ручной ввод"
    ): EquipmentScanResult? {
        val cleanModel = modelName.trim()
        val cleanSerial = serialNumber.trim().uppercase(Locale.ROOT)
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
            matchNote = note
        )
    }

    private fun extractModel(
        line: String,
        catalogEntries: List<PrinterCatalogEntry> = emptyList()
    ): String? {
        val m = brandRegex.find(line)
        if (m != null) {
            val brand = m.groupValues[1].trim()
            val tail = m.groupValues[2].trim()
                .split(Regex("\\s+(?:s/n|sn|сер|инв|rev|mac|ip)\\b", RegexOption.IGNORE_CASE))
                .firstOrNull()
                .orEmpty()
                .trim()
                .take(28)
            val rawCandidate = if (tail.isNotEmpty()) "$brand $tail" else brand
            if (catalogEntries.isNotEmpty()) {
                PrinterModelCatalogEngine.matchModelFromText(catalogEntries, rawCandidate)?.let { return it }
            }
            return rawCandidate
        }
        if (catalogEntries.isNotEmpty()) {
            return PrinterModelCatalogEngine.matchModelFromText(catalogEntries, line)
        }
        return null
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

        if (serialNumber.isNotBlank() && normalizedTicket.contains(serialNumber.lowercase(Locale.ROOT))) {
            return true to "Серийный номер совпадает с заявкой"
        }
        if (inventoryNumber.isNotBlank() && normalizedTicket.contains(inventoryNumber.lowercase(Locale.ROOT))) {
            return true to "Инвентарный номер совпадает с заявкой"
        }

        val ticketModel = extractModel(normalizedTicket).orEmpty()
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
