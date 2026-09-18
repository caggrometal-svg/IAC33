package cl.iac33.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import kotlin.math.abs

data class MapMarker(
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val subtitle: String = ""
)

@Composable
fun IAC33Map(
    centerLatitude: Double,
    centerLongitude: Double,
    zoom: Int,
    markers: List<MapMarker>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapView = remember(context) { createMapView(context) }
    val safeLat = centerLatitude.coerceIn(-85.0, 85.0)
    val safeLon = centerLongitude.coerceIn(-180.0, 180.0)
    val safeZoom = zoom.coerceIn(2, 18)
    var appliedCenter by remember(mapView) { mutableStateOf<GeoPoint?>(null) }
    var appliedZoom by remember(mapView) { mutableStateOf<Int?>(null) }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(
        modifier = modifier.fillMaxWidth().height(390.dp).clip(RoundedCornerShape(16.dp))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(390.dp),
            factory = { mapView },
            update = { map ->
                val target = GeoPoint(safeLat, safeLon)

                // Only apply external position changes. Do not recenter on every Compose
                // recomposition, otherwise the user cannot pan/zoom freely.
                if (appliedCenter == null ||
                    abs(appliedCenter!!.latitude - target.latitude) > 0.00005 ||
                    abs(appliedCenter!!.longitude - target.longitude) > 0.00005
                ) {
                    map.controller.setCenter(target)
                    appliedCenter = target
                }

                if (appliedZoom != safeZoom) {
                    map.controller.setZoom(safeZoom.toDouble())
                    appliedZoom = safeZoom
                }

                val copyright = map.overlays.filterIsInstance<CopyrightOverlay>().firstOrNull()
                    ?: CopyrightOverlay(context).also { map.overlays.add(it) }

                map.overlays.removeAll { it is Marker }
                markers.take(200).forEach { item ->
                    if (!item.latitude.isFinite() || !item.longitude.isFinite()) return@forEach
                    val marker = Marker(map).apply {
                        position = GeoPoint(
                            item.latitude.coerceIn(-85.0, 85.0),
                            item.longitude.coerceIn(-180.0, 180.0)
                        )
                        title = item.title
                        snippet = item.subtitle
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    map.overlays.add(marker)
                }

                if (!map.overlays.contains(copyright)) map.overlays.add(copyright)
                map.invalidate()
            }
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilledTonalButton(
                onClick = { mapView.controller.zoomIn() },
                modifier = Modifier.height(42.dp)
            ) { Text("+") }

            FilledTonalButton(
                onClick = { mapView.controller.zoomOut() },
                modifier = Modifier.height(42.dp)
            ) { Text("−") }

            FilledTonalButton(
                onClick = {
                    mapView.controller.setCenter(GeoPoint(safeLat, safeLon))
                    mapView.invalidate()
                },
                modifier = Modifier.height(42.dp)
            ) { Text("◎") }
        }

        Text(
            "Desliza · pellizca · + / − · ◎ centra",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.70f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            color = Color.White
        )
    }
}

private fun createMapView(context: Context): MapView {
    val base = File(context.getExternalFilesDir(null), "osmdroid")
    val tileCache = File(base, "tiles")
    base.mkdirs()
    tileCache.mkdirs()

    Configuration.getInstance().apply {
        userAgentValue = "IAC33/2.0 Android"
        osmdroidBasePath = base
        osmdroidTileCache = tileCache
    }

    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        setBuiltInZoomControls(false)
        setTilesScaledToDpi(true)
        minZoomLevel = 2.0
        maxZoomLevel = 18.0
        controller.setZoom(5.0)
        isHorizontalMapRepetitionEnabled = false
        isVerticalMapRepetitionEnabled = false
    }
}
