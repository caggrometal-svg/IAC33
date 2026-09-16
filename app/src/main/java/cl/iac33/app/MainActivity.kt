package cl.iac33.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cl.iac33.app.core.connectivity.ConnectivityMonitor
import cl.iac33.app.core.connectivity.ConnectivityStatus
import cl.iac33.app.core.location.LocationReader
import cl.iac33.app.core.location.LocationSnapshot

private val sections = listOf("IA", "Sismos", "C33", "Red", "GPS", "Control")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var selected by remember { mutableIntStateOf(0) }
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
                        if (event == Lifecycle.Event.ON_RESUME) location = locationReader.readLastKnown()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                Scaffold(
                    topBar = { TopAppBar(title = { Column { Text("IAC33"); Text("Núcleo operativo · ${connectivityStatus.name}", style = MaterialTheme.typography.labelSmall) } }) },
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
                        if (selected == 4) GpsPanel(location) else DashboardPanel(sections[selected], connectivityStatus)
                    }
                }
            }
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST && hasLocationPermission()) recreate()
    }

    companion object { private const val LOCATION_PERMISSION_REQUEST = 3401 }
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
