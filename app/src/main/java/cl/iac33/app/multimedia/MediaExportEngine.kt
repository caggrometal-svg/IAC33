package cl.iac33.app.multimedia

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaScannerConnection
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Crop
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

enum class MediaFilter { NONE, BW, SEPIA, VINTAGE, CYBERPUNK }

enum class AspectRatio(val label: String) {
    ORIGINAL("Original"),
    PORTRAIT("9:16"),
    LANDSCAPE("16:9"),
    SQUARE("1:1")
}

data class TextOverlaySpec(
    val text: String,
    val fontSizeSp: Int = 28
)

data class ExportRequest(
    val input: Uri,
    val startMs: Long = 0L,
    val endMs: Long? = null,
    val speed: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 1f,
    val filter: MediaFilter = MediaFilter.NONE,
    val aspect: AspectRatio = AspectRatio.ORIGINAL,
    val textOverlay: TextOverlaySpec? = null,
    val secondaryAudio: Uri? = null
)

data class TimelineExportClip(
    val input: Uri,
    val startMs: Long = 0L,
    val endMs: Long? = null,
    val speed: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 1f,
    val filter: MediaFilter = MediaFilter.NONE,
    val aspect: AspectRatio = AspectRatio.ORIGINAL,
    val textOverlay: TextOverlaySpec? = null
)

data class ExportedMedia(
    val uri: Uri,
    val mimeType: String,
    val displayName: String
)

class MediaExportEngine(private val context: Context) {

    suspend fun exportVideo(
        request: ExportRequest,
        onProgress: (Int) -> Unit = {}
    ): ExportedMedia = withContext(Dispatchers.IO) {
        require(request.speed in 0.25f..4f) { "La velocidad debe estar entre 0.25x y 4x" }
        require(request.endMs == null || request.endMs > request.startMs) { "Rango de recorte inválido" }

        val output = File.createTempFile("iac33_export_", ".mp4", context.cacheDir)
        try {
            val input = MediaItem.Builder()
                .setUri(request.input)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(max(0L, request.startMs))
                        .apply {
                            request.endMs?.let { setEndPositionMs(max(request.startMs + 1L, it)) }
                        }
                        .build()
                )
                .build()

            val edited = EditedMediaItem.Builder(input)
                .setEffects(Effects(emptyList(), buildVideoEffects(request)))
                .setSpeed(FixedSpeedProvider(request.speed))
                .build()

            val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited))
            val composition = if (request.secondaryAudio != null) {
                val bgAudio = EditedMediaItem.Builder(MediaItem.fromUri(request.secondaryAudio)).build()
                val bgSequence = EditedMediaItemSequence.withAudioFrom(listOf(bgAudio))
                    .buildUpon()
                    .setIsLooping(true)
                    .build()
                Composition.Builder(videoSequence, bgSequence).build()
            } else {
                Composition.Builder(videoSequence).build()
            }

            suspendCancellableCoroutine<ExportedMedia> { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            onProgress(100)
                            if (continuation.isActive) {
                                runCatching { saveVideoToGallery(output) }
                                    .onSuccess { continuation.resume(it) }
                                    .onFailure { continuation.resumeWithException(it) }
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    })
                    .build()

                continuation.invokeOnCancellation { runCatching { transformer.cancel() } }
                onProgress(5)
                transformer.start(composition, output.absolutePath)
            }
        } finally {
            output.delete()
        }
    }

    suspend fun exportTimeline(
        clips: List<TimelineExportClip>,
        secondaryAudio: Uri? = null,
        onProgress: (Int) -> Unit = {}
    ): ExportedMedia = withContext(Dispatchers.IO) {
        require(clips.isNotEmpty()) { "La línea de tiempo está vacía" }
        require(clips.size <= 32) { "Demasiados clips en la línea de tiempo" }
        require(clips.all { it.speed in 0.25f..4f && (it.endMs == null || it.endMs > it.startMs) }) {
            "Hay un rango o velocidad inválidos en la línea de tiempo"
        }

        val output = File.createTempFile("iac33_timeline_", ".mp4", context.cacheDir)
        try {
            val items = clips.map { clip ->
                val mediaItem = MediaItem.Builder()
                    .setUri(clip.input)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(max(0L, clip.startMs))
                            .apply {
                                clip.endMs?.let { setEndPositionMs(max(clip.startMs + 1L, it)) }
                            }
                            .build()
                    )
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setEffects(
                        Effects(
                            emptyList(),
                            buildVideoEffects(
                                ExportRequest(
                                    input = clip.input,
                                    startMs = clip.startMs,
                                    endMs = clip.endMs,
                                    speed = clip.speed,
                                    brightness = clip.brightness,
                                    contrast = clip.contrast,
                                    saturation = clip.saturation,
                                    filter = clip.filter,
                                    aspect = clip.aspect,
                                    textOverlay = clip.textOverlay
                                )
                            )
                        )
                    )
                    .setSpeed(FixedSpeedProvider(clip.speed))
                    .build()
            }
            val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(items)
            val composition = if (secondaryAudio != null) {
                val bgAudio = EditedMediaItem.Builder(MediaItem.fromUri(secondaryAudio)).build()
                val bgSequence = EditedMediaItemSequence.withAudioFrom(listOf(bgAudio))
                    .buildUpon()
                    .setIsLooping(true)
                    .build()
                Composition.Builder(videoSequence, bgSequence).build()
            } else {
                Composition.Builder(videoSequence).build()
            }

            suspendCancellableCoroutine<ExportedMedia> { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            onProgress(100)
                            if (continuation.isActive) {
                                runCatching { saveVideoToGallery(output) }
                                    .onSuccess { continuation.resume(it) }
                                    .onFailure { continuation.resumeWithException(it) }
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    })
                    .build()
                continuation.invokeOnCancellation { runCatching { transformer.cancel() } }
                onProgress(5)
                transformer.start(composition, output.absolutePath)
            }
        } finally {
            output.delete()
        }
    }

    suspend fun saveImage(
        source: Uri,
        brightness: Float = 0f,
        contrast: Float = 0f,
        saturation: Float = 1f,
        filter: MediaFilter = MediaFilter.NONE,
        aspect: AspectRatio = AspectRatio.ORIGINAL,
        textOverlay: TextOverlaySpec? = null
    ): ExportedMedia = withContext(Dispatchers.IO) {
        val bitmap = decodeBitmap(source) ?: error("No se pudo decodificar la imagen")
        val matrix = ColorMatrix().apply {
            setSaturation(saturation.coerceIn(0f, 3f))
            postConcat(ColorMatrix().apply {
                val c = 1f + contrast.coerceIn(-1f, 1f)
                val translate = (1f - c) * 127.5f + brightness.coerceIn(-1f, 1f) * 255f
                set(floatArrayOf(
                    c, 0f, 0f, 0f, translate,
                    0f, c, 0f, 0f, translate,
                    0f, 0f, c, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                ))
            })
            when (filter) {
                MediaFilter.BW -> postConcat(ColorMatrix().apply { setSaturation(0f) })
                MediaFilter.SEPIA -> postConcat(ColorMatrix(sepiaMatrix()))
                MediaFilter.VINTAGE -> postConcat(ColorMatrix(vintageMatrix()))
                MediaFilter.CYBERPUNK -> postConcat(ColorMatrix(cyberpunkMatrix()))
                MediaFilter.NONE -> Unit
            }
        }

        val cropped = cropBitmapForAspect(bitmap, aspect)
        if (cropped !== bitmap) bitmap.recycle()
        val outputSize = targetImageSize(cropped.width, cropped.height, aspect)
        val outputBitmap = Bitmap.createBitmap(outputSize.first, outputSize.second, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(cropped, null, android.graphics.Rect(0, 0, outputBitmap.width, outputBitmap.height), paint)
        if (cropped !== bitmap) cropped.recycle()
        textOverlay?.takeIf { it.text.isNotBlank() }?.let { overlay ->
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = overlay.fontSizeSp * context.resources.displayMetrics.scaledDensity
                setShadowLayer(8f, 2f, 2f, android.graphics.Color.BLACK)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(overlay.text.take(140), outputBitmap.width / 2f, outputBitmap.height - textPaint.textSize, textPaint)
        }
        saveBitmapToGallery(outputBitmap)
    }

    suspend fun joinVideos(
        sources: List<Uri>,
        onProgress: (Int) -> Unit = {}
    ): ExportedMedia = withContext(Dispatchers.IO) {
        require(sources.size >= 2) { "Selecciona al menos dos vídeos" }
        val output = File.createTempFile("iac33_join_", ".mp4", context.cacheDir)
        try {
            val items = sources.map { uri ->
                EditedMediaItem.Builder(MediaItem.fromUri(uri)).build()
            }
            val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(items)
            val composition = Composition.Builder(sequence).build()
            suspendCancellableCoroutine<ExportedMedia> { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            onProgress(100)
                            if (continuation.isActive) {
                                runCatching { saveVideoToGallery(output) }
                                    .onSuccess { continuation.resume(it) }
                                    .onFailure { continuation.resumeWithException(it) }
                            }
                        }
                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    })
                    .build()
                continuation.invokeOnCancellation { runCatching { transformer.cancel() } }
                onProgress(5)
                transformer.start(composition, output.absolutePath)
            }
        } finally {
            output.delete()
        }
    }

    suspend fun extractAudio(source: Uri): ExportedMedia = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, source, null)
            var audioTrack = -1
            var format: android.media.MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = index
                    format = candidate
                    break
                }
            }
            require(audioTrack >= 0 && format != null) { "El vídeo no contiene pista de audio" }
            extractor.selectTrack(audioTrack)
            val output = File.createTempFile("iac33_audio_", ".m4a", context.cacheDir)
            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val muxTrack = muxer.addTrack(format)
                muxer.start()
                val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
                val info = android.media.MediaCodec.BufferInfo()
                while (true) {
                    info.offset = 0
                    info.size = extractor.readSampleData(buffer, 0)
                    if (info.size < 0) break
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxTrack, buffer, info)
                    extractor.advance()
                }
                muxer.stop()
                saveAudioToGallery(output)
            } finally {
                runCatching { muxer.release() }
                output.delete()
            }
        } finally {
            extractor.release()
        }
    }

    private fun cropBitmapForAspect(bitmap: Bitmap, aspect: AspectRatio): Bitmap {
        if (aspect == AspectRatio.ORIGINAL) return bitmap
        val target = when (aspect) {
            AspectRatio.PORTRAIT -> 9f / 16f
            AspectRatio.LANDSCAPE -> 16f / 9f
            AspectRatio.SQUARE -> 1f
            AspectRatio.ORIGINAL -> bitmap.width.toFloat() / bitmap.height
        }
        val source = bitmap.width.toFloat() / bitmap.height
        if (kotlin.math.abs(source - target) < 0.01f) return bitmap
        val width: Int
        val height: Int
        if (source > target) {
            height = bitmap.height
            width = (height * target).roundToInt().coerceAtLeast(1)
        } else {
            width = bitmap.width
            height = (width / target).roundToInt().coerceAtLeast(1)
        }
        val left = ((bitmap.width - width) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - height) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun targetImageSize(width: Int, height: Int, aspect: AspectRatio): Pair<Int, Int> {
        val maxDimension = 1920
        return when (aspect) {
            AspectRatio.PORTRAIT -> 1080 to 1920
            AspectRatio.LANDSCAPE -> 1920 to 1080
            AspectRatio.SQUARE -> 1080 to 1080
            AspectRatio.ORIGINAL -> {
                if (maxOf(width, height) <= maxDimension) width to height
                else if (width >= height) maxDimension to (height * maxDimension / width).coerceAtLeast(1)
                else (width * maxDimension / height).coerceAtLeast(1) to maxDimension
            }
        }
    }

    private fun buildVideoEffects(request: ExportRequest): List<Effect> {
        val effects = mutableListOf<Effect>()
        if (request.brightness != 0f) {
            effects.add(Brightness(request.brightness.coerceIn(-1f, 1f)))
        }
        if (request.contrast != 0f) {
            effects.add(Contrast(request.contrast.coerceIn(-1f, 1f)))
        }
        if (request.saturation != 1f) {
            effects.add(
                HslAdjustment.Builder()
                    .adjustSaturation(((request.saturation - 1f) * 100f).coerceIn(-100f, 100f))
                    .build()
            )
        }
        when (request.filter) {
            MediaFilter.BW -> effects.add(RgbFilter.createGrayscaleFilter())
            MediaFilter.SEPIA -> effects.add(RgbMatrix { _, _ -> sepiaMatrix() })
            MediaFilter.VINTAGE -> effects.add(RgbMatrix { _, _ -> vintageMatrix() })
            MediaFilter.CYBERPUNK -> effects.add(RgbMatrix { _, _ -> cyberpunkMatrix() })
            MediaFilter.NONE -> Unit
        }
        val (width, height) = readVideoSize(request.input)
        when (request.aspect) {
            AspectRatio.ORIGINAL -> Unit
            AspectRatio.PORTRAIT -> effects.add(centerCropForRatio(width, height, 9f / 16f))
            AspectRatio.LANDSCAPE -> effects.add(centerCropForRatio(width, height, 16f / 9f))
            AspectRatio.SQUARE -> effects.add(centerCropForRatio(width, height, 1f))
        }
        request.textOverlay?.takeIf { it.text.isNotBlank() }?.let {
            val styled = android.text.SpannableString(it.text).apply {
                setSpan(
                    android.text.style.AbsoluteSizeSpan(it.fontSizeSp, true),
                    0,
                    length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE),
                    0,
                    length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            effects.add(OverlayEffect(listOf(TextOverlay.createStaticTextOverlay(styled))))
        }
        return effects
    }

    private fun readVideoSize(uri: Uri): Pair<Int, Int> {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            width to height
        } finally {
            retriever.release()
        }
    }

    private fun centerCropForRatio(width: Int, height: Int, targetRatio: Float): Crop {
        if (width <= 0 || height <= 0) return Crop(-1f, 1f, -1f, 1f)
        val sourceRatio = width.toFloat() / height.toFloat()
        return if (sourceRatio > targetRatio) {
            val halfWidth = (targetRatio / sourceRatio).coerceIn(0.05f, 1f)
            Crop(-halfWidth, halfWidth, -1f, 1f)
        } else {
            val halfHeight = (sourceRatio / targetRatio).coerceIn(0.05f, 1f)
            Crop(-1f, 1f, -halfHeight, halfHeight)
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): ExportedMedia {
        val displayName = "IAC33_\${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/IAC33")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("No se pudo crear el destino de imagen")
        try {
            resolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: error("No se pudo escribir la imagen")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            return ExportedMedia(uri, "image/png", displayName)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveAudioToGallery(file: File): ExportedMedia {
        val displayName = "IAC33_AUDIO_" + System.currentTimeMillis() + ".m4a"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/IAC33")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("No se pudo crear el destino de audio")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
                ?: error("No se pudo escribir el audio")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }
            return ExportedMedia(uri, "audio/mp4", displayName)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun saveVideoToGallery(file: File): ExportedMedia {
        val displayName = "IAC33_\${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/IAC33")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("No se pudo crear el destino de vídeo")
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: error("No se pudo escribir el vídeo")
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
                return ExportedMedia(uri, "video/mp4", displayName)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val target = File(dir, displayName)
        file.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf("video/mp4"),
            null
        )
        return ExportedMedia(Uri.fromFile(target), "video/mp4", displayName)
    }

    private class FixedSpeedProvider(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = Long.MAX_VALUE
    }

    companion object {
        private fun sepiaMatrix() = floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f,
            0.349f, 0.686f, 0.168f, 0f,
            0.272f, 0.534f, 0.131f, 0f,
            0f, 0f, 0f, 1f
        )

        private fun vintageMatrix() = floatArrayOf(
            0.86f, 0.10f, 0.05f, 0f,
            0.05f, 0.82f, 0.08f, 0f,
            0.03f, 0.10f, 0.72f, 0f,
            0f, 0f, 0f, 1f
        )

        private fun cyberpunkMatrix() = floatArrayOf(
            0.72f, 0.05f, 0.18f, 0f,
            0.03f, 0.82f, 0.20f, 0f,
            0.18f, 0.08f, 0.92f, 0f,
            0f, 0f, 0f, 1f
        )
    }
}
