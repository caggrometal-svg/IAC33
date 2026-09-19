package cl.iac33.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Crop
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import cl.iac33.app.multimedia.AspectRatio
import cl.iac33.app.multimedia.ExportRequest
import cl.iac33.app.multimedia.MediaExportEngine
import cl.iac33.app.multimedia.MediaFilter
import cl.iac33.app.multimedia.TextOverlaySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class StudioMode { EDITOR, AI }
private enum class StudioMediaKind { IMAGE, VIDEO, AUDIO }
private data class TimelineLayer(
    val id: Int,
    val name: String,
    val kind: StudioMediaKind,
    val uri: Uri? = null
)

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MultimediaPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { MediaExportEngine(context) }

    var mode by remember { mutableStateOf(StudioMode.EDITOR) }
    var source by remember { mutableStateOf<Uri?>(null) }
    var sourceKind by remember { mutableStateOf<StudioMediaKind?>(null) }
    var joinSources by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(Long.MAX_VALUE) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var speed by remember { mutableFloatStateOf(1f) }
    var filter by remember { mutableStateOf(MediaFilter.NONE) }
    var aspect by remember { mutableStateOf(AspectRatio.ORIGINAL) }
    var overlayText by remember { mutableStateOf("") }
    var exportBusy by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Listo") }
    var aiPrompt by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiResult by remember { mutableStateOf<String?>(null) }
    var aiMode by remember { mutableStateOf("Imagen") }

    val layers = remember(source, audioUri, overlayText) {
        buildList {
            source?.let { add(TimelineLayer(1, "Medio principal", sourceKind ?: StudioMediaKind.VIDEO, it)) }
            audioUri?.let { add(TimelineLayer(2, "Pista de audio", StudioMediaKind.AUDIO, it)) }
            if (overlayText.isNotBlank()) add(TimelineLayer(3, "Texto", StudioMediaKind.IMAGE))
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        source = uri
        val mime = context.contentResolver.getType(uri).orEmpty()
        sourceKind = if (mime.startsWith("image/")) StudioMediaKind.IMAGE else StudioMediaKind.VIDEO
        durationMs = if (sourceKind == StudioMediaKind.VIDEO) readDurationMs(context, uri) else 0L
        startMs = 0L
        endMs = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        status = "Medio cargado"
    }

    val joinPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size < 2) {
            if (uris.isNotEmpty()) status = "Selecciona al menos dos vídeos"
            return@rememberLauncherForActivityResult
        }
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        joinSources = uris
        status = uris.size.toString() + " vídeos preparados para unir"
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        audioUri = uri
        status = "Pista secundaria cargada"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mode == StudioMode.EDITOR,
                            onClick = { mode = StudioMode.EDITOR },
                            label = { Text("Editor Profesional") }
                        )
                        FilterChip(
                            selected = mode == StudioMode.AI,
                            onClick = { mode = StudioMode.AI },
                            label = { Text("Estudio IA") }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 10.dp)
                .navigationBarsPadding()
        ) {
            if (mode == StudioMode.EDITOR) {
                EditorStudio(
                    context = context,
                    engine = engine,
                    source = source,
                    sourceKind = sourceKind,
                    audioUri = audioUri,
                    durationMs = durationMs,
                    startMs = startMs,
                    endMs = endMs,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    speed = speed,
                    filter = filter,
                    aspect = aspect,
                    overlayText = overlayText,
                    layers = layers,
                    exportBusy = exportBusy,
                    exportProgress = exportProgress,
                    status = status,
                    onOpen = { mediaPicker.launch(arrayOf("image/*", "video/*")) },
                    onJoin = { joinPicker.launch(arrayOf("video/*")) },
                    joinCount = joinSources.size,
                    joinAvailable = joinSources.size >= 2,
                    onAudio = { audioPicker.launch(arrayOf("audio/*")) },
                    onExtractAudio = {
                        val uri = source ?: return@EditorStudio
                        if (sourceKind == StudioMediaKind.VIDEO) {
                            exportBusy = true
                            status = "Extrayendo audio…"
                            scope.launch {
                                runCatching { engine.extractAudio(uri) }
                                    .onSuccess { status = "Audio guardado: " + it.displayName; Toast.makeText(context, "Audio guardado en Música/IAC33", Toast.LENGTH_LONG).show() }
                                    .onFailure { status = "Error de audio"; Toast.makeText(context, it.message ?: "No se pudo extraer el audio", Toast.LENGTH_LONG).show() }
                                exportBusy = false
                            }
                        }
                    },
                    onStartMs = { startMs = it },
                    onEndMs = { endMs = it },
                    onBrightness = { brightness = it },
                    onContrast = { contrast = it },
                    onSaturation = { saturation = it },
                    onSpeed = { speed = it },
                    onFilter = { filter = it },
                    onAspect = { aspect = it },
                    onOverlayText = { overlayText = it.take(140) },
                    onJoinExport = {
                        if (joinSources.size < 2) return@EditorStudio
                        exportBusy = true
                        exportProgress = 5
                        status = "Uniendo vídeos…"
                        scope.launch {
                            runCatching { engine.joinVideos(joinSources) { exportProgress = it } }
                                .onSuccess { status = "Vídeos unidos: " + it.displayName; Toast.makeText(context, "Vídeo unido guardado en la galería", Toast.LENGTH_LONG).show() }
                                .onFailure { status = "Error al unir vídeos"; Toast.makeText(context, it.message ?: "No se pudieron unir los vídeos", Toast.LENGTH_LONG).show() }
                            exportBusy = false
                        }
                    },
                    onExport = {
                        val uri = source ?: return@EditorStudio
                        if (sourceKind == StudioMediaKind.VIDEO) {
                            exportBusy = true
                            exportProgress = 5
                            status = "Exportando vídeo…"
                            scope.launch {
                                val result = runCatching {
                                    engine.exportVideo(
                                        ExportRequest(
                                            input = uri,
                                            startMs = startMs,
                                            endMs = endMs.takeIf { it != Long.MAX_VALUE },
                                            speed = speed,
                                            brightness = brightness,
                                            contrast = contrast,
                                            saturation = saturation,
                                            filter = filter,
                                            aspect = aspect,
                                            textOverlay = overlayText.takeIf { it.isNotBlank() }?.let { TextOverlaySpec(it) },
                                            secondaryAudio = audioUri
                                        )
                                    ) { exportProgress = it }
                                }
                                exportBusy = false
                                result.onSuccess {
                                    status = "Exportado: ${it.displayName}"
                                    Toast.makeText(context, "Guardado en la galería", Toast.LENGTH_LONG).show()
                                }.onFailure {
                                    status = "Error de exportación"
                                    Toast.makeText(context, it.message ?: "No se pudo exportar", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            exportBusy = true
                            status = "Procesando imagen…"
                            scope.launch {
                                val result = runCatching {
                                    engine.saveImage(source = uri, brightness = brightness, contrast = contrast, saturation = saturation, filter = filter, aspect = aspect, textOverlay = overlayText.takeIf { it.isNotBlank() }?.let { TextOverlaySpec(it) })
                                }
                                exportBusy = false
                                result.onSuccess {
                                    status = "Exportado: ${it.displayName}"
                                    Toast.makeText(context, "Imagen guardada en la galería", Toast.LENGTH_LONG).show()
                                }.onFailure {
                                    status = "Error de imagen"
                                    Toast.makeText(context, it.message ?: "No se pudo exportar", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                )
            } else {
                AiStudio(
                    context = context,
                    prompt = aiPrompt,
                    aiMode = aiMode,
                    busy = aiBusy,
                    result = aiResult,
                    onPrompt = { aiPrompt = it.take(8000) },
                    onMode = { aiMode = it },
                    onGenerate = {
                        if (aiPrompt.isBlank()) return@AiStudio
                        aiBusy = true
                        aiResult = null
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching {
                                requestAiStudio(context, aiMode, aiPrompt, source, BuildConfig.IAC33_BACKEND_URL)
                            }
                            withContext(Dispatchers.Main) {
                                aiBusy = false
                                result.onSuccess { aiResult = it }
                                    .onFailure { aiResult = it.message ?: "No se pudo completar la generación" }
                            }
                        }
                    },
                    onImportResult = { generatedUri ->
                        source = generatedUri
                        sourceKind = when (context.contentResolver.getType(generatedUri).orEmpty().substringBefore('/')) {
                            "image" -> StudioMediaKind.IMAGE
                            "video" -> StudioMediaKind.VIDEO
                            else -> sourceKind
                        }
                        mode = StudioMode.EDITOR
                        status = "Resultado importado al editor"
                    }
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun EditorStudio(
    context: Context,
    engine: MediaExportEngine,
    source: Uri?,
    sourceKind: StudioMediaKind?,
    audioUri: Uri?,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    speed: Float,
    filter: MediaFilter,
    aspect: AspectRatio,
    overlayText: String,
    layers: List<TimelineLayer>,
    exportBusy: Boolean,
    exportProgress: Int,
    status: String,
    onOpen: () -> Unit,
    onJoin: () -> Unit,
    joinCount: Int,
    joinAvailable: Boolean,
    onAudio: () -> Unit,
    onExtractAudio: () -> Unit,
    onStartMs: (Long) -> Unit,
    onEndMs: (Long) -> Unit,
    onBrightness: (Float) -> Unit,
    onContrast: (Float) -> Unit,
    onSaturation: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onFilter: (MediaFilter) -> Unit,
    onAspect: (AspectRatio) -> Unit,
    onOverlayText: (String) -> Unit,
    onJoinExport: () -> Unit,
    onExport: () -> Unit
) {
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(source, speed) {
        if (sourceKind == StudioMediaKind.VIDEO && source != null) {
            player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(source))
                prepare()
                playWhenReady = true
                setPlaybackSpeed(speed)
            }
        }
        onDispose {
            player?.release()
            player = null
        }
    }

    val previewPlayer = player
    val previewSize = remember(source) { source?.let { readVideoSize(context, it) } ?: (0 to 0) }
    previewPlayer?.let {
        it.setPlaybackSpeed(speed)
        it.setVideoEffects(
            buildPreviewEffects(
                brightness, contrast, saturation, filter, aspect, overlayText,
                previewSize.first, previewSize.second
            )
        )
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Editor Profesional", style = MaterialTheme.typography.headlineSmall)
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onOpen) { Text("Importar") }
                OutlinedButton(onClick = onJoin) { Text(if (joinCount >= 2) "Unir (" + joinCount + ")" else "Unir vídeos") }
            }
        }

        Card(
            Modifier.fillMaxWidth().height(230.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (previewPlayer != null) {
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply { player = previewPlayer; useController = true } },
                        modifier = Modifier.fillMaxSize(),
                        update = { view -> view.player = previewPlayer }
                    )
                } else if (sourceKind == StudioMediaKind.IMAGE && source != null) {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.ImageView(ctx).apply {
                                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                setImageURI(source)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { view ->
                            view.setImageURI(source)
                            view.scaleType = if (aspect == AspectRatio.ORIGINAL)
                                android.widget.ImageView.ScaleType.FIT_CENTER
                            else
                                android.widget.ImageView.ScaleType.CENTER_CROP
                            val matrix = android.graphics.ColorMatrix().apply {
                                val c = 1f + contrast.coerceIn(-1f, 1f)
                                val t = (1f - c) * 127.5f + brightness.coerceIn(-1f, 1f) * 255f
                                val base = floatArrayOf(
                                    c,0f,0f,0f,t,
                                    0f,c,0f,0f,t,
                                    0f,0f,c,0f,t,
                                    0f,0f,0f,1f,0f
                                )
                                set(base)
                                postConcat(android.graphics.ColorMatrix().apply {
                                    setSaturation(saturation.coerceIn(0f, 3f))
                                })
                            }
                            view.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
                        }
                    )
                } else {
                    Text("Importa una foto o vídeo", color = Color.White)
                }
                if (overlayText.isNotBlank()) {
                    Text(
                        overlayText,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ToolPill("Luz") { }
            ToolPill("Color") { }
            ToolPill("Filtros") { }
            ToolPill("Recorte") { }
            ToolPill("Texto") { }
            ToolPill("Audio") { }
            ToolPill("Velocidad") { }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Línea de tiempo · ${formatDuration(durationMs)}", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    layers.forEach { layer ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                            Column(Modifier.padding(10.dp)) {
                                Text(layer.name, color = Color.White)
                                Text(layer.kind.name, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (durationMs > 0L) {
                    Text("Inicio ${formatDuration(startMs)}")
                    Slider(
                        value = startMs.toFloat(),
                        onValueChange = { onStartMs(it.toLong().coerceIn(0L, max(0L, endMs - 100L))) },
                        valueRange = 0f..durationMs.toFloat()
                    )
                    Text("Fin ${formatDuration(endMs.coerceAtMost(durationMs))}")
                    Slider(
                        value = endMs.toFloat().coerceAtMost(durationMs.toFloat()),
                        onValueChange = { onEndMs(it.toLong().coerceIn(startMs + 100L, durationMs)) },
                        valueRange = 0f..durationMs.toFloat()
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Imagen", style = MaterialTheme.typography.titleMedium)
                LabeledSlider("Brillo", brightness, -1f..1f, onBrightness)
                LabeledSlider("Contraste", contrast, -1f..1f, onContrast)
                LabeledSlider("Saturación", saturation, 0f..3f, onSaturation)

                Text("Filtros")
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        MediaFilter.NONE,
                        MediaFilter.BW,
                        MediaFilter.SEPIA,
                        MediaFilter.VINTAGE,
                        MediaFilter.CYBERPUNK
                    ).forEach { candidate ->
                        FilterChip(
                            selected = filter == candidate,
                            onClick = { onFilter(candidate) },
                            label = { Text(candidate.name) }
                        )
                    }
                }

                Text("Formato")
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AspectRatio.values().forEach { candidate ->
                        FilterChip(
                            selected = aspect == candidate,
                            onClick = { onAspect(candidate) },
                            label = { Text(candidate.label) }
                        )
                    }
                }

                Text("Velocidad")
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0.25f, 0.5f, 1f, 2f, 4f).forEach { candidate ->
                        FilterChip(
                            selected = speed == candidate,
                            onClick = { onSpeed(candidate) },
                            label = { Text("${candidate}x") }
                        )
                    }
                }

                OutlinedTextField(
                    value = overlayText,
                    onValueChange = onOverlayText,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Texto superpuesto") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAudio) { Text(if (audioUri == null) "Añadir audio" else "Cambiar audio") }
                    if (sourceKind == StudioMediaKind.VIDEO) OutlinedButton(onClick = onExtractAudio, enabled = !exportBusy) { Text("Extraer audio") }
                    if (joinAvailable) OutlinedButton(onClick = onJoinExport, enabled = !exportBusy) { Text("Unir seleccionados") }
                    Button(onClick = onExport, enabled = source != null && !exportBusy) {
                        Text(if (exportBusy) "Exportando ${exportProgress}%" else "Exportar a galería")
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            if (sourceKind == StudioMediaKind.IMAGE) "Procesamiento local · recorte + redimensionado + ajustes + filtros + texto · PNG" else "Procesamiento local · Media3 Transformer · exportación MP4/AAC",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@OptIn(UnstableApi::class)
private fun buildPreviewEffects(
    brightness: Float,
    contrast: Float,
    saturation: Float,
    filter: MediaFilter,
    aspect: AspectRatio,
    text: String,
    width: Int,
    height: Int
): List<androidx.media3.common.Effect> {
    val effects = mutableListOf<androidx.media3.common.Effect>()
    if (brightness != 0f) {
        effects.add(Brightness(brightness.coerceIn(-1f, 1f)))
    }
    if (contrast != 0f) {
        effects.add(Contrast(contrast.coerceIn(-1f, 1f)))
    }
    if (saturation != 1f) {
        effects.add(
            HslAdjustment.Builder()
                .adjustSaturation(((saturation - 1f) * 100f).coerceIn(-100f, 100f))
                .build()
        )
    }
    when (filter) {
        MediaFilter.BW -> effects.add(RgbFilter.createGrayscaleFilter())
        MediaFilter.SEPIA -> effects.add(
            RgbMatrix { _, _ ->
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f,
                    0.349f, 0.686f, 0.168f, 0f,
                    0.272f, 0.534f, 0.131f, 0f,
                    0f, 0f, 0f, 1f
                )
            }
        )
        MediaFilter.VINTAGE -> effects.add(
            RgbMatrix { _, _ ->
                floatArrayOf(
                    0.86f, 0.10f, 0.05f, 0f,
                    0.05f, 0.82f, 0.08f, 0f,
                    0.03f, 0.10f, 0.72f, 0f,
                    0f, 0f, 0f, 1f
                )
            }
        )
        MediaFilter.CYBERPUNK -> effects.add(
            RgbMatrix { _, _ ->
                floatArrayOf(
                    0.72f, 0.05f, 0.18f, 0f,
                    0.03f, 0.82f, 0.20f, 0f,
                    0.18f, 0.08f, 0.92f, 0f,
                    0f, 0f, 0f, 1f
                )
            }
        )
        MediaFilter.NONE -> Unit
    }
    when (aspect) {
        AspectRatio.ORIGINAL -> Unit
        AspectRatio.PORTRAIT -> effects.add(centerCropForRatio(width, height, 9f / 16f))
        AspectRatio.LANDSCAPE -> effects.add(centerCropForRatio(width, height, 16f / 9f))
        AspectRatio.SQUARE -> effects.add(centerCropForRatio(width, height, 1f))
    }
    if (text.isNotBlank()) {
        val overlay = android.text.SpannableString(text).apply {
            setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE),
                0,
                length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        effects.add(OverlayEffect(listOf(TextOverlay.createStaticTextOverlay(overlay))))
    }
    return effects
}

private fun readVideoSize(context: Context, uri: Uri): Pair<Int, Int> {
    val retriever = android.media.MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val width = retriever.extractMetadata(
            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        )?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(
            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toIntOrNull() ?: 0
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

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit
) {
    Text("$label: $value")
    Slider(value = value, onValueChange = onValue, valueRange = range)
}

@Composable
private fun ToolPill(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.width(96.dp)) {
        Text(label)
    }
}

@Composable
private fun AiStudio(
    context: Context,
    prompt: String,
    aiMode: String,
    busy: Boolean,
    result: String?,
    onPrompt: (String) -> Unit,
    onMode: (String) -> Unit,
    onGenerate: () -> Unit,
    onImportResult: (Uri) -> Unit
) {
    val scope = rememberCoroutineScope()
    val modes = listOf("Generador de imágenes IA", "Imagen→Imagen", "Generador de vídeos IA", "Imagen→Vídeo", "Vídeo→Vídeo", "TTS")

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Estudio IA", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Generación remota desacoplada del editor local. Los proveedores se mantienen en el backend.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            modes.forEach {
                FilterChip(selected = aiMode == it, onClick = { onMode(it) }, label = { Text(it) })
            }
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = onPrompt,
            modifier = Modifier.fillMaxWidth().height(140.dp),
            label = { Text(if (aiMode == "TTS") "Texto" else "Prompt") }
        )
        Button(onClick = onGenerate, enabled = prompt.isNotBlank() && !busy) {
            Text(if (busy) "Generando…" else "Generar")
        }
        result?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Resultado", style = MaterialTheme.typography.titleMedium)
                    Text(it)
                    val maybeUrl = extractAssetUrl(it, BuildConfig.IAC33_BACKEND_URL)
                    if (maybeUrl != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val uri = downloadAssetToGallery(context, maybeUrl)
                                    if (uri != null) onImportResult(uri)
                                }
                            }
                        ) { Text("Guardar / importar") }
                    }
                }
            }
        }
    }
}

private suspend fun requestAiStudio(
    context: Context,
    mode: String,
    prompt: String,
    source: Uri?,
    backend: String
): String = withContext(Dispatchers.IO) {
    val endpoint = when (mode) {
        "Generador de imágenes IA" -> "/v1/ai/text-to-image"
        "Imagen→Imagen" -> "/v1/ai/image-to-image"
        "Generador de vídeos IA" -> "/v1/ai/text-to-video"
        "Imagen→Vídeo" -> "/v1/ai/image-to-video"
        "Vídeo→Vídeo" -> "/v1/ai/video-to-video"
        "TTS" -> "/v1/ai/text-to-speech"
        else -> error("Modo IA no soportado")
    }

    val json = JSONObject().apply {
        when (mode) {
            "TTS" -> put("text", prompt)
            else -> put("prompt", prompt)
        }
        if (mode.contains("Imagen") && mode != "Generador de imágenes IA" && source != null) {
            put("image", uriAsDataUri(context, source))
        }
        if (mode.contains("Vídeo") || mode == "Generador de vídeos IA") {
            put("ratio", "1280:720")
            put("duration", 5)
        }
        if (mode == "Vídeo→Vídeo") {
            require(source != null) { "Importa un vídeo para transformarlo" }
            require(context.contentResolver.getType(source).orEmpty().startsWith("video/")) { "La fuente debe ser un vídeo" }
            put("video", uriAsDataUri(context, source, 18 * 1024 * 1024))
        }
    }

    val response = postJson(backend.trimEnd('/') + endpoint, json.toString())
    if (response.status !in 200..299) error(response.body)
    if (mode.contains("Vídeo")) {
        val initial = JSONObject(response.body)
        val jobId = initial.getString("jobId")
        return@withContext pollMediaJob(backend, jobId)
    }
    response.body
}

private data class HttpResponse(val status: Int, val body: String)

private fun postJson(url: String, body: String): HttpResponse {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 60_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "IAC33/2.0 Android")
    }
    return try {
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        HttpResponse(connection.responseCode, (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() })
    } finally {
        connection.disconnect()
    }
}

private fun pollMediaJob(backend: String, jobId: String): String {
    repeat(96) {
        val connection = (URL(backend.trimEnd('/') + "/v1/ai/media/jobs/" + Uri.encode(jobId)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "IAC33/2.0 Android")
        }
        val body = try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val json = JSONObject(body)
        when (json.optString("status").uppercase()) {
            "SUCCEEDED" -> return backend.trimEnd('/') + json.optString("assetUrl")
            "FAILED" -> error("La generación de vídeo falló")
        }
        Thread.sleep(5_000L)
    }
    error("Tiempo de espera agotado")
}

private fun uriAsDataUri(context: Context, uri: Uri, maxBytes: Int = 12 * 1024 * 1024): String {
    val mime = context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("No se pudo leer el archivo")
    require(bytes.size <= maxBytes) { "El archivo supera el límite de " + (maxBytes / 1024 / 1024) + " MB" }
    require(mime.startsWith("image/") || mime.startsWith("video/")) { "Tipo de medio no compatible" }
    return "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}

private suspend fun downloadAssetToGallery(context: Context, url: String): Uri? = withContext(Dispatchers.IO) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 90_000
        setRequestProperty("Accept", "*/*")
        setRequestProperty("User-Agent", "IAC33/2.0 Android")
    }
    try {
        if (connection.responseCode !in 200..299) return@withContext null
        val mime = connection.contentType.orEmpty().substringBefore(';').ifBlank { "application/octet-stream" }
        val isVideo = mime.startsWith("video/")
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val name = "IAC33_AI_${System.currentTimeMillis()}" + if (isVideo) ".mp4" else ".png"
        val values = ContentValues().apply {
            if (isVideo) {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, if (mime == "application/octet-stream") "video/mp4" else mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/IAC33")
            } else {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, if (mime == "application/octet-stream") "image/png" else mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/IAC33")
            }
        }
        val uri = context.contentResolver.insert(collection, values) ?: return@withContext null
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                connection.inputStream.use { input -> input.copyTo(output) }
            } ?: error("No se pudo guardar el resultado")
            uri
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    } finally {
        connection.disconnect()
    }
}

private fun extractAssetUrl(body: String, backend: String): String? {
    return runCatching {
        val json = JSONObject(body)
        val assetUrl = json.optString("assetUrl")
        if (assetUrl.isBlank()) null
        else if (assetUrl.startsWith("http")) assetUrl else backend.trimEnd('/') + assetUrl
    }.getOrNull()
}

private fun readDurationMs(context: Context, uri: Uri): Long {
    val retriever = android.media.MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally {
        retriever.release()
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = max(0L, ms) / 1000L
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}
