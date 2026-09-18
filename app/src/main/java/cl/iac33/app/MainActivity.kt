package cl.iac33.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import cl.iac33.app.core.connectivity.ConnectivityMonitor
import cl.iac33.app.core.connectivity.ConnectivityStatus
import cl.iac33.app.core.location.LocationReader
import cl.iac33.app.core.location.LocationSnapshot
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatLine(val role: String, val text: String)

private val sections = listOf("IA", "Sismos", "C33", "Red", "GPS", "Control")

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
            MaterialTheme {
                var selected by rememberSaveable { mutableIntStateOf(0) }
                var connectivityStatus by remember { mutableStateOf(ConnectivityStatus.OFFLINE) }
                var location by remember { mutableStateOf<LocationSnapshot?>(null) }
                val connectivityMonitor = remember { ConnectivityMonitor(this@MainActivity) }
                val locationReader = remember { LocationReader(this@MainActivity) }
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(Unit) {
                    connectivityMonitor.start { status -> connectivityStatus = status }
                    onDispose { connectivityMonitor.stop() }
                }
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) location = locationReader.readCurrentOrLastKnown()
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
                                    icon = { Text(label.take(1)) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                        if (selected == 0) AiPanel()
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

    Column(Modifier.fillMaxSize()) {
        Text("IA", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(12.dp),
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
                placeholder = { Text("Escribe una consulta") },
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
                        val messages = updated.map { AiMessage(it.role, it.text) }
                        when (val result = engine.generate(AiRequest(UUID.randomUUID().toString(), messages))) {
                            is OperationResult.Success -> lines = lines + ChatLine("assistant", result.value.text.orEmpty().ifBlank { "Respuesta vacía." })
                            is OperationResult.Failure -> lines = lines + ChatLine("assistant", "Error: ${result.message}")
                        }
                        busy = false
                    }
                },
                enabled = !busy && draft.isNotBlank(),
                modifier = Modifier.height(56.dp)
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("IAC33 · $section", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Módulo disponible")
            Text("Conectividad: ${connectivityStatus.name}")
        }
    }
}

@Composable
private fun GpsPanel(location: LocationSnapshot?) {
    Text("GPS", style = MaterialTheme.typography.headlineMedium)
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
