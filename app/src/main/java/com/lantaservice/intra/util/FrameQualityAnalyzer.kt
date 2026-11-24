package com.lantaservice.intra.util

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class LightStatus {
    TOO_DARK,
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
    val primaryHint: String = "Наведите камеру на объект или текст",
    val avgLuma: Double = 128.0,
    val sharpnessVar: Double = 0.0
)

/**
 * Evaluates live camera Y-plane frames every ~180ms to guide the engineer on
 * lighting, focus sharpness, and hand stability before pressing the shutter.
 */
class FrameQualityAnalyzer(
    private val onMetrics: (FrameQualityMetrics) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastAnalyzedMs = 0L
    private var prevSample: ByteArray? = null

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalyzedMs < ANALYSIS_INTERVAL_MS) return
            lastAnalyzedMs = now

            val yPlane = image.planes.firstOrNull() ?: return
            val buffer = yPlane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val (metrics, nextSample) = evaluateFrameQuality(
                luma = bytes,
                width = image.width,
                height = image.height,
                rowStride = yPlane.rowStride,
                prevSample = prevSample
            )
            prevSample = nextSample
            onMetrics(metrics)
        } finally {
            image.close()
        }
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

            // 1. Lighting & glare evaluation over whole frame
            val lumaStep = max(2, min(width, height) / 60)
            var sumLuma = 0L
            var brightCount = 0L
            var totalCount = 0L

            var y = 0
            while (y < height) {
                val rowOff = y * rowStride
                var x = 0
                while (x < width && rowOff + x < luma.size) {
                    val v = luma[rowOff + x].toInt() and 0xFF
                    sumLuma += v
                    if (v > 244) brightCount++
                    totalCount++
                    x += lumaStep
                }
                y += lumaStep
            }
            val avgLuma = if (totalCount > 0L) sumLuma.toDouble() / totalCount else 128.0
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
                avgLuma < 52.0 -> LightStatus.TOO_DARK
                avgLuma > 228.0 || brightRatio > 0.35 -> LightStatus.GLARE
                else -> LightStatus.OK
            }
            val sharpnessStatus = if (lapVar < 60.0) SharpnessStatus.BLURRY else SharpnessStatus.OK
            val stabilityStatus = if (motionDelta > 15.0) StabilityStatus.MOVING else StabilityStatus.STEADY

            val optimal = lightStatus == LightStatus.OK &&
                sharpnessStatus == SharpnessStatus.OK &&
                stabilityStatus == StabilityStatus.STEADY

            val hint = when {
                lightStatus == LightStatus.TOO_DARK ->
                    "Слишком темно - включите фонарик или добавьте света"
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
