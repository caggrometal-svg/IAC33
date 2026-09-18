package cl.iac33.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cl.iac33.app.ai.AiEngineImpl
import cl.iac33.app.core.AiMessage
import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.OperationResult
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.connectivity.ConnectivityMonitor
import cl.iac33.app.core.connectivity.ConnectivityStatus
import cl.iac33.app.core.location.LocationReader
import cl.iac33.app.core.location.LocationSnapshot
import cl.iac33.app.seismic.SeismicClient
import cl.iac33.app.seismic.SeismicEvent
import cl.iac33.app.seismic.SeismicEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

data class ChatLine(val role: String, val text: String)

private val sections = listOf("IA", "Sismos", "C33", "Multimedia", "Más")
private val sectionGlyphs = listOf("AI", "EQ", "C33", "MED", "•••")

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            // ON_RESUME refreshes the position after the permission dialog.
            // Avoid Activity recreation, which can blank/restart the Compose surface.
            Unit
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settingsStore = remember { SettingsStore(this@MainActivity) }
            var settings by remember { mutableStateOf(settingsStore.load()) }
            var selected by rememberSaveable { mutableIntStateOf(0) }
            var connectivityStatus by remember { mutableStateOf(ConnectivityStatus.OFFLINE) }
            var location by remember { mutableStateOf<LocationSnapshot?>(null) }
            val connectivityMonitor = remember { ConnectivityMonitor(this@MainActivity) }
            val locationReader = remember { LocationReader(this@MainActivity) }
            val lifecycleOwner = LocalLifecycleOwner.current
            val locationScope = rememberCoroutineScope()
            val chatStore = remember { ChatStore(this@MainActivity) }
            var chatLines by remember {
                mutableStateOf(
                    chatStore.load().ifEmpty {
                        listOf(ChatLine("assistant", "IAC33 listo. El núcleo local está disponible y puede usar el backend cuando exista conectividad."))
                    }
                )
            }

            fun saveSettings(next: AppSettings) {
                settings = next
                settingsStore.save(next)
            }

            DisposableEffect(Unit) {
                connectivityMonitor.start { status -> connectivityStatus = status }
                onDispose { connectivityMonitor.stop() }
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        locationScope.launch { location = locationReader.readCurrentOrLastKnown() }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            IAC33Theme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("IAC33")
                                    Text(
                                        "Núcleo operativo · " + connectivityStatus.name,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            sections.forEachIndexed { index, label ->
                                NavigationBarItem(
                                    selected = if (index == 4) selected >= 4 else selected == index,
                                    onClick = { selected = index },
                                    icon = {
                                        Text(
                                            sectionGlyphs[index],
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    label = {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 12.dp)
                            .imePadding()
                    ) {
                        when (selected) {
                            0 -> AiPanel(
                                lines = chatLines,
                                settings = settings,
                                onLinesChange = {
                                    chatLines = it
                                    chatStore.save(it)
                                }
                            )
                            1 -> SeismicPanel(settings)
                            2 -> C33Panel(chatCount = chatLines.size)
                            3 -> MultimediaPanel()
                            4 -> MorePanel(
                                onOpen = { destination ->
                                    selected = destination
                                    if (destination == 6 && !hasLocationPermission()) requestLocationPermission()
                                }
                            )
                            5 -> RedPanel(connectivityStatus)
                            6 -> GpsPanel(location, settings.mapZoom)
                            7 -> ControlPanel(onRestart = { recreate() })
                            8 -> SettingsPanel(settings, ::saveSettings)
                        }
                    }
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }
}

private const val AI_REQUEST_TIMEOUT_MS = 10_000L
private const val AI_UI_TIMEOUT_MS = 12_000L

@Composable
private fun AiPanel(
    lines: List<ChatLine>,
    settings: AppSettings,
    onLinesChange: (List<ChatLine>) -> Unit
) {
    val engine = remember(settings.aiLocalFirst) { AiEngineImpl(settings.aiLocalFirst) }
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val quickPrompts = listOf("Estado del sistema", "Analiza Sismos", "¿Cómo funciona GPS?", "Abrir Multimedia")

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    fun sendMessage(raw: String) {
        val prompt = raw.trim()
        if (prompt.isEmpty() || busy) return
        val updated = lines + ChatLine("user", prompt)
        onLinesChange(updated)
        draft = ""
        busy = true

        scope.launch {
            try {
                val messages = listOf(
                    AiMessage(
                        "system",
                        "Responde siempre en español. Sé claro, directo, natural y útil. Puedes explicar las funciones reales de IAC33. Nunca presentes una estimación sísmica como predicción exacta."
                    )
                ) + updated.takeLast(16).map { AiMessage(it.role, it.text.take(ChatStore.MAX_MESSAGE_CHARS)) }

                val result = withTimeout(AI_UI_TIMEOUT_MS) {
                    engine.generate(
                        AiRequest(
                            conversationId = UUID.randomUUID().toString(),
                            messages = messages,
                            timeoutMs = AI_REQUEST_TIMEOUT_MS
                        )
                    )
                }

                when (result) {
                    is OperationResult.Success -> {
                        val value = result.value
                        val modelLabel = listOfNotNull(value.provider, value.model)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                        val body = value.text.orEmpty().ifBlank { "Respuesta vacía." }
                        val answer = if (modelLabel.isBlank()) body else modelLabel + "\n" + body
                        onLinesChange(updated + ChatLine("assistant", answer))
                    }
                    is OperationResult.Failure -> {
                        val detail = when (result.error) {
                            OperationError.RATE_LIMIT -> "El proveedor remoto está limitado."
                            OperationError.NETWORK -> "No hay conexión con el backend."
                            OperationError.TIMEOUT -> "El proveedor tardó demasiado."
                            else -> result.message
                        }
                        onLinesChange(updated + ChatLine("assistant", "IA en modo de respaldo: " + detail))
                    }
                }
            } catch (error: TimeoutCancellationException) {
                onLinesChange(updated + ChatLine("assistant", "IA: tiempo de espera agotado (12 s)."))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onLinesChange(updated + ChatLine("assistant", "IA: " + (error.message ?: "error interno")))
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Centro IA", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (settings.aiLocalFirst) "Local primero · respaldo remoto" else "Remoto primero · respaldo local",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(if (busy) "Procesando" else "Listo") }
            )
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(quickPrompts) { prompt ->
                AssistChip(onClick = { sendMessage(prompt) }, label = { Text(prompt) })
            }
        }
        Spacer(Modifier.height(6.dp))
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF030507)),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(lines) { line ->
                    val isUser = line.role == "user"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(0.88f),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isUser) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                            shape = RoundedCornerShape(
                                topStart = 20.dp,
                                topEnd = 20.dp,
                                bottomStart = if (isUser) 20.dp else 6.dp,
                                bottomEnd = if (isUser) 6.dp else 20.dp
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isUser) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                }
                            )
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    Text(
                                        "●",
                                        color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Text(
                                        if (isUser) "TÚ" else "IAC33",
                                        color = if (isUser) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = line.text,
                                    color = if (isUser) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
                if (busy) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp),
                                strokeWidth = 2.dp
                            )
                            Text("IAC33 está procesando…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(ChatStore.MAX_MESSAGE_CHARS) },
                modifier = Modifier.weight(1f),
                enabled = !busy,
                placeholder = { Text("Escribe a IAC33…") },
                maxLines = 4
            )
            Button(
                onClick = { sendMessage(draft) },
                enabled = !busy && draft.isNotBlank(),
                modifier = Modifier.height(56.dp)
            ) { Text(if (busy) "…" else "Enviar") }
        }
    }
}

@Composable
private fun MorePanel(onOpen: (Int) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Más herramientas", style = MaterialTheme.typography.headlineSmall)
            Text("Funciones secundarias agrupadas para mantener la navegación limpia.", style = MaterialTheme.typography.bodySmall)
        }
        item { ToolCard("Red", "Conectividad y prueba del backend", "NET") { onOpen(5) } }
        item { ToolCard("GPS", "Ubicación y mapa del dispositivo", "GPS") { onOpen(6) } }
        item { ToolCard("Control", "Estado local y Bridge OTA", "CTL") { onOpen(7) } }
        item { ToolCard("Configuración", "Preferencias y parámetros", "CFG") { onOpen(8) } }
    }
}

@Composable
private fun ToolCard(title: String, description: String, glyph: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(glyph, style = MaterialTheme.typography.labelLarge)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onClick) { Text("Abrir") }
        }
    }
}

@Composable
private fun C33Panel(chatCount: Int) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Núcleo C33", style = MaterialTheme.typography.headlineSmall)
            Text("Estado de los componentes locales y del puente.", style = MaterialTheme.typography.bodySmall)
        }
        items(
            listOf(
                "IA" to "Asistente remoto + respaldo local",
                "Memoria" to "Chat persistido en el dispositivo",
                "Bridge" to "Cola de comandos y OTA protegida",
                "Mapas" to "Render OSM embebido sin API key",
                "Sismicidad" to "CSN + datos geográficos USGS"
            )
        ) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(item.first, style = MaterialTheme.typography.titleMedium)
                    Text(item.second)
                }
            }
        }
        item { Text("Mensajes persistidos: " + chatCount) }
    }
}

@Composable
private fun RedPanel(connectivityStatus: ConnectivityStatus) {
    val scope = rememberCoroutineScope()
    var diagnostics by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Text("Red", style = MaterialTheme.typography.headlineSmall)
        Text("Diagnóstico por servicio: dispositivo, backend, mapas y sismicidad.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Conectividad del dispositivo", style = MaterialTheme.typography.titleMedium)
                Text(connectivityStatus.name)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val results = listOf(
                                "Backend" to BuildConfig.IAC33_BACKEND_URL.trimEnd('/') + "/health",
                                "Mapas OSM" to "https://tile.openstreetmap.org/5/10/12.png",
                                "USGS" to "https://earthquake.usgs.gov/"
                            ).map { (label, url) ->
                                label + ": " + probeHttp(url)
                            }
                            diagnostics = results
                            busy = false
                        }
                    },
                    enabled = !busy
                ) { Text(if (busy) "Comprobando…" else "Diagnóstico completo") }
                if (diagnostics.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    diagnostics.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun GpsPanel(location: LocationSnapshot?, zoom: Int) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("GPS", style = MaterialTheme.typography.headlineSmall)
            Text("Ubicación del dispositivo y mapa.", style = MaterialTheme.typography.bodyMedium)
        }
        if (location == null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("GPS sin posición disponible", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("Concede el permiso de ubicación y vuelve a esta sección.")
                    }
                }
            }
        } else {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Ubicación actual", style = MaterialTheme.typography.titleMedium)
                        Text(String.format(Locale.US, "Latitud %.6f", location.latitude))
                        Text(String.format(Locale.US, "Longitud %.6f", location.longitude))
                        Text("Precisión " + (location.accuracyMeters?.let { String.format(Locale.US, "%.1f m", it) } ?: "n/d"))
                        Text("Fuente " + location.provider)
                    }
                }
            }
            item {
                IAC33Map(
                    centerLatitude = location.latitude,
                    centerLongitude = location.longitude,
                    zoom = zoom,
                    markers = listOf(
                        MapMarker(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            title = "Mi ubicación",
                            subtitle = "Precisión " + (location.accuracyMeters?.let { String.format(Locale.US, "%.1f m", it) } ?: "n/d")
                        )
                    )
                )
            }
        }
    }
}

@Composable
private fun SeismicPanel(settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val client = remember { SeismicClient(BuildConfig.IAC33_BACKEND_URL) }
    var events by remember { mutableStateOf<List<SeismicEvent>>(emptyList()) }
    var mapEvents by remember { mutableStateOf<List<SeismicEvent>>(emptyList()) }
    var forecast by remember { mutableStateOf<cl.iac33.app.seismic.SeismicForecast?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            client.latest()
                .onSuccess {
                    events = it.events
                    mapEvents = it.mapEvents
                    forecast = it.forecast
                }
                .onFailure { error = it.message ?: "No se pudo consultar sismicidad" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LaunchedEffect(settings.seismicAutoRefresh) {
        if (!settings.seismicAutoRefresh) return@LaunchedEffect
        while (isActive) {
            delay(5 * 60 * 1000L)
            refresh()
        }
    }

    val visible = events.filter { it.magnitude >= settings.seismicMinimumMagnitude }
    val visibleMap = mapEvents.filter { it.magnitude >= settings.seismicMinimumMagnitude }
    val markers = visibleMap.mapNotNull {
        val lat = it.latitude ?: return@mapNotNull null
        val lon = it.longitude ?: return@mapNotNull null
        MapMarker(
            latitude = lat,
            longitude = lon,
            title = "M" + String.format(Locale.US, "%.1f", it.magnitude),
            subtitle = it.place + " · " + String.format(Locale.US, "%.0f km", it.depthKm)
        )
    }.take(100)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Sismicidad", style = MaterialTheme.typography.headlineSmall)
                    Text("CSN reciente · mapa USGS · análisis histórico", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { refresh() }, enabled = !loading) { Text("Actualizar") }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Umbral visible: M" + String.format(Locale.US, "%.1f", settings.seismicMinimumMagnitude))
                    Text("Eventos recientes: " + visible.size)
                    if (loading && visible.isEmpty()) CircularProgressIndicator()
                    if (error != null) Text("Aviso: " + error)
                }
            }
        }

        if (markers.isNotEmpty()) {
            item {
                IAC33Map(
                    centerLatitude = markers.map { it.latitude }.average(),
                    centerLongitude = markers.map { it.longitude }.average(),
                    zoom = settings.mapZoom,
                    markers = markers
                )
            }
        }

        val f = forecast
        if (f != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Estimación probabilística", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Historial: " + f.historyYears + " años · muestra M≥" +
                                String.format(Locale.US, "%.1f", f.completenessMagnitude) +
                                ": " + f.sampleCount
                        )
                        f.bValue?.let {
                            Text("b-value: " + String.format(Locale.US, "%.2f", it))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Probabilidad de al menos 1 evento en 7 días:")
                        f.estimates
                            .filter { it.magnitudeThreshold >= settings.seismicMinimumMagnitude }
                            .forEach { estimate ->
                                ForecastRow(estimate)
                            }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Es una estimación estadística basada en tasa histórica; no predice fecha, lugar exacto ni garantiza un terremoto.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            Text("Últimos eventos", style = MaterialTheme.typography.titleMedium)
        }

        items(visible) { event ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "M" + String.format(Locale.US, "%.1f", event.magnitude) +
                            " · " + event.place,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(event.occurredAtLocal + " · " + String.format(Locale.US, "%.1f km", event.depthKm))
                    if (event.latitude != null && event.longitude != null) {
                        Text(
                            String.format(
                                Locale.US,
                                "%.4f, %.4f",
                                event.latitude,
                                event.longitude
                            )
                        )
                    }
                    Text("Fuente: " + event.source)
                }
            }
        }
    }
}

@Composable
private fun ForecastRow(estimate: SeismicEstimate) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("M≥" + String.format(Locale.US, "%.1f", estimate.magnitudeThreshold))
        Text(String.format(Locale.US, "%.2f %%", estimate.probability7d * 100.0))
    }
}

@Composable
private fun ControlPanel(onRestart: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Control", style = MaterialTheme.typography.headlineSmall)
        Text("Estado del control local y puente OTA.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bridge", style = MaterialTheme.typography.titleMedium)
                Text("La cola remota usa autenticación de dispositivo y transiciones de estado.")
                Text("OTA", style = MaterialTheme.typography.titleMedium)
                Text("Las mejoras que cambian la APK requieren una compilación firmada.")
                OutlinedButton(onClick = onRestart) { Text("Recargar interfaz") }
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Configuración", style = MaterialTheme.typography.headlineSmall)
            Text("Preferencias persistentes de IAC33.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            SettingSwitch(
                title = "IA local primero",
                description = "Evita depender del proveedor remoto cuando el modo local sea suficiente.",
                checked = settings.aiLocalFirst,
                onCheckedChange = { onChange(settings.copy(aiLocalFirst = it)) }
            )
        }
        item {
            SettingSwitch(
                title = "Actualizar sismicidad automáticamente",
                description = "Consulta nuevos datos cada cinco minutos mientras el módulo está abierto.",
                checked = settings.seismicAutoRefresh,
                onCheckedChange = { onChange(settings.copy(seismicAutoRefresh = it)) }
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Magnitud mínima visible")
                    Text("M" + String.format(Locale.US, "%.1f", settings.seismicMinimumMagnitude))
                    Slider(
                        value = settings.seismicMinimumMagnitude,
                        onValueChange = { value ->
                            val rounded = (value * 10f).toInt() / 10f
                            onChange(settings.copy(seismicMinimumMagnitude = rounded.coerceIn(2.5f, 5.0f)))
                        },
                        valueRange = 2.5f..5.0f
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Zoom de mapas")
                    Text(settings.mapZoom.toString())
                    Slider(
                        value = settings.mapZoom.toFloat(),
                        onValueChange = { value ->
                            onChange(settings.copy(mapZoom = value.toInt().coerceIn(3, 8)))
                        },
                        valueRange = 3f..8f,
                        steps = 4
                    )
                }
            }
        }
        item {
            SettingSwitch(
                title = "Vibración",
                description = "Reserva la vibración para acciones y alertas futuras.",
                checked = settings.haptics,
                onCheckedChange = { onChange(settings.copy(haptics = it)) }
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("IAC33 " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleMedium)
                    Text(BuildConfig.IAC33_BACKEND_URL)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { onChange(AppSettings()) }) {
                        Text("Restaurar valores")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private fun probeBackend(baseUrl: String): String = probeHttp(baseUrl.trimEnd('/') + "/health")

private fun probeHttp(url: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 6_000
        readTimeout = 6_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "*/*")
        setRequestProperty("User-Agent", "IAC33/2.0 Android")
    }
    return try {
        val status = connection.responseCode
        "HTTP " + status + " · " + connection.contentType.orEmpty()
    } catch (error: Throwable) {
        "ERROR · " + (error.message ?: error.javaClass.simpleName)
    } finally {
        connection.disconnect()
    }
}