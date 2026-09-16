package cl.iac33.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                    connectivityMonitor.start { status ->
                        runOnUiThread { connectivityStatus = status }
                    }
                    onDispose { connectivityMonitor.stop() }
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            location = locationReader.readLastKnown()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("IAC33")
                                    Text(
                                        text = "Núcleo operativo · ${connectivityStatus.name}",
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
                                    selected = selected == index,
                                    onClick = {
                                        selected = index
                                        if (index == 4 && !hasLocationPermission()) {
                                            requestPermissions(
                                                arrayOf(
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                                ),
                                                LOCATION_PERMISSION_REQUEST
                                            )
                                        }
                                    },
                                    icon = { Text(label.take(1)) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Top
                    ) {
                        if (selected == 4) {
                            GpsPanel(location)
                        } else {
                            DashboardPanel(sections[selected], connectivityStatus)
                        }
                    }
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST && hasLocationPermission()) recreate()
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 3401
    }
}

@androidx.compose.runtime.Composable
private fun DashboardPanel(section: String, connectivityStatus: ConnectivityStatus) {
    Text("$section", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("IAC33 · $section", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Módulo disponible")
            Text("Conectividad: ${connectivityStatus.name}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@androidx.compose.runtime.Composable
private fun GpsPanel(location: LocationSnapshot?) {
    Text("GPS", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (location != null) {
                Text("Ubicación disponible", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Latitud")
                    Text("%.6f".format(location.latitude))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Longitud")
                    Text("%.6f".format(location.longitude))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Precisión")
                    Text(location.accuracyMeters?.let { "%.1f m".format(it) } ?: "n/d")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Fuente")
                    Text(location.provider)
                }
            } else {
                Text("GPS sin posición disponible", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Concede el permiso de ubicación y vuelve a abrir esta sección.")
            }
        }
    }
}
