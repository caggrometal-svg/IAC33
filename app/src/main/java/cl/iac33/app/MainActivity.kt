package cl.iac33.app

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
import androidx.compose.ui.unit.dp
import cl.iac33.app.core.connectivity.ConnectivityMonitor
import cl.iac33.app.core.connectivity.ConnectivityStatus

private val sections = listOf("IA", "Sismos", "C33", "Red", "GPS", "Control")

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var selected by remember { mutableIntStateOf(0) }
                var connectivityStatus by remember { mutableStateOf(ConnectivityStatus.OFFLINE) }
                val connectivityMonitor = remember { ConnectivityMonitor(this@MainActivity) }

                DisposableEffect(Unit) {
                    connectivityMonitor.start { status ->
                        runOnUiThread { connectivityStatus = status }
                    }
                    onDispose { connectivityMonitor.stop() }
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
                                    onClick = { selected = index },
                                    icon = { Text(label.take(1)) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Text(
                        text = "Módulo ${sections[selected]} · núcleo operativo listo",
                        modifier = Modifier.padding(padding).padding(16.dp)
                    )
                }
            }
        }
    }
}
