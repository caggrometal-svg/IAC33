package cl.iac33.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cl.iac33.app.core.connectivity.ConnectivityMonitor
import cl.iac33.app.core.connectivity.ConnectivityStatus
import cl.iac33.app.core.location.LocationReader
import cl.iac33.app.core.location.LocationSnapshot

private val sections = listOf("IA", "Sismos", "C33", "Red", "GPS", "Control")

@OptIn(ExperimentalMaterial3Api::class)
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
                                        text = connectivityStatus.name,
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
                                        if (index == 4 &&
                                            ContextCompat.checkSelfPermission(
                                                this@MainActivity,
                                                Manifest.permission.ACCESS_FINE_LOCATION
                                            ) != PackageManager.PERMISSION_GRANTED &&
                                            ContextCompat.checkSelfPermission(
                                                this@MainActivity,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
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
                    if (selected == 4) {
                        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
                            Text("GPS", style = MaterialTheme.typography.headlineSmall)
                            when {
                                location != null -> {
                                    Text("Latitud: ${location!!.latitude}")
                                    Text("Longitud: ${location!!.longitude}")
                                    Text("Precisión: ${location!!.accuracyMeters?.let { "%.1f m".format(it) } ?: "n/d"}")
                                    Text("Fuente: ${location!!.provider}")
                                }
                                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> {
                                    Text("Permiso concedido. Esperando ubicación disponible.")
                                }
                                else -> Text("Permiso de ubicación requerido.")
                            }
                        }
                    } else {
                        Text(
                            text = "Módulo ${sections[selected]} · núcleo operativo listo",
                            modifier = Modifier.padding(padding).padding(16.dp)
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 3401
    }
}
