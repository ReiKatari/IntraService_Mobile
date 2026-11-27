package com.lantaservice.intra.util

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.hypot
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
    SOFT,
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
    val motionDelta: Double = 0.0,
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
    private var consecutiveSharpFrames = 0

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalyzedMs < ANALYSIS_INTERVAL_MS) return
            lastAnalyzedMs = now

            val yPlane = image.planes.firstOrNull() ?: return
            val buffer = yPlane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

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
            val paperCoreRoi = liveQuad?.let {
                computeRawPaperCoreRoi(it, orientedW, orientedH, rotDeg)
            }

            val (baseMetrics, nextSample) = evaluateFrameQuality(
                luma = bytes,
                width = image.width,
                height = image.height,
                rowStride = yPlane.rowStride,
                prevSample = prevSample,
                paperRoiNormalized = paperCoreRoi
            )
            prevSample = nextSample

            val motionDelta = baseMetrics.motionDelta
            val steadyThreshold = if (consecutiveSteadyFrames >= 3) 5.5 else 4.2
            if (motionDelta <= steadyThreshold) {
                consecutiveSteadyFrames++
            } else {
                consecutiveSteadyFrames = 0
            }
            val effectiveStability = if (consecutiveSteadyFrames >= 3) {
                StabilityStatus.STEADY
            } else {
                StabilityStatus.MOVING
            }

            if (baseMetrics.sharpness == SharpnessStatus.OK && effectiveStability == StabilityStatus.STEADY) {
                consecutiveSharpFrames++
            } else {
                consecutiveSharpFrames = 0
            }
            val effectiveSharpness = when {
                motionDelta > 9.0 || baseMetrics.sharpness == SharpnessStatus.BLURRY -> SharpnessStatus.BLURRY
                consecutiveSharpFrames >= 2 -> SharpnessStatus.OK
                else -> SharpnessStatus.SOFT
            }

            val guidance = DocumentGeometry.evaluateLiveSheetGuidance(
                quad = liveQuad,
                frameW = orientedW,
                frameH = orientedH,
                light = baseMetrics.light,
                sharpness = effectiveSharpness,
                stability = effectiveStability
            )
            val trulyOptimal = baseMetrics.light == LightStatus.OK &&
                effectiveSharpness == SharpnessStatus.OK &&
                effectiveStability == StabilityStatus.STEADY &&
                guidance.isSheetAligned

            onMetrics(
                baseMetrics.copy(
                    sharpness = effectiveSharpness,
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

    private fun computeRawPaperCoreRoi(
        quad: DocumentQuad,
        orientedW: Int,
        orientedH: Int,
        rotationDegrees: Int
    ): NormalizedRect? {
        val normRot = ((rotationDegrees % 360) + 360) % 360
        fun mapCornerToRaw(p: Point2D): Point2D {
            val nx = (p.x / max(1, orientedW).toFloat()).coerceIn(0f, 1f)
            val ny = (p.y / max(1, orientedH).toFloat()).coerceIn(0f, 1f)
            return when (normRot) {
                90 -> Point2D(ny, 1f - nx)
                180 -> Point2D(1f - nx, 1f - ny)
                270 -> Point2D(1f - ny, nx)
                else -> Point2D(nx, ny)
            }
        }
        val pts = listOf(
            mapCornerToRaw(quad.topLeft),
            mapCornerToRaw(quad.topRight),
            mapCornerToRaw(quad.bottomRight),
            mapCornerToRaw(quad.bottomLeft)
        )
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val spanW = maxX - minX
        val spanH = maxY - minY
        if (spanW < 0.18f || spanH < 0.18f) return null
        val insetX = spanW * 0.10f
        val insetY = spanH * 0.10f
        val l = (minX + insetX).coerceIn(0f, 0.85f)
        val t = (minY + insetY).coerceIn(0f, 0.85f)
        val r = (maxX - insetX).coerceIn(l + 0.05f, 1f)
        val b = (maxY - insetY).coerceIn(t + 0.05f, 1f)
        if (r - l < 0.15f || b - t < 0.15f) return null
        return NormalizedRect(l, t, r, b)
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
        private const val SAMPLE_W = 20
        private const val SAMPLE_H = 15

        fun evaluateFrameQuality(
            luma: ByteArray,
            width: Int,
            height: Int,
            rowStride: Int = width,
            prevSample: ByteArray? = null,
            paperRoiNormalized: NormalizedRect? = null
        ): Pair<FrameQualityMetrics, ByteArray> {
            val emptySample = ByteArray(SAMPLE_W * SAMPLE_H)
            if (width < 16 || height < 16 || luma.isEmpty()) {
                return FrameQualityMetrics() to emptySample
            }

            // 1. Lighting & glare evaluation over whole frame and interior paper/document ROI
            val lumaStep = max(2, min(width, height) / 60)
            var sumLuma = 0L
            var brightCount = 0L
            var totalCount = 0L
            var centerSumLuma = 0L
            var roiBrightCount = 0L
            val roiSamples = ArrayList<Int>(400)

            val cxStart = if (paperRoiNormalized != null) {
                (paperRoiNormalized.left * width).toInt().coerceIn(0, width - 1)
            } else {
                width / 5
            }
            val cxEnd = if (paperRoiNormalized != null) {
                (paperRoiNormalized.right * width).toInt().coerceIn(cxStart + 1, width - 1)
            } else {
                (width * 4) / 5
            }
            val cyStart = if (paperRoiNormalized != null) {
                (paperRoiNormalized.top * height).toInt().coerceIn(0, height - 1)
            } else {
                height / 5
            }
            val cyEnd = if (paperRoiNormalized != null) {
                (paperRoiNormalized.bottom * height).toInt().coerceIn(cyStart + 1, height - 1)
            } else {
                (height * 4) / 5
            }

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
                        if (v >= 246) roiBrightCount++
                        roiSamples += v
                    }
                    x += lumaStep
                }
                y += lumaStep
            }
            val avgLuma = if (totalCount > 0L) sumLuma.toDouble() / totalCount else 128.0
            val centerAvgLuma = if (roiSamples.isNotEmpty()) {
                centerSumLuma.toDouble() / roiSamples.size.toDouble()
            } else {
                avgLuma
            }
            val brightRatio = if (totalCount > 0L) brightCount.toDouble() / totalCount else 0.0
            val roiBrightRatio = if (roiSamples.isNotEmpty()) {
                roiBrightCount.toDouble() / roiSamples.size.toDouble()
            } else {
                brightRatio
            }
            roiSamples.sort()
            val paperMedian = if (roiSamples.isNotEmpty()) {
                roiSamples[roiSamples.size / 2].toDouble()
            } else {
                centerAvgLuma
            }
            val paperP80 = if (roiSamples.isNotEmpty()) {
                roiSamples[(roiSamples.size * 4) / 5].toDouble()
            } else {
                centerAvgLuma
            }

            // 2. Illumination-invariant block-averaged 20x15 grid for hand motion stability
            val currentSample = ByteArray(SAMPLE_W * SAMPLE_H)
            for (gy in 0 until SAMPLE_H) {
                val py = ((gy * height) / SAMPLE_H).coerceIn(0, height - 1)
                for (gx in 0 until SAMPLE_W) {
                    val px = ((gx * width) / SAMPLE_W).coerceIn(0, width - 1)
                    var patchSum = 0
                    var patchCnt = 0
                    for (dy in -1..1) {
                        val yy = (py + dy).coerceIn(0, height - 1)
                        val rOff = yy * rowStride
                        for (dx in -1..1) {
                            val xx = (px + dx).coerceIn(0, width - 1)
                            val idx = rOff + xx
                            if (idx < luma.size) {
                                patchSum += luma[idx].toInt() and 0xFF
                                patchCnt++
                            }
                        }
                    }
                    val patchAvg = if (patchCnt > 0) patchSum / patchCnt else 0
                    currentSample[gy * SAMPLE_W + gx] = patchAvg.toByte()
                }
            }
            val motionDelta = if (prevSample != null && prevSample.size == currentSample.size) {
                var curSum = 0.0
                var prevSum = 0.0
                for (i in currentSample.indices) {
                    curSum += currentSample[i].toInt() and 0xFF
                    prevSum += prevSample[i].toInt() and 0xFF
                }
                val curMean = curSum / currentSample.size.toDouble()
                val prevMean = prevSum / currentSample.size.toDouble()
                var centeredDiffSum = 0.0
                for (i in currentSample.indices) {
                    val curZeroMean = (currentSample[i].toInt() and 0xFF) - curMean
                    val prevZeroMean = (prevSample[i].toInt() and 0xFF) - prevMean
                    centeredDiffSum += abs(curZeroMean - prevZeroMean)
                }
                val centeredMad = centeredDiffSum / currentSample.size.toDouble()
                val brightnessJump = abs(curMean - prevMean)
                centeredMad + (if (brightnessJump > 18.0) (brightnessJump - 18.0) * 0.5 else 0.0)
            } else {
                0.0
            }

            // 3. Noise-immune Gaussian Laplacian + multi-scale edge crispness over interior ROI
            val sx0 = if (paperRoiNormalized != null) {
                (paperRoiNormalized.left * width).toInt().coerceIn(1, (width - 6).coerceAtLeast(1))
            } else {
                (width * 22 / 100).coerceAtLeast(1)
            }
            val sx1 = if (paperRoiNormalized != null) {
                (paperRoiNormalized.right * width).toInt().coerceIn((sx0 + 4).coerceAtMost(width - 2), width - 2)
            } else {
                (width * 78 / 100).coerceAtMost(width - 2)
            }
            val sy0 = if (paperRoiNormalized != null) {
                (paperRoiNormalized.top * height).toInt().coerceIn(1, (height - 6).coerceAtLeast(1))
            } else {
                (height * 22 / 100).coerceAtLeast(1)
            }
            val sy1 = if (paperRoiNormalized != null) {
                (paperRoiNormalized.bottom * height).toInt().coerceIn((sy0 + 4).coerceAtMost(height - 2), height - 2)
            } else {
                (height * 78 / 100).coerceAtMost(height - 2)
            }

            val roiW = (sx1 - sx0 + 1).coerceAtLeast(1)
            val roiH = (sy1 - sy0 + 1).coerceAtLeast(1)
            val sharpStep = max(1, max(roiW, roiH) / 220)
            val gw = ((roiW + sharpStep - 1) / sharpStep).coerceAtLeast(6)
            val gh = ((roiH + sharpStep - 1) / sharpStep).coerceAtLeast(6)
            val rawGrid = FloatArray(gw * gh)

            for (gy in 0 until gh) {
                val py = (sy0 + gy * sharpStep).coerceIn(0, height - 1)
                val rowOff = py * rowStride
                val gOff = gy * gw
                for (gx in 0 until gw) {
                    val px = (sx0 + gx * sharpStep).coerceIn(0, width - 1)
                    val idx = rowOff + px
                    rawGrid[gOff + gx] = if (idx < luma.size) (luma[idx].toInt() and 0xFF).toFloat() else 0f
                }
            }

            // 3x3 binomial filter [1 2 1; 2 4 2; 1 2 1] / 16 suppresses sensor ISO grain
            val smooth = rawGrid.copyOf()
            for (gy in 1 until (gh - 1)) {
                val rPrev = (gy - 1) * gw
                val rCur = gy * gw
                val rNext = (gy + 1) * gw
                for (gx in 1 until (gw - 1)) {
                    smooth[rCur + gx] = (
                        rawGrid[rPrev + gx - 1] + 2f * rawGrid[rPrev + gx] + rawGrid[rPrev + gx + 1] +
                            2f * rawGrid[rCur + gx - 1] + 4f * rawGrid[rCur + gx] + 2f * rawGrid[rCur + gx + 1] +
                            rawGrid[rNext + gx - 1] + 2f * rawGrid[rNext + gx] + rawGrid[rNext + gx + 1]
                        ) / 16f
                }
            }

            var lapSum = 0.0
            var lapSqSum = 0.0
            var lapCount = 0L
            for (gy in 1 until (gh - 1)) {
                val rCur = gy * gw
                for (gx in 1 until (gw - 1)) {
                    val idx = rCur + gx
                    val lap = (4f * smooth[idx] -
                        smooth[idx - 1] - smooth[idx + 1] -
                        smooth[idx - gw] - smooth[idx + gw]).toDouble()
                    lapSum += lap
                    lapSqSum += lap * lap
                    lapCount++
                }
            }
            val lapMean = if (lapCount > 0L) lapSum / lapCount else 0.0
            val smoothLapVar = if (lapCount > 0L) (lapSqSum / lapCount) - (lapMean * lapMean) else 0.0

            val wideSpan = if (min(gw, gh) >= 48) 3 else 2
            val ratios = ArrayList<Float>(1024)
            for (gy in wideSpan until (gh - wideSpan)) {
                val rCur = gy * gw
                val rUp1 = (gy - 1) * gw
                val rDn1 = (gy + 1) * gw
                val rUpW = (gy - wideSpan) * gw
                val rDnW = (gy + wideSpan) * gw
                for (gx in wideSpan until (gw - wideSpan)) {
                    val gwX = abs(smooth[rCur + gx + wideSpan] - smooth[rCur + gx - wideSpan])
                    val gwY = abs(smooth[rDnW + gx] - smooth[rUpW + gx])
                    val gradWide = hypot(gwX, gwY)
                    if (gradWide >= 22f) {
                        val gfX = abs(smooth[rCur + gx + 1] - smooth[rCur + gx - 1])
                        val gfY = abs(smooth[rDn1 + gx] - smooth[rUp1 + gx])
                        val gradFine = hypot(gfX, gfY)
                        ratios += min(1.25f, gradFine / gradWide)
                    }
                }
            }
            val minEdges = min(25, max(8, (gw * gh) / 45))
            val edgeCrispnessP75 = if (ratios.size >= minEdges) {
                ratios.sort()
                ratios[(ratios.size * 3) / 4]
            } else {
                0f
            }

            val lightStatus = when {
                avgLuma < 58.0 || centerAvgLuma < 72.0 || paperMedian < 78.0 -> LightStatus.TOO_DARK
                avgLuma > 228.0 || brightRatio > 0.30 || roiBrightRatio > 0.32 -> LightStatus.GLARE
                (paperRoiNormalized != null && paperP80 < 155.0) ||
                    centerAvgLuma < 124.0 ||
                    (avgLuma < 110.0 && paperP80 < 152.0) -> LightStatus.LOW_LIGHT
                else -> LightStatus.OK
            }
            val stabilityStatus = if (motionDelta > 5.0) StabilityStatus.MOVING else StabilityStatus.STEADY
            val sharpnessStatus = when {
                motionDelta > 9.0 -> SharpnessStatus.BLURRY
                smoothLapVar >= 46.0 && edgeCrispnessP75 >= 0.76f -> SharpnessStatus.OK
                smoothLapVar >= 24.0 && edgeCrispnessP75 >= 0.68f -> SharpnessStatus.SOFT
                else -> SharpnessStatus.BLURRY
            }

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
                    "Кадр размыт - удерживайте телефон ровно и нажмите на текст"
                sharpnessStatus == SharpnessStatus.SOFT ->
                    "Слабая резкость - коснитесь текста для фокуса (15-30 см)"
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
                sharpnessVar = smoothLapVar,
                motionDelta = motionDelta
            ) to currentSample
        }
    }
}
