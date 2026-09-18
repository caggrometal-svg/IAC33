package cl.iac33.app

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File

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
    val context = androidx.compose.ui.platform.LocalContext.current
    val mapView = remember(context) { createMapView(context) }
    val safeLat = centerLatitude.coerceIn(-85.0, 85.0)
    val safeLon = centerLongitude.coerceIn(-180.0, 180.0)
    val safeZoom = zoom.coerceIn(2, 18)

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(330.dp),
        factory = { mapView },
        update = { map ->
            map.controller.setZoom(safeZoom.toDouble())
            map.controller.setCenter(GeoPoint(safeLat, safeLon))

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

            if (copyright.parent == null) map.overlays.add(copyright)
            map.invalidate()
        }
    )
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
        minZoomLevel = 2.0
        maxZoomLevel = 18.0
        controller.setZoom(5.0)
    }
}
