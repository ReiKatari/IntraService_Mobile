package com.lantaservice.intra.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import com.lantaservice.intra.data.model.PendingPhoto
import com.lantaservice.intra.data.model.PhotoDocumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

data class CameraReviewPair(
    val scanBwPhoto: PendingPhoto,
    val enhancedPhoto: PendingPhoto,
    val originalPhoto: PendingPhoto
)

/**
 * Handles camera temp files, EXIF orientation correction, crisp scaling (up to 2200px),
 * flatbed B&W scan generation, and integration with DocumentScannerEngine / PhotoEnhancerEngine.
 */
object ImageCompressor {

    private const val MAX_DIMENSION_PX = 2200
    private const val JPEG_QUALITY = 88
    private const val SCAN_BW_JPEG_QUALITY = 76

    fun createTempCameraFile(context: Context, taskId: Int): Pair<File, Uri> {
        val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(cameraDir, "cap_${taskId}_${stamp}_${UUID.randomUUID().toString().take(6)}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return file to uri
    }

    suspend fun compressPhoto(
        context: Context,
        sourceUri: Uri,
        taskId: Int,
        index: Int,
        enhanceMode: EnhanceMode = EnhanceMode.TEXT_CLARITY,
        documentType: PhotoDocumentType = PhotoDocumentType.AVR
    ): PendingPhoto = withContext(Dispatchers.IO) {
        val preparedBitmap = decodeAndOrientUri(context, sourceUri)
        val scanResult = if (enhanceMode == EnhanceMode.TEXT_CLARITY) {
            DocumentScannerEngine.scanAndFlatten(context, preparedBitmap)
        } else {
            null
        }
        val enhanceInput = scanResult?.flattenedBitmap ?: preparedBitmap
        val result = PhotoEnhancerEngine.enhance(enhanceInput, enhanceMode)
        if (result.bitmap !== enhanceInput) {
            enhanceInput.recycle()
        }
        if (enhanceInput !== preparedBitmap) {
            preparedBitmap.recycle()
        }

        val uploadsDir = File(context.cacheDir, "uploads").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val nameWithoutExt = "${documentType.filePrefix}_task${taskId}_${stamp}_$index"
        val outFile = File(uploadsDir, "$nameWithoutExt.jpg")

        FileOutputStream(outFile).use { out ->
            result.bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        result.bitmap.recycle()

        PendingPhoto(
            id = UUID.randomUUID().toString(),
            file = outFile,
            nameWithoutExt = nameWithoutExt,
            sizeBytes = outFile.length(),
            isEnhanced = result.enhanced,
            isBlurryWarning = result.isBlurryWarning || (scanResult?.needsRetakeWarning == true),
            sharpnessScore = scanResult?.sharpnessScore ?: result.sharpnessScore,
            documentType = documentType,
            createdAtEpochMs = System.currentTimeMillis(),
            qualityWarning = scanResult?.warningMessage,
            wasDeskewed = scanResult?.wasDeskewed == true,
            autoRotatedDegrees = scanResult?.autoRotatedDegrees ?: 0
        )
    }

    suspend fun prepareCameraReviewPair(
        context: Context,
        rawFile: File,
        taskId: Int,
        index: Int,
        documentType: PhotoDocumentType = PhotoDocumentType.AVR
    ): CameraReviewPair = withContext(Dispatchers.IO) {
        val uri = Uri.fromFile(rawFile)
        val oriented = decodeAndOrientUri(context, uri)
        val scanResult = DocumentScannerEngine.scanAndFlatten(context, oriented)
        val flattenedInput = scanResult.flattenedBitmap

        val uploadsDir = File(context.cacheDir, "uploads").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val origName = "orig_${documentType.filePrefix}_task${taskId}_${stamp}_$index"
        val enhName = "${documentType.filePrefix}_task${taskId}_${stamp}_$index"
        val bwName = "scan_${documentType.filePrefix}_task${taskId}_${stamp}_$index"
        val origFile = File(uploadsDir, "$origName.jpg")
        val enhFile = File(uploadsDir, "$enhName.jpg")
        val bwFile = File(uploadsDir, "$bwName.jpg")
        val createdEpochMs = System.currentTimeMillis()

        val (naturalResultPair, enhancedResultPair, scanBwResultPair) = coroutineScope {
            val origDeferred = async(Dispatchers.Default) {
                val res = PhotoEnhancerEngine.enhance(oriented, EnhanceMode.NATURAL)
                FileOutputStream(origFile).use { out ->
                    res.bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (res.bitmap !== oriented) {
                    res.bitmap.recycle()
                }
                res
            }
            val enhDeferred = async(Dispatchers.Default) {
                val res = PhotoEnhancerEngine.enhance(flattenedInput, EnhanceMode.TEXT_CLARITY)
                FileOutputStream(enhFile).use { out ->
                    res.bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (res.bitmap !== flattenedInput) {
                    res.bitmap.recycle()
                }
                res
            }
            val bwDeferred = async(Dispatchers.Default) {
                val res = PhotoEnhancerEngine.enhance(flattenedInput, EnhanceMode.DOCUMENT_SCAN_BW)
                FileOutputStream(bwFile).use { out ->
                    res.bitmap.compress(Bitmap.CompressFormat.JPEG, SCAN_BW_JPEG_QUALITY, out)
                }
                if (res.bitmap !== flattenedInput) {
                    res.bitmap.recycle()
                }
                res
            }
            Triple(origDeferred.await(), enhDeferred.await(), bwDeferred.await())
        }
        if (flattenedInput !== oriented) {
            flattenedInput.recycle()
        }
        oriented.recycle()
        runCatching { rawFile.delete() }

        val needsRetake = scanResult.needsRetakeWarning || enhancedResultPair.isBlurryWarning
        val effectiveWarning = scanResult.warningMessage ?: if (enhancedResultPair.isBlurryWarning) {
            "Текст недостаточно чёткий - необходимо переснять."
        } else {
            null
        }

        CameraReviewPair(
            scanBwPhoto = PendingPhoto(
                id = UUID.randomUUID().toString(),
                file = bwFile,
                nameWithoutExt = enhName,
                sizeBytes = bwFile.length(),
                isEnhanced = true,
                isBlurryWarning = needsRetake || scanBwResultPair.isBlurryWarning,
                sharpnessScore = scanResult.sharpnessScore,
                documentType = documentType,
                createdAtEpochMs = createdEpochMs,
                qualityWarning = effectiveWarning,
                wasDeskewed = scanResult.wasDeskewed,
                autoRotatedDegrees = scanResult.autoRotatedDegrees
            ),
            enhancedPhoto = PendingPhoto(
                id = UUID.randomUUID().toString(),
                file = enhFile,
                nameWithoutExt = enhName,
                sizeBytes = enhFile.length(),
                isEnhanced = true,
                isBlurryWarning = needsRetake,
                sharpnessScore = scanResult.sharpnessScore,
                documentType = documentType,
                createdAtEpochMs = createdEpochMs,
                qualityWarning = effectiveWarning,
                wasDeskewed = scanResult.wasDeskewed,
                autoRotatedDegrees = scanResult.autoRotatedDegrees
            ),
            originalPhoto = PendingPhoto(
                id = UUID.randomUUID().toString(),
                file = origFile,
                nameWithoutExt = enhName,
                sizeBytes = origFile.length(),
                isEnhanced = false,
                isBlurryWarning = naturalResultPair.isBlurryWarning || scanResult.needsRetakeWarning,
                sharpnessScore = naturalResultPair.sharpnessScore,
                documentType = documentType,
                createdAtEpochMs = createdEpochMs,
                qualityWarning = effectiveWarning,
                wasDeskewed = false,
                autoRotatedDegrees = 0
            )
        )
    }

    fun readExifLatLong(context: Context, uri: Uri): Pair<Double, Double>? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = FloatArray(2)
                @Suppress("DEPRECATION")
                val ok = exif.getLatLong(latLong)
                if (ok) latLong[0].toDouble() to latLong[1].toDouble() else null
            }
        }.getOrNull()
    }

    fun isInCacheUploads(context: Context, file: File): Boolean {
        val cachePath = runCatching { context.cacheDir.canonicalPath }.getOrDefault(context.cacheDir.absolutePath)
        val filePath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        return filePath.startsWith(cachePath + File.separator)
    }

    fun cleanupTemporaryUploadFiles(context: Context, photos: List<PendingPhoto>) {
        photos.forEach { photo ->
            if (isInCacheUploads(context, photo.file)) {
                runCatching { photo.file.delete() }
            }
        }
    }

    fun cleanupUploadFiles(photos: List<PendingPhoto>) {
        photos.forEach { photo ->
            runCatching { photo.file.delete() }
        }
    }

    fun cleanupCacheDirs(context: Context) {
        listOf("camera", "uploads", "downloads").forEach { dirName ->
            runCatching {
                File(context.cacheDir, dirName).deleteRecursively()
            }
        }
    }

    private fun decodeAndOrientUri(context: Context, sourceUri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION_PX)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOpts)
        } ?: error("Не удалось прочитать изображение")

        val rotationDegrees = readExifRotation(context, sourceUri)
        val rotated = if (rotationDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }
        return scaleDownIfNeeded(rotated, MAX_DIMENSION_PX)
    }

    private fun readExifRotation(context: Context, uri: Uri): Float {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var inSampleSize = 1
        if (width <= 0 || height <= 0) return inSampleSize
        var halfW = width / 2
        var halfH = height / 2
        while (halfW / inSampleSize >= maxDim && halfH / inSampleSize >= maxDim) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / largest.toFloat()
        val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}
