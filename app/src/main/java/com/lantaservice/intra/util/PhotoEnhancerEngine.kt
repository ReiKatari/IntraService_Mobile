package com.lantaservice.intra.util

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class EnhanceMode {
    TEXT_CLARITY,
    NATURAL,
    DOCUMENT_SCAN_BW
}

data class EnhancedPhotoResult(
    val bitmap: Bitmap,
    val sharpnessScore: Double,
    val isBlurryWarning: Boolean,
    val enhanced: Boolean
)

/**
 * Pure-Kotlin image clarity engine designed for document, label, and rack photos:
 * - Gentle Gray-World auto white balance (neutralizes indoor color casts)
 * - 8x8 tile-interpolated adaptive local contrast (CLAHE-lite) with glare protection
 * - Edge-preserving micro-denoise on flat areas
 * - Multi-scale luminance unsharp masking for razor-sharp readable text
 */
object PhotoEnhancerEngine {

    private const val TILE_GRID = 8
    private const val BLURRY_THRESHOLD = 42.0

    suspend fun enhance(
        source: Bitmap,
        mode: EnhanceMode
    ): EnhancedPhotoResult = withContext(Dispatchers.Default) {
        val w = source.width
        val h = source.height
        val n = w * h
        val pixels = IntArray(n)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        if (mode == EnhanceMode.NATURAL) {
            val score = computeSharpnessFromArgb(pixels, w, h)
            return@withContext EnhancedPhotoResult(
                bitmap = source,
                sharpnessScore = score,
                isBlurryWarning = score < BLURRY_THRESHOLD,
                enhanced = false
            )
        }

        if (mode == EnhanceMode.DOCUMENT_SCAN_BW) {
            val yRaw = IntArray(n)
            for (idx in 0 until n) {
                yRaw[idx] = argbLuma(pixels[idx])
            }
            val bwPlane = enhanceDocumentScanBwPlane(yRaw, w, h)
            val bwPixels = IntArray(n)
            for (idx in 0 until n) {
                val g = bwPlane[idx]
                bwPixels[idx] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            val score = computeSharpnessScore(bwPlane, w, h)
            val outBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outBitmap.setPixels(bwPixels, 0, w, 0, 0, w, h)
            return@withContext EnhancedPhotoResult(
                bitmap = outBitmap,
                sharpnessScore = score,
                isBlurryWarning = score < BLURRY_THRESHOLD,
                enhanced = true
            )
        }

        // 1. Gray-World auto white balance (sampled every 2nd pixel for speed)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L
        var i = 0
        while (i < n) {
            val c = pixels[i]
            sumR += (c ushr 16) and 0xFF
            sumG += (c ushr 8) and 0xFF
            sumB += c and 0xFF
            count++
            i += 2
        }
        val avgR = max(1f, sumR.toFloat() / max(1L, count))
        val avgG = max(1f, sumG.toFloat() / max(1L, count))
        val avgB = max(1f, sumB.toFloat() / max(1L, count))
        val avgGray = (avgR + avgG + avgB) / 3f

        val gainR = 1f + 0.45f * ((avgGray / avgR).coerceIn(0.82f, 1.22f) - 1f)
        val gainG = 1f + 0.45f * ((avgGray / avgG).coerceIn(0.85f, 1.18f) - 1f)
        val gainB = 1f + 0.45f * ((avgGray / avgB).coerceIn(0.82f, 1.22f) - 1f)

        val lutR = IntArray(256) { v -> (v * gainR).roundToInt().coerceIn(0, 255) }
        val lutG = IntArray(256) { v -> (v * gainG).roundToInt().coerceIn(0, 255) }
        val lutB = IntArray(256) { v -> (v * gainB).roundToInt().coerceIn(0, 255) }

        val yIn = IntArray(n)
        for (idx in 0 until n) {
            val c = pixels[idx]
            val r = lutR[(c ushr 16) and 0xFF]
            val g = lutG[(c ushr 8) and 0xFF]
            val b = lutB[c and 0xFF]
            pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            yIn[idx] = (77 * r + 150 * g + 29 * b) ushr 8
        }

        // 2. Enhance luminance plane (CLAHE-lite + selective denoise + multi-scale USM)
        val yOut = enhanceLuminancePlane(
            yIn = yIn,
            width = w,
            height = h,
            sharpenAmount = 1.38f,
            contrastBlend = 0.36f
        )

        // 3. Reconstruct crisp color pixels preserving hue without clipping highlights
        for (idx in 0 until n) {
            val c = pixels[idx]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val oldY = yIn[idx]
            val newY = yOut[idx]
            if (oldY != newY) {
                val deltaY = newY - oldY
                val scale = if (oldY > 8) (newY.toFloat() / oldY.toFloat()).coerceIn(0.65f, 1.55f) else 1f
                val nr = ((0.55f * (r * scale)) + (0.45f * (r + deltaY))).roundToInt().coerceIn(0, 255)
                val ng = ((0.55f * (g * scale)) + (0.45f * (g + deltaY))).roundToInt().coerceIn(0, 255)
                val nb = ((0.55f * (b * scale)) + (0.45f * (b + deltaY))).roundToInt().coerceIn(0, 255)
                pixels[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        val score = computeSharpnessScore(yOut, w, h)
        val outBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        EnhancedPhotoResult(
            bitmap = outBitmap,
            sharpnessScore = score,
            isBlurryWarning = score < BLURRY_THRESHOLD,
            enhanced = true
        )
    }

    /**
     * Pure-JVM testable luminance clarity pipeline.
     */
    fun enhanceLuminancePlane(
        yIn: IntArray,
        width: Int,
        height: Int,
        sharpenAmount: Float = 1.38f,
        contrastBlend: Float = 0.36f
    ): IntArray {
        val n = width * height
        if (n == 0 || width < 4 || height < 4) return yIn.copyOf()

        // Tile-based local contrast (CLAHE-lite) with bilinear interpolation
        val yContrast = applyTileClaheLite(yIn, width, height, contrastBlend)

        // Multi-scale separable box blurs on contrast-enhanced luminance
        val blur1 = separableBoxBlur(yContrast, width, height, radius = 1)
        val blur2 = separableBoxBlur(yContrast, width, height, radius = 2)

        val out = IntArray(n)
        for (i in 0 until n) {
            val base = yContrast[i]
            val b1 = blur1[i]
            val b2 = blur2[i]
            val d1 = base - b1
            val d2 = base - b2

            val newVal = if (abs(d1) <= 3 && abs(d2) <= 4) {
                // Smooth low-amplitude noise on flat surfaces
                (base * 0.65f + b1 * 0.35f).roundToInt()
            } else {
                val combinedDetail = d1 * 0.65f + d2 * 0.35f
                val boost = (combinedDetail * sharpenAmount).roundToInt().coerceIn(-55, 55)
                base + boost
            }
            out[i] = newVal.coerceIn(0, 255)
        }
        return out
    }

    /**
     * Pure-JVM flatbed-scanner style B&W document pipeline:
     * removes uneven hand/lamp shadows via morphological closing + blurred illumination surface,
     * normalizes paper to crisp white (255), and boosts ink readability for small JPEG size.
     */
    fun enhanceDocumentScanBwPlane(
        yIn: IntArray,
        width: Int,
        height: Int
    ): IntArray {
        val n = width * height
        if (n == 0 || width < 8 || height < 8) return yIn.copyOf()

        // 1. Estimate local paper background illumination on a 4x downsampled grid
        val step = 4
        val dw = max(4, (width + step - 1) / step)
        val dh = max(4, (height + step - 1) / step)
        val down = IntArray(dw * dh)
        for (dy in 0 until dh) {
            val sy = min(height - 1, dy * step)
            val row = sy * width
            for (dx in 0 until dw) {
                val sx = min(width - 1, dx * step)
                // Take local 4x4 max so narrow dark text lines do not darken background estimate
                var localMax = 0
                val yEnd = min(height, sy + step)
                val xEnd = min(width, sx + step)
                for (yy in sy until yEnd) {
                    val rOff = yy * width
                    for (xx in sx until xEnd) {
                        val v = yIn[rOff + xx]
                        if (v > localMax) localMax = v
                    }
                }
                down[dy * dw + dx] = localMax
            }
        }

        // Morphological dilation (3x3 max) followed by wide box blur for smooth shadow envelope
        val dilated = IntArray(dw * dh)
        for (dy in 0 until dh) {
            for (dx in 0 until dw) {
                var m = 0
                for (ky in -1..1) {
                    val yy = (dy + ky).coerceIn(0, dh - 1)
                    val rOff = yy * dw
                    for (kx in -1..1) {
                        val xx = (dx + kx).coerceIn(0, dw - 1)
                        val v = down[rOff + xx]
                        if (v > m) m = v
                    }
                }
                dilated[dy * dw + dx] = m
            }
        }
        val bgSmooth = separableBoxBlur(dilated, dw, dh, radius = max(5, min(dw, dh) / 14))

        // 2. Upsample illumination field & apply crisp document tone mapping
        val normalized = IntArray(n)
        for (y in 0 until height) {
            val fy = (y.toFloat() / step.toFloat()).coerceIn(0f, (dh - 1).toFloat())
            val y0 = fy.toInt().coerceIn(0, dh - 1)
            val y1 = (y0 + 1).coerceIn(0, dh - 1)
            val wy = fy - y0
            val row = y * width

            for (x in 0 until width) {
                val fx = (x.toFloat() / step.toFloat()).coerceIn(0f, (dw - 1).toFloat())
                val x0 = fx.toInt().coerceIn(0, dw - 1)
                val x1 = (x0 + 1).coerceIn(0, dw - 1)
                val wx = fx - x0

                val c00 = bgSmooth[y0 * dw + x0]
                val c10 = bgSmooth[y0 * dw + x1]
                val c01 = bgSmooth[y1 * dw + x0]
                val c11 = bgSmooth[y1 * dw + x1]
                val top = c00 + (c10 - c00) * wx
                val bot = c01 + (c11 - c01) * wx
                val bgEst = max(28f, top + (bot - top) * wy)

                val ratio = ((yIn[row + x] * 246f) / bgEst).coerceIn(0f, 255f)
                // Clean pure white background, smooth edges, crisp dark ink
                val tone = when {
                    ratio >= 220f -> 255
                    ratio <= 145f -> ((ratio / 145f) * 32f).roundToInt().coerceIn(0, 32)
                    else -> {
                        val t = (ratio - 145f) / (220f - 145f)
                        (32f + t * t * (3f - 2f * t) * 223f).roundToInt().coerceIn(32, 255)
                    }
                }
                normalized[row + x] = tone
            }
        }

        // Light edge sharpening on the B&W scan plane for razor-sharp letters
        val blur = separableBoxBlur(normalized, width, height, radius = 1)
        val out = IntArray(n)
        for (i in 0 until n) {
            val v = normalized[i]
            val d = v - blur[i]
            out[i] = if (v >= 252 && abs(d) <= 4) {
                255
            } else {
                (v + (d * 0.55f).roundToInt()).coerceIn(0, 255)
            }
        }
        return out
    }

    /**
     * Computes center-weighted Laplacian sharpness variance score.
     */
    private fun computeSharpnessFromArgb(pixels: IntArray, width: Int, height: Int): Double {
        if (width < 8 || height < 8 || pixels.size < width * height) return 0.0
        val xStart = (width / 5).coerceAtLeast(1)
        val xEnd = ((width * 4) / 5).coerceAtMost(width - 2)
        val yStart = (height / 5).coerceAtLeast(1)
        val yEnd = ((height * 4) / 5).coerceAtMost(height - 2)
        val step = max(1, min(width, height) / 360)

        var sum = 0.0
        var sumSq = 0.0
        var count = 0L

        var y = yStart
        while (y <= yEnd) {
            val row = y * width
            var x = xStart
            while (x <= xEnd) {
                val idx = row + x
                val c = argbLuma(pixels[idx])
                val lap = (4 * c -
                    argbLuma(pixels[idx - 1]) -
                    argbLuma(pixels[idx + 1]) -
                    argbLuma(pixels[idx - width]) -
                    argbLuma(pixels[idx + width])).toDouble()
                sum += lap
                sumSq += lap * lap
                count++
                x += step
            }
            y += step
        }
        if (count == 0L) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    private fun argbLuma(c: Int): Int {
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        return (77 * r + 150 * g + 29 * b) ushr 8
    }

    fun computeSharpnessScore(luma: IntArray, width: Int, height: Int): Double {
        if (width < 8 || height < 8 || luma.size < width * height) return 0.0
        val xStart = (width / 5).coerceAtLeast(1)
        val xEnd = ((width * 4) / 5).coerceAtMost(width - 2)
        val yStart = (height / 5).coerceAtLeast(1)
        val yEnd = ((height * 4) / 5).coerceAtMost(height - 2)
        val step = max(1, min(width, height) / 360)

        var sum = 0.0
        var sumSq = 0.0
        var count = 0L

        var y = yStart
        while (y <= yEnd) {
            val row = y * width
            var x = xStart
            while (x <= xEnd) {
                val idx = row + x
                val c = luma[idx]
                val lap = (4 * c - luma[idx - 1] - luma[idx + 1] - luma[idx - width] - luma[idx + width]).toDouble()
                sum += lap
                sumSq += lap * lap
                count++
                x += step
            }
            y += step
        }
        if (count == 0L) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    private fun applyTileClaheLite(
        yIn: IntArray,
        width: Int,
        height: Int,
        blend: Float
    ): IntArray {
        val gridX = min(TILE_GRID, max(1, width / 16))
        val gridY = min(TILE_GRID, max(1, height / 16))
        val luts = Array(gridY) { Array(gridX) { IntArray(256) } }

        for (ty in 0 until gridY) {
            val y0 = (ty * height) / gridY
            val y1 = ((ty + 1) * height) / gridY
            for (tx in 0 until gridX) {
                val x0 = (tx * width) / gridX
                val x1 = ((tx + 1) * width) / gridX
                val hist = IntArray(256)
                var tilePixels = 0
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) {
                        hist[yIn[row + x]]++
                        tilePixels++
                    }
                }
                if (tilePixels == 0) continue

                // Clip histogram to avoid over-amplifying flat regions
                val clipLimit = max(2, (tilePixels * 3) / 256)
                var excess = 0
                for (bin in 0 until 256) {
                    if (hist[bin] > clipLimit) {
                        excess += hist[bin] - clipLimit
                        hist[bin] = clipLimit
                    }
                }
                val redistrib = excess / 256
                val rem = excess % 256
                for (bin in 0 until 256) {
                    hist[bin] += redistrib + if (bin < rem) 1 else 0
                }

                // Build normalized CDF LUT
                var cdf = 0
                val denom = max(1, tilePixels - hist[0])
                for (bin in 0 until 256) {
                    cdf += hist[bin]
                    val eq = (((cdf - hist[0]).toFloat() / denom.toFloat()) * 255f)
                        .roundToInt()
                        .coerceIn(0, 255)
                    // Gentle shadow lift for dark text on shadowed labels
                    val shadowLift = if (bin < 90) bin + ((90 - bin) * 0.14f).roundToInt() else bin
                    val target = (shadowLift * (1f - blend) + eq * blend).roundToInt().coerceIn(0, 255)
                    luts[ty][tx][bin] = target
                }
            }
        }

        val out = IntArray(width * height)
        val tileW = width.toFloat() / gridX.toFloat()
        val tileH = height.toFloat() / gridY.toFloat()

        for (y in 0 until height) {
            val fy = ((y + 0.5f) / tileH) - 0.5f
            val ty0 = fy.toInt().coerceIn(0, gridY - 1)
            val ty1 = (ty0 + 1).coerceIn(0, gridY - 1)
            val wy = (fy - ty0).coerceIn(0f, 1f)
            val row = y * width

            for (x in 0 until width) {
                val fx = ((x + 0.5f) / tileW) - 0.5f
                val tx0 = fx.toInt().coerceIn(0, gridX - 1)
                val tx1 = (tx0 + 1).coerceIn(0, gridX - 1)
                val wx = (fx - tx0).coerceIn(0f, 1f)

                val v = yIn[row + x]
                val c00 = luts[ty0][tx0][v]
                val c10 = luts[ty0][tx1][v]
                val c01 = luts[ty1][tx0][v]
                val c11 = luts[ty1][tx1][v]

                val top = c00 + (c10 - c00) * wx
                val bot = c01 + (c11 - c01) * wx
                out[row + x] = (top + (bot - top) * wy).roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    private fun separableBoxBlur(
        src: IntArray,
        width: Int,
        height: Int,
        radius: Int
    ): IntArray {
        val n = width * height
        val tmp = IntArray(n)
        val dst = IntArray(n)
        val kernelSize = radius * 2 + 1

        // Horizontal pass
        for (y in 0 until height) {
            val row = y * width
            var sum = 0
            for (k in -radius..radius) {
                sum += src[row + k.coerceIn(0, width - 1)]
            }
            tmp[row] = sum / kernelSize
            for (x in 1 until width) {
                val removeIdx = (x - radius - 1).coerceIn(0, width - 1)
                val addIdx = (x + radius).coerceIn(0, width - 1)
                sum += src[row + addIdx] - src[row + removeIdx]
                tmp[row + x] = sum / kernelSize
            }
        }

        // Vertical pass
        for (x in 0 until width) {
            var sum = 0
            for (k in -radius..radius) {
                val sy = k.coerceIn(0, height - 1)
                sum += tmp[sy * width + x]
            }
            dst[x] = sum / kernelSize
            for (y in 1 until height) {
                val removeY = (y - radius - 1).coerceIn(0, height - 1)
                val addY = (y + radius).coerceIn(0, height - 1)
                sum += tmp[addY * width + x] - tmp[removeY * width + x]
                dst[y * width + x] = sum / kernelSize
            }
        }
        return dst
    }
}
