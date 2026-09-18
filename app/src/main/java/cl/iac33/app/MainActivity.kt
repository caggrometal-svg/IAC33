package cl.iac33.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatLine(val role: String, val text: String)

private val sections = listOf("IA", "Sismos", "C33", "Red", "GPS", "Control")
private val sectionGlyphs = listOf("AI", "EQ", "C33", "NET", "GPS", "CTL")

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) recreate()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val iac33DarkColors = darkColorScheme(
                primary = Color(0xFF7DD3FC),
                onPrimary = Color(0xFF00202A),
                primaryContainer = Color(0xFF123B46),
                onPrimaryContainer = Color(0xFFB8ECFF),
                secondary = Color(0xFFB9C7FF),
                onSecondary = Color(0xFF18254A),
                secondaryContainer = Color(0xFF29345D),
                onSecondaryContainer = Color(0xFFDCE1FF),
                tertiary = Color(0xFF9FE6D2),
                onTertiary = Color(0xFF00382F),
                tertiaryContainer = Color(0xFF155348),
                onTertiaryContainer = Color(0xFFBAF2E3),
                background = Color(0xFF080B10),
                onBackground = Color(0xFFE7EAF0),
                surface = Color(0xFF0D1118),
                onSurface = Color(0xFFE7EAF0),
                surfaceVariant = Color(0xFF171D27),
                onSurfaceVariant = Color(0xFFB8C0CC),
                outline = Color(0xFF3A4352)
            )
            MaterialTheme(colorScheme = iac33DarkColors) {
                var selected by rememberSaveable { mutableIntStateOf(0) }
                var connectivityStatus by remember { mutableStateOf(ConnectivityStatus.OFFLINE) }
                var location by remember { mutableStateOf<LocationSnapshot?>(null) }
                val connectivityMonitor = remember { ConnectivityMonitor(this@MainActivity) }
                val locationReader = remember { LocationReader(this@MainActivity) }
                val lifecycleOwner = LocalLifecycleOwner.current
                val locationScope = rememberCoroutineScope()

                DisposableEffect(Unit) {
                    connectivityMonitor.start { status -> connectivityStatus = status }
                    onDispose { connectivityMonitor.stop() }
                }
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) locationScope.launch { location = locationReader.readCurrentOrLastKnown() }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(title = {
                            Column {
                                Text("IAC33")
                                Text("Núcleo operativo · ${connectivityStatus.name}", style = MaterialTheme.typography.labelSmall)
                            }
                        })
                    },
                    bottomBar = {
                        NavigationBar {
                            sections.forEachIndexed { index, label ->
                                NavigationBarItem(
                                    selected = selected == index,
                                    onClick = {
                                        selected = index
                                        if (index == 4 && !hasLocationPermission()) requestLocationPermission()
                                    },
                                    icon = { Text(sectionGlyphs[index], style = MaterialTheme.typography.labelSmall) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).imePadding()) {
                        if (selected == 0) AiPanel()
                        else if (selected == 1) SeismicPanel()
                        else if (selected == 4) GpsPanel(location)
                        else DashboardPanel(sections[selected], connectivityStatus)
                    }
                }
            }
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }
}

@Composable
private fun AiPanel() {
    val engine = remember { AiEngineImpl() }
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(listOf(ChatLine("assistant", "IAC33 listo. Puedes escribir una consulta."))) }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Centro IA", style = MaterialTheme.typography.headlineSmall)
                Text("Conversación con el núcleo IAC33", style = MaterialTheme.typography.bodySmall)
            }
            AssistChip(onClick = {}, enabled = false, label = { Text(if (busy) "Procesando" else "Listo") })
        }
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(20.dp)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(lines) { line ->
                    Text(
                        text = if (line.role == "user") "Tú: ${line.text}" else "IAC33: ${line.text}",
                        style = if (line.role == "user") MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 32_000) draft = it },
                modifier = Modifier.weight(1f),
                enabled = !busy,
                placeholder = { Text("Escribe una consulta a IAC33…") },
                shape = RoundedCornerShape(16.dp),
                singleLine = false,
                maxLines = 4
            )
            Button(
                onClick = {
                    val prompt = draft.trim()
                    if (prompt.isEmpty() || busy) return@Button
                    val updated = lines + ChatLine("user", prompt)
                    lines = updated
                    draft = ""
                    busy = true
                    scope.launch {
                        try {
                            val messages = updated.map { AiMessage(it.role, it.text) }
                            val result = runCatching {
                                engine.generate(AiRequest(UUID.randomUUID().toString(), messages))
                            }.getOrElse { throwable ->
                                OperationResult.Failure(OperationError.INTERNAL, throwable.message ?: "Error interno de IA")
                            }
                            when (result) {
                                is OperationResult.Success -> lines = lines + ChatLine("assistant", result.value.text.orEmpty().ifBlank { "Respuesta vacía." })
                                is OperationResult.Failure -> lines = lines + ChatLine("assistant", "Error: ${result.message}")
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && draft.isNotBlank(),
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(if (busy) "…" else "Enviar")
            }
        }
    }
}

@Composable
private fun DashboardPanel(section: String, connectivityStatus: ConnectivityStatus) {
    Text(section, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("IAC33 · $section", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Módulo disponible", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text("Conectividad: ${connectivityStatus.name}")
            Spacer(Modifier.height(12.dp))
            AssistChip(onClick = {}, enabled = false, label = { Text("Núcleo IAC33 activo") })
        }
    }
}

@Composable
private fun GpsPanel(location: LocationSnapshot?) {
    Text("GPS", style = MaterialTheme.typography.headlineSmall)
    Text("Ubicación del dispositivo", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (location != null) {
                Text("Ubicación disponible", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Latitud"); Text("%.6f".format(location.latitude)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Longitud"); Text("%.6f".format(location.longitude)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Precisión"); Text(location.accuracyMeters?.let { "%.1f m".format(it) } ?: "n/d") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Fuente"); Text(location.provider) }
            } else {
                Text("GPS sin posición disponible", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Concede el permiso de ubicación y vuelve a abrir esta sección.")
            }
        }
    }
}

@Composable
private fun SeismicPanel() {
    val scope = rememberCoroutineScope()
    val client = remember { SeismicClient(BuildConfig.IAC33_BACKEND_URL) }
    var events by remember { mutableStateOf<List<SeismicEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            client.latest().onSuccess { snapshot ->
                events = snapshot.events
            }.onFailure { failure ->
                error = failure.message ?: "No se pudo consultar sismicidad"
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Sismicidad", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { refresh() }, enabled = !loading) { Text("Actualizar") }
        }
        Spacer(Modifier.height(8.dp))
        if (loading && events.isEmpty()) {
            CircularProgressIndicator()
        } else if (error != null && events.isEmpty()) {
            Text("Error: $error")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events) { event ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("M%.1f · %s".format(event.magnitude, event.place), style = MaterialTheme.typography.titleMedium)
                            Text(event.occurredAtLocal + " · " + event.depthKm + " km")
                            Text("Fuente: " + event.source)
                        }
                    }
                }
            }
        }
    }
}