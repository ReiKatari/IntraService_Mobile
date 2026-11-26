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
    val matchNote: String = ""
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
 * then cross-checking against the ticket title and description.
 */
object EquipmentScanParser {

    private val brandRegex = Regex(
        "(?iu)\\b(Lexmark|Kyocera|Hewlett[\\s-]?Packard|HP|Ricoh|Xerox|Canon|Brother|Konica\\s+Minolta|Pantum|Epson|Samsung|Sharp|Oki|Toshiba|Katyusha|Катюша)\\b([A-Za-zА-Яа-я0-9\\s/-]{1,28})?"
    )
    private val serialLabelRegex = Regex(
        "(?iu)(?:s\\s*/\\s*n|sn|serial(?:\\s*no\\.?)?|сер\\.?\\s*№?|серийный(?:\\s*номер)?|зав\\.?\\s*№?)\\s*[:#=-]?\\s*([A-Z0-9-]{6,24})"
    )
    private val invLabelRegex = Regex(
        "(?iu)(?:инв\\.?\\s*№\\s*|инв\\.?\\s*[:#=]\\s*|инв\\.?\\s+|inv(?:entory)?(?:\\s*no\\.?)?\\s*[:#=]\\s*|inv(?:entory)?(?:\\s*no\\.?)?\\s+|asset\\s*(?:id|tag)?\\s*[:#=]?\\s*)([A-Z0-9-]{4,20})"
    )
    private val standaloneSerialRegex = Regex("^(?=.*[A-Z])(?=.*\\d)[A-Z0-9-]{8,24}$|^\\d{10,18}$")

    fun parse(
        barcodeValues: List<String>,
        ocrLines: List<String>,
        ticketText: String
    ): EquipmentScanResult? {
        val cleanBarcodes = barcodeValues.map { it.trim() }.filter { it.isNotEmpty() }
        val cleanOcr = ocrLines.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanBarcodes.isEmpty() && cleanOcr.isEmpty()) return null

        var serialNumber = ""
        var inventoryNumber = ""
        var modelName = ""

        // 1. Check barcodes first (highest fidelity for DataMatrix / Code128 / QR)
        for (raw in cleanBarcodes) {
            val upper = raw.uppercase(Locale.ROOT).trim()
            serialLabelRegex.find(upper)?.let { m ->
                if (serialNumber.isEmpty()) serialNumber = m.groupValues[1].trim()
            }
            invLabelRegex.find(upper)?.let { m ->
                if (inventoryNumber.isEmpty()) inventoryNumber = m.groupValues[1].trim()
            }
            if (serialNumber.isEmpty()) {
                val compact = upper.replace(" ", "")
                if (standaloneSerialRegex.matches(compact)) {
                    serialNumber = compact
                }
            }
            if (modelName.isEmpty()) {
                extractModel(raw)?.let { modelName = it }
            }
        }

        // 2. Enrich from label OCR lines
        for (idx in cleanOcr.indices) {
            val line = cleanOcr[idx]
            if (modelName.isEmpty()) {
                extractModel(line)?.let { modelName = it }
            }
            if (serialNumber.isEmpty()) {
                serialLabelRegex.find(line)?.let { m ->
                    serialNumber = m.groupValues[1].uppercase(Locale.ROOT).trim()
                }
                if (serialNumber.isEmpty() && line.contains("s/n", ignoreCase = true) && idx + 1 < cleanOcr.size) {
                    val nextTok = cleanOcr[idx + 1].trim().uppercase(Locale.ROOT).replace(" ", "")
                    if (standaloneSerialRegex.matches(nextTok)) {
                        serialNumber = nextTok
                    }
                }
            }
            if (inventoryNumber.isEmpty()) {
                invLabelRegex.find(line)?.let { m ->
                    inventoryNumber = m.groupValues[1].uppercase(Locale.ROOT).trim()
                }
            }
        }

        // Last fallback if OCR captured an uppercase alphanumeric serial token
        if (serialNumber.isEmpty()) {
            for (line in cleanOcr) {
                line.split(Regex("[\\s,;]+")).forEach { tok ->
                    val up = tok.trim().uppercase(Locale.ROOT)
                    if (standaloneSerialRegex.matches(up) && serialNumber.isEmpty()) {
                        serialNumber = up
                    }
                }
            }
        }

        if (serialNumber.isEmpty() && inventoryNumber.isEmpty() && modelName.isEmpty()) {
            return null
        }

        // 3. Verify against ticket description/assets
        val normalizedTicket = ticketText.lowercase(Locale.ROOT)
        val (matches, note) = evaluateTicketMatch(
            normalizedTicket = normalizedTicket,
            modelName = modelName,
            serialNumber = serialNumber,
            inventoryNumber = inventoryNumber
        )

        val src = when {
            cleanBarcodes.isNotEmpty() && cleanOcr.isNotEmpty() -> "Штрихкод + OCR"
            cleanBarcodes.isNotEmpty() -> "Штрихкод (${cleanBarcodes.first().take(24)})"
            else -> "OCR шильдика"
        }

        return EquipmentScanResult(
            serialNumber = serialNumber,
            inventoryNumber = inventoryNumber,
            modelName = modelName,
            rawSource = src,
            matchesTicket = matches,
            matchNote = note
        )
    }

    private fun extractModel(line: String): String? {
        val m = brandRegex.find(line) ?: return null
        val brand = m.groupValues[1].trim()
        val tail = m.groupValues[2].trim()
            .split(Regex("\\s+(?:s/n|sn|сер|инв|rev|mac|ip)\\b", RegexOption.IGNORE_CASE))
            .firstOrNull()
            .orEmpty()
            .trim()
            .take(24)
        return if (tail.isNotEmpty()) "$brand $tail" else brand
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
