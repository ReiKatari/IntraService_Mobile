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
    val warningMessage: String?
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
    private const val MIN_DOC_SHARPNESS = 46.0

    fun detectPaperQuadFromArgb(
        pixels: IntArray,
        width: Int,
        height: Int
    ): DocumentQuad? {
        if (width < 24 || height < 24 || pixels.size < width * height) return null
        val n = width * height
        val scores = IntArray(n)
        val hist = IntArray(256)

        for (i in 0 until n) {
            val c = pixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val luma = (77 * r + 150 * g + 29 * b) ushr 8
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val chroma = maxC - minC
            val s = (luma - (chroma * 1.35f).roundToInt()).coerceIn(0, 255)
            scores[i] = s
            hist[s]++
        }

        val otsu = computeOtsuThreshold(hist, n)
        val threshold = max(otsu, 108)

        val visited = BooleanArray(n)
        val queue = IntArray(n)
        var bestArea = 0
        var bestMask: BooleanArray? = null

        for (startIdx in 0 until n) {
            if (visited[startIdx] || scores[startIdx] < threshold) continue
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
                    if (!visited[nxt] && scores[nxt] >= threshold) {
                        visited[nxt] = true
                        queue[tail++] = nxt
                    }
                }
                if (x - 1 >= 0) {
                    val nxt = cur - 1
                    if (!visited[nxt] && scores[nxt] >= threshold) {
                        visited[nxt] = true
                        queue[tail++] = nxt
                    }
                }
                if (y + 1 < height) {
                    val nxt = cur + width
                    if (!visited[nxt] && scores[nxt] >= threshold) {
                        visited[nxt] = true
                        queue[tail++] = nxt
                    }
                }
                if (y - 1 >= 0) {
                    val nxt = cur - width
                    if (!visited[nxt] && scores[nxt] >= threshold) {
                        visited[nxt] = true
                        queue[tail++] = nxt
                    }
                }
            }

            val areaFrac = area.toFloat() / n.toFloat()
            if (areaFrac < 0.06f || areaFrac > 0.96f) continue
            val cxNorm = (sumX.toFloat() / area.toFloat()) / width.toFloat()
            val cyNorm = (sumY.toFloat() / area.toFloat()) / height.toFloat()
            // Paper center is expected in central region (ignores bright lamps/cups at corners)
            if (cxNorm !in 0.14f..0.86f || cyNorm !in 0.14f..0.86f) continue

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
        val reducedHull = subsampleHull(fullHull, maxVertices = 20)
        val rawQuad = findMaxAreaQuad(reducedHull) ?: return null
        return orderQuadCorners(rawQuad)
    }

    fun evaluateQuadGeometry(
        quad: DocumentQuad?,
        fullWidth: Int,
        fullHeight: Int
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
                warningMessage = null
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

        val canFlatten = areaRatio >= MIN_FLATTENABLE_AREA_RATIO &&
            minEdgePx >= MIN_FLATTENABLE_EDGE_PIXELS &&
            maxTaperRatio <= MAX_FLATTENABLE_TAPER_RATIO &&
            angleDev <= 45f

        val (issue, warning) = when {
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
            warningMessage = warning
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
        val fullQuad = smallQuad?.scaled(
            scaleX = srcW.toFloat() / smallW.toFloat(),
            scaleY = srcH.toFloat() / smallH.toFloat()
        )
        val geomReport = DocumentGeometry.evaluateQuadGeometry(fullQuad, srcW, srcH)

        // 2. Perspective flatten if valid quad detected
        var workingBitmap = orientedSource
        var wasDeskewed = false
        if (fullQuad != null && geomReport.canSafelyFlatten) {
            val paddedQuad = fullQuad.expandedOutward(0.015f, srcW.toFloat(), srcH.toFloat())
            val (dstW, dstH) = DocumentGeometry.computeFlatA4Size(paddedQuad)
            val srcPts = floatArrayOf(
                paddedQuad.topLeft.x, paddedQuad.topLeft.y,
                paddedQuad.topRight.x, paddedQuad.topRight.y,
                paddedQuad.bottomRight.x, paddedQuad.bottomRight.y,
                paddedQuad.bottomLeft.x, paddedQuad.bottomLeft.y
            )
            val dstPts = floatArrayOf(
                0f, 0f,
                dstW.toFloat(), 0f,
                dstW.toFloat(), dstH.toFloat(),
                0f, dstH.toFloat()
            )
            val matrix = Matrix()
            if (matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) {
                val warped = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(warped)
                canvas.drawColor(android.graphics.Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(orientedSource, matrix, paint)
                workingBitmap = warped
                wasDeskewed = true
            }
        }

        // 3. Auto-rotate sideways or upside-down document pages
        var rotatedDegrees = 0
        val initialText = recognizeTextOnScaled(workingBitmap, OCR_PROBE_MAX_DIM)
        val angles = initialText?.textBlocks?.flatMap { it.lines }?.map { it.angle }.orEmpty()
        var suggestedRot = DocumentGeometry.resolveRotationFromLineAngles(angles)

        val initialAlnum = countAlnum(initialText)
        if (suggestedRot == 0 && initialAlnum < 16) {
            // Probe alternate 90/180/270 orientations in case upside-down/sideways Cyrillic was missed
            var bestRot = 0
            var bestScore = initialAlnum
            for (candRot in listOf(90, 180, 270)) {
                val probeBmp = rotateCopy(downscaleCopy(workingBitmap, 720), candRot.toFloat())
                val probeText = recognizeTextOnScaled(probeBmp, 720)
                probeBmp.recycle()
                val score = countAlnum(probeText)
                if (score >= bestScore + 15 && score >= (bestScore * 1.8f).toInt()) {
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
