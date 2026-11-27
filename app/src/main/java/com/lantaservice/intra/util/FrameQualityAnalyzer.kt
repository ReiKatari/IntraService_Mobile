package com.lantaservice.intra.util

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class LightStatus {
    TOO_DARK,
    LOW_LIGHT,
    OK,
    GLARE
}

enum class SharpnessStatus {
    BLURRY,
    OK
}

enum class StabilityStatus {
    MOVING,
    STEADY
}

data class FrameQualityMetrics(
    val light: LightStatus = LightStatus.OK,
    val sharpness: SharpnessStatus = SharpnessStatus.OK,
    val stability: StabilityStatus = StabilityStatus.STEADY,
    val isOptimalToShoot: Boolean = false,
    val primaryHint: String = "Поместите лист A4 целиком в рамку",
    val avgLuma: Double = 128.0,
    val sharpnessVar: Double = 0.0,
    val detectedQuadNormalized: List<Point2D>? = null,
    val sheetAligned: Boolean = false,
    val analyzedFrameWidth: Int = 0,
    val analyzedFrameHeight: Int = 0
)

/**
 * Evaluates live camera Y-plane frames every ~180ms to guide the engineer on
 * lighting, focus sharpness, hand stability, and live A4 sheet boundary alignment.
 */
class FrameQualityAnalyzer(
    private val onMetrics: (FrameQualityMetrics) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastAnalyzedMs = 0L
    private var prevSample: ByteArray? = null
    private var consecutiveSteadyFrames = 0

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalyzedMs < ANALYSIS_INTERVAL_MS) return
            lastAnalyzedMs = now

            val yPlane = image.planes.firstOrNull() ?: return
            val buffer = yPlane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val (baseMetrics, nextSample) = evaluateFrameQuality(
                luma = bytes,
                width = image.width,
                height = image.height,
                rowStride = yPlane.rowStride,
                prevSample = prevSample
            )
            prevSample = nextSample

            if (baseMetrics.stability == StabilityStatus.STEADY) {
                consecutiveSteadyFrames++
            } else {
                consecutiveSteadyFrames = 0
            }
            val effectiveStability = if (consecutiveSteadyFrames >= 2) {
                StabilityStatus.STEADY
            } else {
                StabilityStatus.MOVING
            }

            // Fast downsampled rotation-corrected Y plane for live A4 sheet contour detection (~112px max)
            val rotDeg = image.imageInfo.rotationDegrees
            val (orientedW, orientedH, smallLuma) = extractOrientedSmallLuma(
                luma = bytes,
                srcW = image.width,
                srcH = image.height,
                rowStride = yPlane.rowStride,
                rotationDegrees = rotDeg,
                maxDim = 112
            )
            val liveQuad = DocumentGeometry.detectPaperQuadFromLuma(smallLuma, orientedW, orientedH)
            val guidance = DocumentGeometry.evaluateLiveSheetGuidance(
                quad = liveQuad,
                frameW = orientedW,
                frameH = orientedH,
                light = baseMetrics.light,
                sharpness = baseMetrics.sharpness,
                stability = effectiveStability
            )
            val trulyOptimal = baseMetrics.light == LightStatus.OK &&
                baseMetrics.sharpness == SharpnessStatus.OK &&
                effectiveStability == StabilityStatus.STEADY &&
                guidance.isSheetAligned

            onMetrics(
                baseMetrics.copy(
                    stability = effectiveStability,
                    detectedQuadNormalized = guidance.normalizedQuad,
                    sheetAligned = guidance.isSheetAligned,
                    analyzedFrameWidth = orientedW,
                    analyzedFrameHeight = orientedH,
                    primaryHint = guidance.hint,
                    isOptimalToShoot = trulyOptimal
                )
            )
        } finally {
            image.close()
        }
    }

    private fun extractOrientedSmallLuma(
        luma: ByteArray,
        srcW: Int,
        srcH: Int,
        rowStride: Int,
        rotationDegrees: Int,
        maxDim: Int
    ): Triple<Int, Int, ByteArray> {
        val normRot = ((rotationDegrees % 360) + 360) % 360
        val swap = normRot == 90 || normRot == 270
        val targetRawW = if (swap) srcH else srcW
        val targetRawH = if (swap) srcW else srcH
        val scale = min(1f, maxDim.toFloat() / max(1, max(targetRawW, targetRawH)).toFloat())
        val outW = (targetRawW * scale).toInt().coerceAtLeast(24)
        val outH = (targetRawH * scale).toInt().coerceAtLeast(24)
        val out = ByteArray(outW * outH)

        for (oy in 0 until outH) {
            val ny = oy.toFloat() / outH.toFloat()
            for (ox in 0 until outW) {
                val nx = ox.toFloat() / outW.toFloat()
                val (sxNorm, syNorm) = when (normRot) {
                    90 -> ny to (1f - nx)
                    180 -> (1f - nx) to (1f - ny)
                    270 -> (1f - ny) to nx
                    else -> nx to ny
                }
                val sx = (sxNorm * srcW).toInt().coerceIn(0, srcW - 1)
                val sy = (syNorm * srcH).toInt().coerceIn(0, srcH - 1)
                val idx = sy * rowStride + sx
                out[oy * outW + ox] = if (idx < luma.size) luma[idx] else 0
            }
        }
        return Triple(outW, outH, out)
    }

    companion object {
        private const val ANALYSIS_INTERVAL_MS = 180L
        private const val SAMPLE_W = 16
        private const val SAMPLE_H = 12

        fun evaluateFrameQuality(
            luma: ByteArray,
            width: Int,
            height: Int,
            rowStride: Int = width,
            prevSample: ByteArray? = null
        ): Pair<FrameQualityMetrics, ByteArray> {
            val emptySample = ByteArray(SAMPLE_W * SAMPLE_H)
            if (width < 16 || height < 16 || luma.isEmpty()) {
                return FrameQualityMetrics() to emptySample
            }

            // 1. Lighting & glare evaluation over whole frame and center document region (20%..80%)
            val lumaStep = max(2, min(width, height) / 60)
            var sumLuma = 0L
            var brightCount = 0L
            var totalCount = 0L
            var centerSumLuma = 0L
            var centerCount = 0L
            val cxStart = width / 5
            val cxEnd = (width * 4) / 5
            val cyStart = height / 5
            val cyEnd = (height * 4) / 5

            var y = 0
            while (y < height) {
                val rowOff = y * rowStride
                var x = 0
                while (x < width && rowOff + x < luma.size) {
                    val v = luma[rowOff + x].toInt() and 0xFF
                    sumLuma += v
                    if (v > 244) brightCount++
                    totalCount++
                    if (x in cxStart..cxEnd && y in cyStart..cyEnd) {
                        centerSumLuma += v
                        centerCount++
                    }
                    x += lumaStep
                }
                y += lumaStep
            }
            val avgLuma = if (totalCount > 0L) sumLuma.toDouble() / totalCount else 128.0
            val centerAvgLuma = if (centerCount > 0L) centerSumLuma.toDouble() / centerCount else avgLuma
            val brightRatio = if (totalCount > 0L) brightCount.toDouble() / totalCount else 0.0

            // 2. Center-region Laplacian variance for focus sharpness
            val x0 = (width / 5).coerceAtLeast(1)
            val x1 = ((width * 4) / 5).coerceAtMost(width - 2)
            val y0 = (height / 5).coerceAtLeast(1)
            val y1 = ((height * 4) / 5).coerceAtMost(height - 2)
            val sharpStep = max(1, min(width, height) / 180)

            var lapSum = 0.0
            var lapSqSum = 0.0
            var lapCount = 0L

            var sy = y0
            while (sy <= y1) {
                val row = sy * rowStride
                if (row + rowStride + x1 >= luma.size || row - rowStride < 0) break
                var sx = x0
                while (sx <= x1) {
                    val idx = row + sx
                    val c = luma[idx].toInt() and 0xFF
                    val l = luma[idx - 1].toInt() and 0xFF
                    val r = luma[idx + 1].toInt() and 0xFF
                    val u = luma[idx - rowStride].toInt() and 0xFF
                    val d = luma[idx + rowStride].toInt() and 0xFF
                    val lap = (4 * c - l - r - u - d).toDouble()
                    lapSum += lap
                    lapSqSum += lap * lap
                    lapCount++
                    sx += sharpStep
                }
                sy += sharpStep
            }
            val lapMean = if (lapCount > 0L) lapSum / lapCount else 0.0
            val lapVar = if (lapCount > 0L) (lapSqSum / lapCount) - (lapMean * lapMean) else 0.0

            // 3. Downsampled 16x12 frame delta for hand motion stability
            val currentSample = ByteArray(SAMPLE_W * SAMPLE_H)
            var motionSum = 0L
            for (gy in 0 until SAMPLE_H) {
                val py = ((gy * height) / SAMPLE_H).coerceIn(0, height - 1)
                val rowOff = py * rowStride
                for (gx in 0 until SAMPLE_W) {
                    val px = ((gx * width) / SAMPLE_W).coerceIn(0, width - 1)
                    val sIdx = gy * SAMPLE_W + gx
                    val valByte = if (rowOff + px < luma.size) luma[rowOff + px] else 0
                    currentSample[sIdx] = valByte
                    if (prevSample != null && prevSample.size == currentSample.size) {
                        motionSum += abs((valByte.toInt() and 0xFF) - (prevSample[sIdx].toInt() and 0xFF))
                    }
                }
            }
            val motionDelta = if (prevSample != null) {
                motionSum.toDouble() / currentSample.size.toDouble()
            } else {
                0.0
            }

            val lightStatus = when {
                avgLuma < 58.0 || centerAvgLuma < 72.0 -> LightStatus.TOO_DARK
                avgLuma > 228.0 || brightRatio > 0.30 -> LightStatus.GLARE
                centerAvgLuma < 124.0 || avgLuma < 85.0 -> LightStatus.LOW_LIGHT
                else -> LightStatus.OK
            }
            val sharpnessStatus = if (lapVar < 165.0) SharpnessStatus.BLURRY else SharpnessStatus.OK
            val stabilityStatus = if (motionDelta > 6.5) StabilityStatus.MOVING else StabilityStatus.STEADY

            val optimal = lightStatus == LightStatus.OK &&
                sharpnessStatus == SharpnessStatus.OK &&
                stabilityStatus == StabilityStatus.STEADY

            val hint = when {
                lightStatus == LightStatus.TOO_DARK ->
                    "Слишком темно - включите фонарик или добавьте света"
                lightStatus == LightStatus.LOW_LIGHT ->
                    "Мало света - включите фонарик или подойдите к источнику света"
                stabilityStatus == StabilityStatus.MOVING ->
                    "Держите телефон неподвижно - кадр может смазаться"
                sharpnessStatus == SharpnessStatus.BLURRY ->
                    "Нет резкости - коснитесь текста для фокуса (15-30 см)"
                lightStatus == LightStatus.GLARE ->
                    "Сильный блик - слегка измените угол съёмки"
                else ->
                    "Отличные условия - можно фотографировать!"
            }

            return FrameQualityMetrics(
                light = lightStatus,
                sharpness = sharpnessStatus,
                stability = stabilityStatus,
                isOptimalToShoot = optimal,
                primaryHint = hint,
                avgLuma = avgLuma,
                sharpnessVar = lapVar
            ) to currentSample
        }
    }
}
