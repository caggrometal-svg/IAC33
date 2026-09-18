package cl.iac33.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

private data class MediaInfo(
    val uri: Uri,
    val mime: String,
    val durationMs: Long = 0L,
    val width: Int? = null,
    val height: Int? = null
)

@Composable
fun MultimediaPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var media by remember { mutableStateOf<MediaInfo?>(null) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var originalImage by remember { mutableStateOf<Bitmap?>(null) }
    var startSeconds by remember { mutableFloatStateOf(0f) }
    var endSeconds by remember { mutableFloatStateOf(1f) }
    var maxSeconds by remember { mutableFloatStateOf(1f) }
    var busy by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val mime = context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val info = readMediaInfo(context, uri, mime)
        media = info
        resultText = null
        if (mime.startsWith("image/")) {
            val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            originalImage = bitmap
            image = bitmap
        } else {
            image = null
            maxSeconds = max(1f, info.durationMs / 1000f)
            startSeconds = 0f
            endSeconds = maxSeconds
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Editor Multimedia", style = MaterialTheme.typography.headlineSmall)
                Text("Edición local de imágenes y recorte de vídeo.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { picker.launch(arrayOf("image/*", "video/*")) }) { Text("Abrir") }
        }
        Spacer(Modifier.height(10.dp))

        val selected = media
        if (selected == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Sin archivo seleccionado", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Selecciona una foto o vídeo para comenzar.")
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(selected.mime, style = MaterialTheme.typography.labelLarge)
                    if (selected.durationMs > 0L) {
                        Text("Duración: " + formatDuration(selected.durationMs))
                    } else if (selected.width != null && selected.height != null) {
                        Text("Tamaño: " + selected.width + " × " + selected.height)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (selected.mime.startsWith("image/") && image != null) {
                Image(
                    bitmap = image!!.asImageBitmap(),
                    contentDescription = "Vista previa",
                    modifier = Modifier.fillMaxWidth().height(300.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { image = rotateBitmap(image!!) }) { Text("Rotar") }
                    OutlinedButton(onClick = { image = grayscaleBitmap(image!!) }) { Text("B/N") }
                    OutlinedButton(onClick = { image = originalImage }) { Text("Reset") }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val file = saveImage(context, image!!)
                            withContext(Dispatchers.Main) {
                                busy = false
                                resultText = file.absolutePath
                                shareFile(context, file, "image/png")
                            }
                        }
                    },
                    enabled = !busy
                ) { Text(if (busy) "Guardando…" else "Guardar y compartir") }
            } else if (selected.mime.startsWith("video/")) {
                var videoReady by remember(selected.uri) { mutableStateOf(false) }
                var videoError by remember(selected.uri) { mutableStateOf<String?>(null) }
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setMediaController(MediaController(ctx))
                                setVideoURI(selected.uri)
                                setOnPreparedListener { player ->
                                    videoReady = true
                                    videoError = null
                                    player.isLooping = true
                                    player.start()
                                }
                                setOnErrorListener { _, what, extra ->
                                    videoReady = false
                                    videoError = "No se pudo reproducir el vídeo (error $what/$extra)"
                                    true
                                }
                            }
                        }
                    )
                    if (!videoReady && videoError == null) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    videoError?.let {
                        Text(
                            it,
                            modifier = Modifier.align(Alignment.Center).padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Inicio: " + formatDuration((startSeconds * 1000f).toLong()))
                Slider(
                    value = startSeconds,
                    onValueChange = { value -> startSeconds = value.coerceIn(0f, max(0.1f, endSeconds - 0.1f)) },
                    valueRange = 0f..max(1f, maxSeconds)
                )
                Text("Fin: " + formatDuration((endSeconds * 1000f).toLong()))
                Slider(
                    value = endSeconds,
                    onValueChange = { value -> endSeconds = value.coerceIn(startSeconds + 0.1f, max(startSeconds + 0.1f, maxSeconds)) },
                    valueRange = 0.1f..max(1f, maxSeconds)
                )
                Button(
                    onClick = {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val res = runCatching {
                                trimVideo(context, selected.uri, (startSeconds * 1000f).toLong(), (endSeconds * 1000f).toLong())
                            }
                            withContext(Dispatchers.Main) {
                                busy = false
                                res.onSuccess { file ->
                                    resultText = file.absolutePath
                                    shareFile(context, file, "video/mp4")
                                }.onFailure { error -> resultText = "Error: " + (error.message ?: "No se pudo recortar") }
                            }
                        }
                    },
                    enabled = !busy && endSeconds > startSeconds
                ) { Text(if (busy) "Procesando…" else "Recortar y compartir") }
            }

            resultText?.let {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Los archivos se procesan localmente dentro de IAC33.")
    }
}

private fun decodeBitmapForPreview(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    }
}

private fun readMediaInfo(context: Context, uri: Uri, mime: String): MediaInfo {
    if (!mime.startsWith("video/")) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        return MediaInfo(uri, mime, width = options.outWidth.takeIf { it > 0 }, height = options.outHeight.takeIf { it > 0 })
    }
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        MediaInfo(
            uri = uri,
            mime = mime,
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        )
    } finally {
        retriever.release()
    }
}

private fun rotateBitmap(bitmap: Bitmap): Bitmap {
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun grayscaleBitmap(bitmap: Bitmap): Bitmap {
    val output = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
    Canvas(output).drawBitmap(
        bitmap,
        0f,
        0f,
        Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
    )
    return output
}

private fun saveImage(context: Context, bitmap: Bitmap): File {
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
    dir.mkdirs()
    val file = File(dir, "IAC33_" + System.currentTimeMillis() + ".png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

private fun trimVideo(context: Context, uri: Uri, startMs: Long, endMs: Long): File {
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    dir.mkdirs()
    val output = File(dir, "IAC33_" + System.currentTimeMillis() + ".mp4")
    val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("No se pudo abrir el vídeo")
    val extractor = MediaExtractor()
    val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    try {
        pfd.use { descriptor ->
            extractor.setDataSource(descriptor.fileDescriptor)
            val tracks = mutableListOf<Pair<Int, Int>>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) tracks += i to muxer.addTrack(format)
            }
            check(tracks.isNotEmpty()) { "El vídeo no contiene pistas compatibles" }
            muxer.start()
            val info = android.media.MediaCodec.BufferInfo()
            for ((sourceTrack, muxTrack) in tracks) {
                extractor.unselectTrack(sourceTrack)
                extractor.selectTrack(sourceTrack)
                extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val trackFormat = extractor.getTrackFormat(sourceTrack)
                val maxInputSize = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1024 * 1024)
                } else {
                    8 * 1024 * 1024
                }
                val buffer = java.nio.ByteBuffer.allocate(maxInputSize)
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    val time = extractor.sampleTime
                    if (size < 0 || time > endMs * 1000L) break
                    if (time >= startMs * 1000L) {
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = time - startMs * 1000L
                        info.flags = extractor.sampleFlags
                        muxer.writeSampleData(muxTrack, buffer, info)
                    }
                    extractor.advance()
                }
                extractor.unselectTrack(sourceTrack)
            }
            muxer.stop()
        }
    } catch (error: Throwable) {
        runCatching { muxer.stop() }
        output.delete()
        throw error
    } finally {
        extractor.release()
        muxer.release()
    }
    return output
}

private fun shareFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Compartir archivo IAC33"))
}

private fun formatDuration(ms: Long): String {
    val total = max(0L, ms) / 1000L
    return "%02d:%02d".format(total / 60L, total % 60L)
}
