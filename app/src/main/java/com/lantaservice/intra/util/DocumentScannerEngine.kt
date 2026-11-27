package com.lantaservice.intra.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Point2D(
    val x: Float,
    val y: Float
)

data class DocumentQuad(
    val topLeft: Point2D,
    val topRight: Point2D,
    val bottomRight: Point2D,
    val bottomLeft: Point2D
) {
    fun scaled(scaleX: Float, scaleY: Float): DocumentQuad {
        return DocumentQuad(
            topLeft = Point2D(topLeft.x * scaleX, topLeft.y * scaleY),
            topRight = Point2D(topRight.x * scaleX, topRight.y * scaleY),
            bottomRight = Point2D(bottomRight.x * scaleX, bottomRight.y * scaleY),
            bottomLeft = Point2D(bottomLeft.x * scaleX, bottomLeft.y * scaleY)
        )
    }

    fun expandedOutward(fraction: Float, maxW: Float, maxH: Float): DocumentQuad {
        val cx = (topLeft.x + topRight.x + bottomRight.x + bottomLeft.x) / 4f
        val cy = (topLeft.y + topRight.y + bottomRight.y + bottomLeft.y) / 4f
        fun push(p: Point2D): Point2D {
            val nx = (cx + (p.x - cx) * (1f + fraction)).coerceIn(0f, maxW)
            val ny = (cy + (p.y - cy) * (1f + fraction)).coerceIn(0f, maxH)
            return Point2D(nx, ny)
        }
        return DocumentQuad(
            topLeft = push(topLeft),
            topRight = push(topRight),
            bottomRight = push(bottomRight),
            bottomLeft = push(bottomLeft)
        )
    }
}

enum class DocumentQualityIssue {
    NONE,
    TOO_FAR,
    EXTREME_TILT,
    CLIPPED,
    BLURRY_OR_UNREADABLE
}

data class DocumentGeometryReport(
    val quad: DocumentQuad?,
    val areaRatio: Float,
    val minEdgeRatio: Float,
    val minEdgePixels: Float,
    val maxTaperRatio: Float,
    val maxCornerAngleDeviationDeg: Float,
    val canSafelyFlatten: Boolean,
    val issue: DocumentQualityIssue,
    val warningMessage: String?,
    val shouldCompleteBottomToA4: Boolean = false
)

data class TextLineOrientation(
    val angleDeg: Float,
    val alnumCount: Int,
    val boxWidth: Int,
    val boxHeight: Int
)

data class FlatA4CanvasLayout(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val visibleHeight: Int,
    val completedBottomToA4: Boolean
)

data class LiveSheetGuidance(
    val normalizedQuad: List<Point2D>?,
    val isSheetAligned: Boolean,
    val hint: String
)

data class ScannedDocumentResult(
    val flattenedBitmap: Bitmap,
    val wasDeskewed: Boolean,
    val autoRotatedDegrees: Int,
    val needsRetakeWarning: Boolean,
    val warningMessage: String?,
    val sharpnessScore: Double
)

/**
 * Pure JVM document detection, perspective geometry validation, orientation classification,
 * and honest text readability evaluation (zero Android framework dependencies).
 */
object DocumentGeometry {

    private const val A4_RATIO = 1.4142135f
    private const val MIN_AREA_RATIO = 0.22f
    private const val MIN_FLATTENABLE_AREA_RATIO = 0.14f
    private const val MIN_EDGE_PIXELS = 520f
    private const val MIN_FLATTENABLE_EDGE_PIXELS = 380f
    private const val MAX_TAPER_RATIO = 1.72f
    private const val MAX_FLATTENABLE_TAPER_RATIO = 2.15f
    private const val MAX_CORNER_DEVIATION_DEG = 32f
    private const val MIN_DOC_SHARPNESS = 78.0

    /**
     * Maps a normalized point `(0..1, 0..1)` from oriented camera analysis coordinates into
     * view pixels using `FIT_CENTER` scaling so the overlay matches the preview 1:1.
     */
    fun mapNormalizedPointToFitCenter(
        point: Point2D,
        frameW: Int,
        frameH: Int,
        viewW: Float,
        viewH: Float
    ): Point2D {
        if (frameW <= 0 || frameH <= 0 || viewW <= 0f || viewH <= 0f) {
            return Point2D(point.x * viewW, point.y * viewH)
        }
        val scale = min(viewW / frameW.toFloat(), viewH / frameH.toFloat())
        val scaledW = frameW * scale
        val scaledH = frameH * scale
        val offsetX = (viewW - scaledW) * 0.5f
        val offsetY = (viewH - scaledH) * 0.5f
        return Point2D(
            x = offsetX + point.x * scaledW,
            y = offsetY + point.y * scaledH
        )
    }

    fun detectPaperQuadFromArgb(
        pixels: IntArray,
        width: Int,
        height: Int
    ): DocumentQuad? {
        if (width < 24 || height < 24 || pixels.size < width * height) return null
        val n = width * height
        val scores = IntArray(n)
        for (i in 0 until n) {
            val c = pixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val luma = (77 * r + 150 * g + 29 * b) ushr 8
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val chroma = maxC - minC
            scores[i] = (luma - (chroma * 1.15f).roundToInt()).coerceIn(0, 255)
        }
        return detectPaperQuadFromScorePlane(scores, width, height)
    }

    fun detectPaperQuadFromLuma(
        luma: ByteArray,
        width: Int,
        height: Int
    ): DocumentQuad? {
        if (width < 24 || height < 24 || luma.size < width * height) return null
        val scores = IntArray(width * height) { i -> luma[i].toInt() and 0xFF }
        return detectPaperQuadFromScorePlane(scores, width, height)
    }

    private data class QuadEdgeClipFlags(
        val topClipped: Boolean,
        val fullLeftClipped: Boolean,
        val fullRightClipped: Boolean,
        val topLeftRightClipped: Boolean,
        val bottomClipped: Boolean
    ) {
        val sideOrTopClipped: Boolean
            get() = topClipped || fullLeftClipped || fullRightClipped || topLeftRightClipped
    }

    private fun inspectQuadClipFlags(
        quad: DocumentQuad,
        frameW: Int,
        frameH: Int
    ): QuadEdgeClipFlags {
        val marginX = max(1.5f, frameW * 0.015f)
        val marginY = max(1.5f, frameH * 0.015f)
        return QuadEdgeClipFlags(
            topClipped = quad.topLeft.y <= marginY || quad.topRight.y <= marginY,
            fullLeftClipped = quad.topLeft.x <= marginX && quad.bottomLeft.x <= marginX,
            fullRightClipped = quad.topRight.x >= frameW - marginX && quad.bottomRight.x >= frameW - marginX,
            topLeftRightClipped = quad.topLeft.x <= marginX || quad.topRight.x >= frameW - marginX,
            bottomClipped = quad.bottomLeft.y >= frameH - marginY || quad.bottomRight.y >= frameH - marginY
        )
    }

    fun isQuadClippedByFrame(
        quad: DocumentQuad,
        frameW: Int,
        frameH: Int
    ): Boolean {
        if (frameW <= 0 || frameH <= 0) return false
        val flags = inspectQuadClipFlags(quad, frameW, frameH)
        return flags.sideOrTopClipped || flags.bottomClipped
    }

    fun evaluateLiveSheetGuidance(
        quad: DocumentQuad?,
        frameW: Int,
        frameH: Int,
        light: LightStatus,
        sharpness: SharpnessStatus,
        stability: StabilityStatus
    ): LiveSheetGuidance {
        if (quad == null || frameW <= 0 || frameH <= 0) {
            val fallbackHint = when {
                light == LightStatus.TOO_DARK -> "Слишком темно - включите фонарик или добавьте света"
                light == LightStatus.LOW_LIGHT -> "Мало света - включите фонарик или добавьте света"
                light == LightStatus.GLARE -> "Сильный блик - слегка измените угол съёмки"
                stability == StabilityStatus.MOVING -> "Держите телефон неподвижно"
                sharpness == SharpnessStatus.BLURRY -> "Наведите на лист и коснитесь экрана для фокуса"
                else -> "Поместите лист A4 целиком в рамку"
            }
            return LiveSheetGuidance(normalizedQuad = null, isSheetAligned = false, hint = fallbackHint)
        }

        val norm = listOf(
            Point2D((quad.topLeft.x / frameW).coerceIn(0f, 1f), (quad.topLeft.y / frameH).coerceIn(0f, 1f)),
            Point2D((quad.topRight.x / frameW).coerceIn(0f, 1f), (quad.topRight.y / frameH).coerceIn(0f, 1f)),
            Point2D((quad.bottomRight.x / frameW).coerceIn(0f, 1f), (quad.bottomRight.y / frameH).coerceIn(0f, 1f)),
            Point2D((quad.bottomLeft.x / frameW).coerceIn(0f, 1f), (quad.bottomLeft.y / frameH).coerceIn(0f, 1f))
        )

        val frameArea = frameW.toFloat() * frameH.toFloat()
        val areaRatio = (polygonArea(quad) / frameArea).coerceIn(0f, 1f)
        val topLen = dist(quad.topLeft, quad.topRight)
        val rightLen = dist(quad.topRight, quad.bottomRight)
        val bottomLen = dist(quad.bottomRight, quad.bottomLeft)
        val leftLen = dist(quad.bottomLeft, quad.topLeft)
        val avgW = (topLen + bottomLen) * 0.5f
        val avgH = (leftLen + rightLen) * 0.5f
        val visibleAspect = avgH / max(1f, avgW)
        val horizTaper = max(topLen, bottomLen) / max(1f, min(topLen, bottomLen))
        val vertTaper = max(leftLen, rightLen) / max(1f, min(leftLen, rightLen))
        val maxTaper = max(horizTaper, vertTaper)
        val angleDev = max(
            max(
                cornerDeviationDeg(quad.bottomLeft, quad.topLeft, quad.topRight),
                cornerDeviationDeg(quad.topLeft, quad.topRight, quad.bottomRight)
            ),
            max(
                cornerDeviationDeg(quad.topRight, quad.bottomRight, quad.bottomLeft),
                cornerDeviationDeg(quad.bottomRight, quad.bottomLeft, quad.topLeft)
            )
        )
        val clipFlags = inspectQuadClipFlags(quad, frameW, frameH)
        val hardClipped = clipFlags.sideOrTopClipped || (clipFlags.bottomClipped && visibleAspect < 0.55f)

        return when {
            light == LightStatus.TOO_DARK -> LiveSheetGuidance(
                norm, false, "Слишком темно - включите фонарик"
            )
            light == LightStatus.LOW_LIGHT -> LiveSheetGuidance(
                norm, false, "Мало света - включите фонарик или добавьте света"
            )
            light == LightStatus.GLARE -> LiveSheetGuidance(
                norm, false, "Сильный блик - слегка измените угол съёмки"
            )
            hardClipped -> LiveSheetGuidance(
                norm, false, "Лист обрезан краем кадра - отведите камеру дальше"
            )
            maxTaper > 1.32f || angleDev > 16f -> LiveSheetGuidance(
                norm, false, "Выровняйте угол - держите камеру параллельно листу"
            )
            areaRatio < 0.22f -> LiveSheetGuidance(
                norm, false, "Подойдите ближе к листу A4"
            )
            stability == StabilityStatus.MOVING -> LiveSheetGuidance(
                norm, false, "Держите телефон неподвижно"
            )
            sharpness == SharpnessStatus.BLURRY -> LiveSheetGuidance(
                norm, false, "Нет резкости - коснитесь текста для фокуса"
            )
            else -> LiveSheetGuidance(
                norm, true, "Лист ровно в рамке - можно снимать"
            )
        }
    }

    private fun detectPaperQuadFromScorePlane(
        scores: IntArray,
        width: Int,
        height: Int
    ): DocumentQuad? {
        val n = width * height
        val hist = IntArray(256)
        for (i in 0 until n) {
            hist[scores[i]]++
        }

        val otsu = computeOtsuThreshold(hist, n)
        var bgSum = 0L
        var bgCount = 0L
        var fgSum = 0L
        var fgCount = 0L
        for (v in 0..255) {
            val c = hist[v].toLong()
            if (v <= otsu) {
                bgSum += v * c
                bgCount += c
            } else {
                fgSum += v * c
                fgCount += c
            }
        }
        if (bgCount == 0L || fgCount == 0L) return null
        val bgMean = bgSum.toDouble() / bgCount.toDouble()
        val fgMean = fgSum.toDouble() / fgCount.toDouble()
        if (fgMean - bgMean < 24.0 || fgMean < 75.0) return null

        val threshold = max(
            otsu - 6,
            (bgMean + (fgMean - bgMean) * 0.42).toInt()
        ).coerceIn(50, 210)

        val rawMask = BooleanArray(n) { i -> scores[i] >= threshold }
        val closeRadius = if (max(width, height) >= 200) 3 else 2
        val closedMask = morphClose(rawMask, width, height, closeRadius)

        val visited = BooleanArray(n)
        val queue = IntArray(n)
        var bestArea = 0
        var bestMask: BooleanArray? = null

        for (startIdx in 0 until n) {
            if (visited[startIdx] || !closedMask[startIdx]) continue
            var head = 0
            var tail = 0
            queue[tail++] = startIdx
            visited[startIdx] = true

            var area = 0
            var sumX = 0L
            var sumY = 0L

            while (head < tail) {
                val cur = queue[head++]
                area++
                val x = cur % width
                val y = cur / width
                sumX += x
                sumY += y

                if (x + 1 < width) {
                    val nxt = cur + 1
                    if (!visited[nxt] && closedMask[nxt]) {
                        visited[nxt] = true
                        queue[tail++] = nxt
                    }
                }
                if (x - 1 >= 0) {
                    val nxt = cur - 1
                    if (!visited[nxt] && closedMask[nxt]) {
                        visited[nxt] = true
                        queue[tail++] = nxt
                    }
                }
                if (y + 1 < height) {
                    val nxt = cur + width
                    if (!visited[nxt] && closedMask[nxt]) {
                        visited[nxt] = true
                        queue[tail++] = nxt
                    }
                }
                if (y - 1 >= 0) {
                    val nxt = cur - width
                    if (!visited[nxt] && closedMask[nxt]) {
                        visited[nxt] = true
                        queue[tail++] = nxt
                    }
                }
            }

            val areaFrac = area.toFloat() / n.toFloat()
            if (areaFrac < 0.055f || areaFrac > 0.95f) continue
            val cxNorm = (sumX.toFloat() / area.toFloat()) / width.toFloat()
            val cyNorm = (sumY.toFloat() / area.toFloat()) / height.toFloat()
            // Paper center is expected in central region (ignores bright lamps/cups at corners)
            if (cxNorm !in 0.12f..0.88f || cyNorm !in 0.12f..0.88f) continue

            if (area > bestArea) {
                bestArea = area
                val mask = BooleanArray(n)
                for (q in 0 until tail) {
                    mask[queue[q]] = true
                }
                bestMask = mask
            }
        }

        val mask = bestMask ?: return null
        val boundary = ArrayList<Point2D>(bestArea / 8 + 32)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val idx = row + x
                if (!mask[idx]) continue
                val isEdge = x == 0 || x == width - 1 || y == 0 || y == height - 1 ||
                    !mask[idx - 1] || !mask[idx + 1] || !mask[idx - width] || !mask[idx + width]
                if (isEdge) {
                    boundary += Point2D(x.toFloat(), y.toFloat())
                }
            }
        }
        if (boundary.size < 8) return null

        val fullHull = convexHull(boundary)
        if (fullHull.size < 4) return null
        val reducedHull = subsampleHull(fullHull, maxVertices = 28)
        val rawQuad = findMaxAreaQuad(reducedHull) ?: return null
        val ordered = orderQuadCorners(rawQuad)

        val quadArea = polygonArea(ordered)
        val solidity = bestArea.toFloat() / max(1f, quadArea)
        if (solidity < 0.72f) return null

        val avgW = (dist(ordered.topLeft, ordered.topRight) + dist(ordered.bottomLeft, ordered.bottomRight)) * 0.5f
        val avgH = (dist(ordered.topLeft, ordered.bottomLeft) + dist(ordered.topRight, ordered.bottomRight)) * 0.5f
        val aspect = max(avgW, avgH) / max(1f, min(avgW, avgH))
        if (aspect !in 1.0f..2.25f) return null

        return ordered
    }

    private fun morphClose(
        mask: BooleanArray,
        width: Int,
        height: Int,
        radius: Int
    ): BooleanArray {
        val dilated = morphStep(mask, width, height, radius, isDilate = true)
        return morphStep(dilated, width, height, radius, isDilate = false)
    }

    private fun morphStep(
        src: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
        isDilate: Boolean
    ): BooleanArray {
        val n = width * height
        val horiz = BooleanArray(n)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius).coerceAtMost(width - 1)
                var res = !isDilate
                for (kx in x0..x1) {
                    val v = src[row + kx]
                    if (isDilate && v) {
                        res = true
                        break
                    }
                    if (!isDilate && !v) {
                        res = false
                        break
                    }
                }
                horiz[row + x] = res
            }
        }
        val out = BooleanArray(n)
        for (y in 0 until height) {
            val y0 = (y - radius).coerceAtLeast(0)
            val y1 = (y + radius).coerceAtMost(height - 1)
            val row = y * width
            for (x in 0 until width) {
                var res = !isDilate
                for (ky in y0..y1) {
                    val v = horiz[ky * width + x]
                    if (isDilate && v) {
                        res = true
                        break
                    }
                    if (!isDilate && !v) {
                        res = false
                        break
                    }
                }
                out[row + x] = res
            }
        }
        return out
    }

    fun evaluateQuadGeometry(
        quad: DocumentQuad?,
        fullWidth: Int,
        fullHeight: Int,
        bottomEdgeInkRatio: Float? = null
    ): DocumentGeometryReport {
        if (quad == null || fullWidth <= 0 || fullHeight <= 0) {
            return DocumentGeometryReport(
                quad = null,
                areaRatio = 0f,
                minEdgeRatio = 0f,
                minEdgePixels = 0f,
                maxTaperRatio = 1f,
                maxCornerAngleDeviationDeg = 0f,
                canSafelyFlatten = false,
                issue = DocumentQualityIssue.NONE,
                warningMessage = null,
                shouldCompleteBottomToA4 = false
            )
        }

        val frameArea = fullWidth.toFloat() * fullHeight.toFloat()
        val quadArea = polygonArea(quad)
        val areaRatio = (quadArea / frameArea).coerceIn(0f, 1f)

        val topLen = dist(quad.topLeft, quad.topRight)
        val rightLen = dist(quad.topRight, quad.bottomRight)
        val bottomLen = dist(quad.bottomRight, quad.bottomLeft)
        val leftLen = dist(quad.bottomLeft, quad.topLeft)

        val minEdgePx = min(min(topLen, bottomLen), min(leftLen, rightLen))
        val minEdgeRatio = minEdgePx / min(fullWidth, fullHeight).toFloat()

        val horizTaper = max(topLen, bottomLen) / max(1f, min(topLen, bottomLen))
        val vertTaper = max(leftLen, rightLen) / max(1f, min(leftLen, rightLen))
        val maxTaperRatio = max(horizTaper, vertTaper)

        val avgW = (topLen + bottomLen) * 0.5f
        val avgH = (leftLen + rightLen) * 0.5f
        val visibleAspect = avgH / max(1f, avgW)
        val aspect = max(avgW, avgH) / max(1f, min(avgW, avgH))

        val clipFlags = inspectQuadClipFlags(quad, fullWidth, fullHeight)
        val bottomOnlyClipped = clipFlags.bottomClipped && !clipFlags.sideOrTopClipped
        val bottomIsBlank = bottomEdgeInkRatio == null || bottomEdgeInkRatio < 0.015f
        val canCompleteBottom = bottomOnlyClipped &&
            bottomIsBlank &&
            visibleAspect in 0.55f..1.65f &&
            areaRatio >= MIN_FLATTENABLE_AREA_RATIO
        val hardClipped = clipFlags.sideOrTopClipped || (bottomOnlyClipped && !canCompleteBottom)
        val aspectValid = if (canCompleteBottom) true else aspect in 1.02f..2.15f

        val angleDev = max(
            max(
                cornerDeviationDeg(quad.bottomLeft, quad.topLeft, quad.topRight),
                cornerDeviationDeg(quad.topLeft, quad.topRight, quad.bottomRight)
            ),
            max(
                cornerDeviationDeg(quad.topRight, quad.bottomRight, quad.bottomLeft),
                cornerDeviationDeg(quad.bottomRight, quad.bottomLeft, quad.topLeft)
            )
        )

        val tooFar = areaRatio < MIN_AREA_RATIO || minEdgePx < MIN_EDGE_PIXELS
        val extremeTilt = maxTaperRatio > MAX_TAPER_RATIO || angleDev > MAX_CORNER_DEVIATION_DEG

        val canFlatten = !hardClipped &&
            aspectValid &&
            areaRatio >= MIN_FLATTENABLE_AREA_RATIO &&
            minEdgePx >= MIN_FLATTENABLE_EDGE_PIXELS &&
            maxTaperRatio <= MAX_FLATTENABLE_TAPER_RATIO &&
            angleDev <= 45f

        val (issue, warning) = when {
            hardClipped -> DocumentQualityIssue.CLIPPED to
                "Часть листа обрезана краем кадра - отведите камеру чуть дальше и переснимите."
            extremeTilt && areaRatio >= MIN_FLATTENABLE_AREA_RATIO -> DocumentQualityIssue.EXTREME_TILT to
                "Слишком сильный наклон листа - невозможно идеально выровнять без потери чёткости. Держите камеру ровнее над листом и переснимите."
            tooFar -> DocumentQualityIssue.TOO_FAR to
                "Лист снят слишком далеко - текст может быть нечитаемым. Поднесите камеру ближе к листу A4 и переснимите."
            extremeTilt -> DocumentQualityIssue.EXTREME_TILT to
                "Слишком сильный наклон листа - невозможно идеально выровнять без потери чёткости. Держите камеру ровнее над листом и переснимите."
            else -> DocumentQualityIssue.NONE to null
        }

        return DocumentGeometryReport(
            quad = quad,
            areaRatio = areaRatio,
            minEdgeRatio = minEdgeRatio,
            minEdgePixels = minEdgePx,
            maxTaperRatio = maxTaperRatio,
            maxCornerAngleDeviationDeg = angleDev,
            canSafelyFlatten = canFlatten,
            issue = issue,
            warningMessage = warning,
            shouldCompleteBottomToA4 = canCompleteBottom
        )
    }

    fun computeFlatA4Size(
        quad: DocumentQuad,
        maxDimPx: Int = 2100
    ): Pair<Int, Int> {
        val avgW = (dist(quad.topLeft, quad.topRight) + dist(quad.bottomLeft, quad.bottomRight)) * 0.5f
        val avgH = (dist(quad.topLeft, quad.bottomLeft) + dist(quad.topRight, quad.bottomRight)) * 0.5f
        val isPortrait = avgH >= avgW

        val maxObservedEdge = max(
            max(dist(quad.topLeft, quad.topRight), dist(quad.bottomLeft, quad.bottomRight)),
            max(dist(quad.topLeft, quad.bottomLeft), dist(quad.topRight, quad.bottomRight))
        ).coerceAtLeast(600f)

        val targetLong = (maxObservedEdge * 1.15f).roundToInt().coerceIn(900, maxDimPx)
        val targetShort = (targetLong / A4_RATIO).roundToInt().coerceAtLeast(636)

        return if (isPortrait) {
            targetShort to targetLong
        } else {
            targetLong to targetShort
        }
    }

    fun computeFlatA4CanvasLayout(
        quad: DocumentQuad,
        completeBottomToA4: Boolean,
        maxDimPx: Int = 2100
    ): FlatA4CanvasLayout {
        if (!completeBottomToA4) {
            val (w, h) = computeFlatA4Size(quad, maxDimPx)
            return FlatA4CanvasLayout(
                canvasWidth = w,
                canvasHeight = h,
                visibleHeight = h,
                completedBottomToA4 = false
            )
        }
        val avgW = (dist(quad.topLeft, quad.topRight) + dist(quad.bottomLeft, quad.bottomRight)) * 0.5f
        val avgH = (dist(quad.topLeft, quad.bottomLeft) + dist(quad.topRight, quad.bottomRight)) * 0.5f
        val visibleAspect = avgH / max(1f, avgW)
        val rawLong = max(avgW * A4_RATIO, avgH)
        val dstH = (rawLong * 1.12f).roundToInt().coerceIn(1100, maxDimPx)
        val dstW = (dstH / A4_RATIO).roundToInt().coerceAtLeast(778)
        val visibleH = (dstW * visibleAspect).roundToInt().coerceIn((dstH * 0.42f).roundToInt(), dstH)
        return FlatA4CanvasLayout(
            canvasWidth = dstW,
            canvasHeight = dstH,
            visibleHeight = visibleH,
            completedBottomToA4 = visibleH < dstH - 8
        )
    }

    fun resolveRotationFromLineAngles(anglesDeg: List<Float>): Int {
        if (anglesDeg.isEmpty()) return 0
        val sorted = anglesDeg.sorted()
        val median = sorted[sorted.size / 2]
        // Normalize angle into (-180, 180]
        var a = median % 360f
        if (a > 180f) a -= 360f
        if (a <= -180f) a += 360f

        return when {
            a in -35f..35f -> 0
            a in 55f..125f -> 270
            a in -125f..-55f -> 90
            abs(a) >= 145f -> 180
            else -> 0
        }
    }

    /**
     * Weighted orientation classifier using character count + line geometry (width vs height),
     * preventing accidental 90-degree flips caused by vertical columns or table borders.
     */
    fun resolveRotationFromWeightedLines(lines: List<TextLineOrientation>): Int {
        val reliable = lines.filter { line ->
            val w = max(1, line.boxWidth)
            val h = max(1, line.boxHeight)
            line.alnumCount >= 4 && (w >= h * 1.35f || h >= w * 1.35f)
        }
        if (reliable.isEmpty()) return 0

        var c0 = 0
        var c90 = 0
        var c270 = 0
        var c180 = 0
        var n90 = 0
        var n270 = 0
        var n180 = 0

        for (line in reliable) {
            var a = line.angleDeg % 360f
            if (a > 180f) a -= 360f
            if (a <= -180f) a += 360f

            when {
                a in -35f..35f && line.boxWidth > line.boxHeight -> c0 += line.alnumCount
                a in 55f..125f && line.boxHeight > line.boxWidth -> {
                    c270 += line.alnumCount
                    n270++
                }
                a in -125f..-55f && line.boxHeight > line.boxWidth -> {
                    c90 += line.alnumCount
                    n90++
                }
                abs(a) >= 145f && line.boxWidth > line.boxHeight -> {
                    c180 += line.alnumCount
                    n180++
                }
            }
        }

        val maxOther = max(c90, max(c270, c180))
        if (c0 >= 12 && c0 >= (maxOther * 0.65f)) {
            return 0
        }
        val winnerChars = maxOther
        val winnerRot: Int
        val winnerCount: Int
        when (winnerChars) {
            c270 -> {
                winnerRot = 270
                winnerCount = n270
            }
            c90 -> {
                winnerRot = 90
                winnerCount = n90
            }
            else -> {
                winnerRot = 180
                winnerCount = n180
            }
        }
        return if (winnerCount >= 3 && winnerChars >= 18 && winnerChars >= c0 * 2) {
            winnerRot
        } else {
            0
        }
    }

    fun evaluateReadability(
        sharpnessScore: Double,
        darkInkRatio: Float,
        ocrAlnumCount: Int,
        medianLineHeightPx: Float,
        geometryReport: DocumentGeometryReport
    ): Pair<Boolean, String?> {
        if (geometryReport.issue != DocumentQualityIssue.NONE && !geometryReport.warningMessage.isNullOrBlank()) {
            return true to geometryReport.warningMessage
        }
        if (sharpnessScore < MIN_DOC_SHARPNESS) {
            return true to "Текст недостаточно чёткий (смаз или нет фокуса) - необходимо переснять."
        }
        // If page visibly contains printed/handwritten ink, verify OCR can read at least some text
        if (darkInkRatio >= 0.010f) {
            if (ocrAlnumCount < 14) {
                return true to "Текст на листе слишком мелкий или нечитаемый - подойдите ближе, выровняйте лист и переснимите."
            }
            if (medianLineHeightPx in 0.1f..9.5f) {
                return true to "Шрифт на снимке слишком мелкий для разбора текста - поднесите камеру ближе и переснимите."
            }
        }
        return false to null
    }

    private fun computeOtsuThreshold(hist: IntArray, totalPixels: Int): Int {
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()

        var sumB = 0.0
        var wB = 0
        var maxVar = -1.0
        var threshold = 128

        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = totalPixels - wB
            if (wF == 0) break

            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val diff = mB - mF
            val betweenVar = wB.toDouble() * wF.toDouble() * diff * diff
            if (betweenVar > maxVar) {
                maxVar = betweenVar
                threshold = t
            }
        }
        return threshold
    }

    private fun convexHull(points: List<Point2D>): List<Point2D> {
        val sorted = points.sortedWith(compareBy<Point2D> { it.x }.thenBy { it.y })
        if (sorted.size <= 3) return sorted

        val lower = ArrayList<Point2D>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
                lower.removeAt(lower.size - 1)
            }
            lower += p
        }

        val upper = ArrayList<Point2D>()
        for (i in sorted.indices.reversed()) {
            val p = sorted[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
                upper.removeAt(upper.size - 1)
            }
            upper += p
        }

        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        lower.addAll(upper)
        return lower
    }

    private fun subsampleHull(hull: List<Point2D>, maxVertices: Int): List<Point2D> {
        if (hull.size <= maxVertices) return hull
        val res = ArrayList<Point2D>(maxVertices)
        val step = hull.size.toFloat() / maxVertices.toFloat()
        var pos = 0f
        while (res.size < maxVertices && pos.toInt() < hull.size) {
            res += hull[pos.toInt()]
            pos += step
        }
        return res
    }

    private fun findMaxAreaQuad(hull: List<Point2D>): List<Point2D>? {
        val m = hull.size
        if (m < 4) return null
        var bestArea = -1f
        var bestPts: List<Point2D>? = null

        for (i in 0 until m - 3) {
            for (j in i + 1 until m - 2) {
                for (k in j + 1 until m - 1) {
                    val triBase = abs(cross(hull[i], hull[j], hull[k]))
                    for (l in k + 1 until m) {
                        val area2 = triBase + abs(cross(hull[i], hull[k], hull[l]))
                        if (area2 > bestArea) {
                            bestArea = area2
                            bestPts = listOf(hull[i], hull[j], hull[k], hull[l])
                        }
                    }
                }
            }
        }
        return bestPts
    }

    private fun orderQuadCorners(pts: List<Point2D>): DocumentQuad {
        val cx = pts.map { it.x }.average().toFloat()
        val cy = pts.map { it.y }.average().toFloat()
        // Sort clockwise around centroid in screen coordinates (Y grows downwards)
        val cw = pts.sortedBy { p -> atan2((p.y - cy).toDouble(), (p.x - cx).toDouble()) }
        // Rotate list so index 0 is closest to top-left corner (smallest x + y)
        var tlIdx = 0
        var minSum = Float.MAX_VALUE
        cw.forEachIndexed { idx, p ->
            if (p.x + p.y < minSum) {
                minSum = p.x + p.y
                tlIdx = idx
            }
        }
        return DocumentQuad(
            topLeft = cw[tlIdx],
            topRight = cw[(tlIdx + 1) % 4],
            bottomRight = cw[(tlIdx + 2) % 4],
            bottomLeft = cw[(tlIdx + 3) % 4]
        )
    }

    private fun cross(o: Point2D, a: Point2D, b: Point2D): Float {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    }

    private fun dist(a: Point2D, b: Point2D): Float {
        return hypot(a.x - b.x, a.y - b.y)
    }

    private fun polygonArea(q: DocumentQuad): Float {
        val pts = listOf(q.topLeft, q.topRight, q.bottomRight, q.bottomLeft)
        var sum = 0f
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            sum += (a.x * b.y - b.x * a.y)
        }
        return abs(sum) * 0.5f
    }

    private fun cornerDeviationDeg(prev: Point2D, corner: Point2D, next: Point2D): Float {
        val ux = prev.x - corner.x
        val uy = prev.y - corner.y
        val vx = next.x - corner.x
        val vy = next.y - corner.y
        val magU = hypot(ux, uy)
        val magV = hypot(vx, vy)
        if (magU < 1e-3f || magV < 1e-3f) return 90f
        val cosTheta = ((ux * vx + uy * vy) / (magU * magV)).coerceIn(-1f, 1f)
        val deg = Math.toDegrees(acos(cosTheta.toDouble())).toFloat()
        return abs(90f - deg)
    }
}

/**
 * On-device Android document scanner pipeline:
 * 1. Detects A4 paper sheet bounds & evaluates tilt / distance feasibility.
 * 2. Perspective-flattens tilted sheets onto an upright A4 canvas via Matrix.setPolyToPoly.
 * 3. Auto-rotates sideways (90/270) or upside-down (180) pages using ML Kit line orientation.
 * 4. Audits real document legibility & sharpness so blurry/unreadable captures warn for retake.
 */
object DocumentScannerEngine {

    private const val DETECT_MAX_DIM = 320
    private const val OCR_PROBE_MAX_DIM = 960

    suspend fun scanAndFlatten(
        context: Context,
        orientedSource: Bitmap
    ): ScannedDocumentResult = withContext(Dispatchers.Default) {
        val srcW = orientedSource.width
        val srcH = orientedSource.height

        // 1. Fast downsampled quad detection
        val small = downscaleCopy(orientedSource, DETECT_MAX_DIM)
        val smallW = small.width
        val smallH = small.height
        val smallPixels = IntArray(smallW * smallH)
        small.getPixels(smallPixels, 0, smallW, 0, 0, smallW, smallH)
        if (small !== orientedSource) small.recycle()

        val smallQuad = DocumentGeometry.detectPaperQuadFromArgb(smallPixels, smallW, smallH)
        val bottomEdgeInkRatio = smallQuad?.let {
            computeBottomEdgeInkRatio(smallPixels, smallW, smallH, it)
        }
        val fullQuad = smallQuad?.scaled(
            scaleX = srcW.toFloat() / smallW.toFloat(),
            scaleY = srcH.toFloat() / smallH.toFloat()
        )
        val geomReport = DocumentGeometry.evaluateQuadGeometry(fullQuad, srcW, srcH, bottomEdgeInkRatio)

        // 2. Perspective flatten if valid quad detected (seamlessly completing blank bottom to A4 when needed)
        var workingBitmap = orientedSource
        var wasDeskewed = false
        if (fullQuad != null && geomReport.canSafelyFlatten) {
            val layout = DocumentGeometry.computeFlatA4CanvasLayout(
                quad = fullQuad,
                completeBottomToA4 = geomReport.shouldCompleteBottomToA4
            )
            val dstW = layout.canvasWidth
            val dstH = layout.canvasHeight
            val visH = layout.visibleHeight
            val cropQuad = if (layout.completedBottomToA4) {
                DocumentQuad(
                    topLeft = Point2D(
                        fullQuad.topLeft.x.coerceIn(0f, (srcW - 1).toFloat()),
                        fullQuad.topLeft.y.coerceIn(0f, (srcH - 12).toFloat())
                    ),
                    topRight = Point2D(
                        fullQuad.topRight.x.coerceIn(0f, (srcW - 1).toFloat()),
                        fullQuad.topRight.y.coerceIn(0f, (srcH - 12).toFloat())
                    ),
                    bottomRight = Point2D(
                        fullQuad.bottomRight.x.coerceIn(1f, (srcW - 2).toFloat()),
                        min(fullQuad.bottomRight.y, (srcH - 3).toFloat())
                            .coerceAtLeast(fullQuad.topRight.y + 8f)
                    ),
                    bottomLeft = Point2D(
                        fullQuad.bottomLeft.x.coerceIn(1f, (srcW - 2).toFloat()),
                        min(fullQuad.bottomLeft.y, (srcH - 3).toFloat())
                            .coerceAtLeast(fullQuad.topLeft.y + 8f)
                    )
                )
            } else {
                fullQuad.expandedOutward(0.004f, srcW.toFloat(), srcH.toFloat())
            }
            val srcPts = floatArrayOf(
                cropQuad.topLeft.x, cropQuad.topLeft.y,
                cropQuad.topRight.x, cropQuad.topRight.y,
                cropQuad.bottomRight.x, cropQuad.bottomRight.y,
                cropQuad.bottomLeft.x, cropQuad.bottomLeft.y
            )
            val dstPts = floatArrayOf(
                0f, 0f,
                dstW.toFloat(), 0f,
                dstW.toFloat(), visH.toFloat(),
                0f, visH.toFloat()
            )
            val matrix = Matrix()
            if (matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) {
                val warped = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(warped)
                canvas.drawColor(android.graphics.Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(orientedSource, matrix, paint)
                if (layout.completedBottomToA4 && visH < dstH) {
                    fillSeamlessPaperExtension(warped, dstW, dstH, visH)
                }
                workingBitmap = warped
                wasDeskewed = true
            }
        }

        // 3. Auto-rotate only genuinely sideways or upside-down document pages
        var rotatedDegrees = 0
        val initialText = recognizeTextOnScaled(workingBitmap, OCR_PROBE_MAX_DIM)
        val weightedLines = extractWeightedLines(initialText)
        var suggestedRot = DocumentGeometry.resolveRotationFromWeightedLines(weightedLines)

        val initialAlnum = countAlnum(initialText)
        if (suggestedRot == 0 && initialAlnum < 16) {
            // Probe alternate 90/180/270 orientations in case upside-down/sideways Cyrillic was missed
            var bestRot = 0
            var bestScore = initialAlnum
            for (candRot in listOf(90, 180, 270)) {
                val probeBmp = rotateCopy(downscaleCopy(workingBitmap, 720), candRot.toFloat())
                val probeText = recognizeTextOnScaled(probeBmp, 720)
                probeBmp.recycle()
                val probeWeighted = extractWeightedLines(probeText)
                val horzChars = probeWeighted.filter { it.boxWidth > it.boxHeight }.sumOf { it.alnumCount }
                val vertChars = probeWeighted.filter { it.boxHeight > it.boxWidth }.sumOf { it.alnumCount }
                val score = countAlnum(probeText)
                if (horzChars >= (vertChars * 1.5f) &&
                    score >= bestScore + 25 &&
                    score >= (bestScore * 2.5f).toInt()
                ) {
                    bestScore = score
                    bestRot = candRot
                }
            }
            suggestedRot = bestRot
        }

        val finalText: Text?
        if (suggestedRot != 0) {
            val rotated = rotateCopy(workingBitmap, suggestedRot.toFloat())
            if (workingBitmap !== orientedSource) {
                workingBitmap.recycle()
            }
            workingBitmap = rotated
            rotatedDegrees = suggestedRot
            finalText = recognizeTextOnScaled(workingBitmap, OCR_PROBE_MAX_DIM)
        } else {
            finalText = initialText
        }

        // 4. Honest legibility & sharpness audit on the flattened document
        val finalW = workingBitmap.width
        val finalH = workingBitmap.height
        val finalPixels = IntArray(finalW * finalH)
        workingBitmap.getPixels(finalPixels, 0, finalW, 0, 0, finalW, finalH)

        val lumaPlane = IntArray(finalPixels.size)
        var darkInkCount = 0L
        for (i in finalPixels.indices) {
            val c = finalPixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val yVal = (77 * r + 150 * g + 29 * b) ushr 8
            lumaPlane[i] = yVal
            if (yVal < 115) darkInkCount++
        }
        val sharpness = PhotoEnhancerEngine.computeSharpnessScore(lumaPlane, finalW, finalH)
        val darkInkRatio = darkInkCount.toFloat() / max(1, finalPixels.size).toFloat()

        val ocrScaleBack = max(finalW, finalH).toFloat() / max(1f, min(OCR_PROBE_MAX_DIM, max(finalW, finalH)).toFloat())
        val lineHeights = finalText?.textBlocks
            ?.flatMap { it.lines }
            ?.mapNotNull { it.boundingBox?.height()?.toFloat()?.times(ocrScaleBack) }
            ?.sorted()
            .orEmpty()
        val medianLineH = if (lineHeights.isNotEmpty()) lineHeights[lineHeights.size / 2] else 0f
        val ocrAlnum = countAlnum(finalText)

        val (needsRetake, warningMsg) = DocumentGeometry.evaluateReadability(
            sharpnessScore = sharpness,
            darkInkRatio = darkInkRatio,
            ocrAlnumCount = ocrAlnum,
            medianLineHeightPx = medianLineH,
            geometryReport = geomReport
        )

        ScannedDocumentResult(
            flattenedBitmap = workingBitmap,
            wasDeskewed = wasDeskewed,
            autoRotatedDegrees = rotatedDegrees,
            needsRetakeWarning = needsRetake,
            warningMessage = warningMsg,
            sharpnessScore = sharpness
        )
    }

    private fun extractWeightedLines(text: Text?): List<TextLineOrientation> {
        if (text == null) return emptyList()
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            var alnum = 0
            for (ch in line.text) {
                if (ch.isLetterOrDigit()) alnum++
            }
            if (alnum == 0) return@mapNotNull null
            TextLineOrientation(
                angleDeg = line.angle,
                alnumCount = alnum,
                boxWidth = box.width(),
                boxHeight = box.height()
            )
        }
    }

    private fun computeBottomEdgeInkRatio(
        pixels: IntArray,
        width: Int,
        height: Int,
        quad: DocumentQuad
    ): Float {
        if (width < 8 || height < 8 || pixels.size < width * height) return 0f
        fun argbY(c: Int): Int {
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            return (77 * r + 150 * g + 29 * b) ushr 8
        }
        fun sampleQuadPoint(u: Float, v: Float): Int {
            val lx = quad.topLeft.x + (quad.bottomLeft.x - quad.topLeft.x) * v
            val ly = quad.topLeft.y + (quad.bottomLeft.y - quad.topLeft.y) * v
            val rx = quad.topRight.x + (quad.bottomRight.x - quad.topRight.x) * v
            val ry = quad.topRight.y + (quad.bottomRight.y - quad.topRight.y) * v
            val px = (lx + (rx - lx) * u).roundToInt().coerceIn(0, width - 1)
            val py = (ly + (ry - ly) * u).roundToInt().coerceIn(0, height - 1)
            return argbY(pixels[py * width + px])
        }

        val interiorSamples = ArrayList<Int>(140)
        for (vi in 1..10) {
            val v = 0.08f + vi * 0.08f
            for (ui in 1..12) {
                val u = 0.06f + ui * 0.073f
                interiorSamples += sampleQuadPoint(u, v)
            }
        }
        if (interiorSamples.isEmpty()) return 0f
        interiorSamples.sort()
        val paperMedian = interiorSamples[interiorSamples.size / 2]

        var darkCount = 0
        var total = 0
        for (vi in 0..4) {
            val v = 0.92f + vi * 0.0175f
            for (ui in 0..24) {
                val u = 0.04f + ui * 0.038f
                val yVal = sampleQuadPoint(u, v)
                if (yVal < paperMedian - 36) {
                    darkCount++
                }
                total++
            }
        }
        return if (total > 0) darkCount.toFloat() / total.toFloat() else 0f
    }

    private fun fillSeamlessPaperExtension(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        visibleHeight: Int
    ) {
        val sampleY0 = (visibleHeight - 16).coerceIn(0, visibleHeight - 1)
        val sampleY1 = (visibleHeight - 3).coerceIn(sampleY0 + 1, visibleHeight)
        val rVals = ArrayList<Int>(128)
        val gVals = ArrayList<Int>(128)
        val bVals = ArrayList<Int>(128)
        val xStart = (width * 0.06f).roundToInt().coerceIn(0, width - 1)
        val xEnd = (width * 0.94f).roundToInt().coerceIn(xStart + 1, width)
        val step = max(1, (xEnd - xStart) / 48)

        for (y in sampleY0 until sampleY1) {
            var x = xStart
            while (x < xEnd) {
                val c = bitmap.getPixel(x, y)
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                if ((77 * r + 150 * g + 29 * b) ushr 8 >= 80) {
                    rVals += r
                    gVals += g
                    bVals += b
                }
                x += step
            }
        }
        val baseR: Int
        val baseG: Int
        val baseB: Int
        if (rVals.isNotEmpty()) {
            rVals.sort(); gVals.sort(); bVals.sort()
            baseR = rVals[rVals.size / 2]
            baseG = gVals[gVals.size / 2]
            baseB = bVals[bVals.size / 2]
        } else {
            baseR = 232; baseG = 232; baseB = 232
        }

        val startFillY = (visibleHeight - 2).coerceIn(0, height - 1)
        val extRows = (height - startFillY).coerceAtLeast(1)
        val rowPixels = IntArray(width)
        val fadeRows = min(40, extRows)
        for (dy in 0 until extRows) {
            val y = startFillY + dy
            val targetR = min(255, baseR + 12)
            val targetG = min(255, baseG + 12)
            val targetB = min(255, baseB + 12)
            val t = if (fadeRows > 0) (dy.toFloat() / fadeRows.toFloat()).coerceIn(0f, 1f) else 1f
            val r = (baseR + (targetR - baseR) * t).roundToInt().coerceIn(0, 255)
            val g = (baseG + (targetG - baseG) * t).roundToInt().coerceIn(0, 255)
            val b = (baseB + (targetB - baseB) * t).roundToInt().coerceIn(0, 255)
            val col = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            rowPixels.fill(col)
            bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1)
        }
    }

    private fun countAlnum(text: Text?): Int {
        if (text == null) return 0
        var count = 0
        for (ch in text.text) {
            if (ch.isLetterOrDigit()) count++
        }
        return count
    }

    private suspend fun recognizeTextOnScaled(bitmap: Bitmap, maxDim: Int): Text? {
        val scaled = downscaleCopy(bitmap, maxDim)
        val inputImage = runCatching { InputImage.fromBitmap(scaled, 0) }.getOrNull()
        if (inputImage == null) {
            if (scaled !== bitmap) scaled.recycle()
            return null
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { res -> if (cont.isActive) cont.resume(res) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun downscaleCopy(src: Bitmap, maxDim: Int): Bitmap {
        val largest = max(src.width, src.height)
        if (largest <= maxDim) return src
        val scale = maxDim.toFloat() / largest.toFloat()
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun rotateCopy(src: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return src
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}
