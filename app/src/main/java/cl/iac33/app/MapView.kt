package cl.iac33.app

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class MapMarker(
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val subtitle: String = ""
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun IAC33Map(
    centerLatitude: Double,
    centerLongitude: Double,
    zoom: Int,
    markers: List<MapMarker>,
    modifier: Modifier = Modifier
) {
    val html = buildMapHtml(centerLatitude, centerLongitude, zoom, markers)
    AndroidView(
        modifier = modifier.fillMaxWidth().height(330.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.rgb(3, 7, 18))
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView,
                        errorCode: Int,
                        description: String,
                        failingUrl: String
                    ) {
                        Log.e("IAC33-MAP", "WebView error code=" + errorCode + " description=" + description + " url=" + failingUrl)
                    }
                }
                tag = html
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                loadDataWithBaseURL("https://www.openstreetmap.org/", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL("https://www.openstreetmap.org/", html, "text/html", "UTF-8", null)
            }
        }
    )
}

private fun buildMapHtml(
    centerLatitude: Double,
    centerLongitude: Double,
    zoom: Int,
    markers: List<MapMarker>
): String {
    val markerJson = JSONArray().apply {
        markers.take(100).forEach { marker ->
            put(
                JSONObject()
                    .put("lat", marker.latitude)
                    .put("lon", marker.longitude)
                    .put("title", marker.title)
                    .put("subtitle", marker.subtitle)
            )
        }
    }
    val safeLat = centerLatitude.coerceIn(-85.0, 85.0)
    val safeLon = centerLongitude.coerceIn(-180.0, 180.0)
    val safeZoom = zoom.coerceIn(2, 12)
    return """
<!doctype html>
<html><head>
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=yes">
<style>
html,body,#map{margin:0;width:100%;height:100%;overflow:hidden;background:#030712;font-family:system-ui,sans-serif}
#map{position:relative}
.tile{position:absolute;width:256px;height:256px}
.marker{position:absolute;width:18px;height:18px;border-radius:50%;background:#38bdf8;border:2px solid white;box-shadow:0 0 0 2px #0c4a6e;transform:translate(-50%,-50%);cursor:pointer}
.panel{position:absolute;left:10px;right:10px;top:10px;display:flex;justify-content:space-between;gap:8px;z-index:5;pointer-events:none}
.badge{pointer-events:none;background:rgba(3,7,18,.88);color:#e2e8f0;border:1px solid #334155;border-radius:12px;padding:7px 10px;font-size:12px}
.controls{position:absolute;right:10px;bottom:10px;z-index:6;display:flex;gap:5px}
button{border:1px solid #334155;background:rgba(15,23,42,.95);color:#f8fafc;border-radius:10px;width:38px;height:38px;font-size:18px}
.popup{position:absolute;z-index:7;min-width:180px;max-width:270px;background:#0f172a;color:#e2e8f0;border:1px solid #334155;border-radius:12px;padding:10px;box-shadow:0 10px 28px rgba(0,0,0,.35);font-size:12px}
.popup b{display:block;color:#f8fafc;margin-bottom:4px}
</style>
</head><body>
<div id="map"></div>
<script>
const map=document.getElementById('map');
const initial={lat:${fmt(safeLat)},lon:${fmt(safeLon)},zoom:${safeZoom}};
const markers=${markerJson};
let centerLat=initial.lat, centerLon=initial.lon, zoom=initial.zoom;
function worldXY(lat,lon,z){
  const sin=Math.sin(lat*Math.PI/180);
  const n=Math.pow(2,z);
  const x=(lon+180)/360*n*256;
  const y=(0.5-Math.log((1+sin)/(1-sin))/(4*Math.PI))*n*256;
  return {x,y,n};
}
function render(){
  map.innerHTML='<div class="panel"><div class="badge">IAC33 · mapa</div><div class="badge">'+markers.length+' puntos · © OpenStreetMap</div></div><div class="controls"><button onclick="setZoom(1)">+</button><button onclick="setZoom(-1)">−</button><button onclick="center()">⌖</button></div>';
  const w=map.clientWidth,h=map.clientHeight,c=worldXY(centerLat,centerLon,zoom);
  const startX=Math.floor((c.x-w/2)/256)-1,startY=Math.floor((c.y-h/2)/256)-1;
  const endX=startX+Math.ceil(w/256)+2,endY=startY+Math.ceil(h/256)+2;
  for(let tx=startX;tx<=endX;tx++)for(let ty=startY;ty<=endY;ty++){
    const x=((tx%c.n)+c.n)%c.n;
    const img=document.createElement('img'); img.className='tile';
    img.src='https://tile.openstreetmap.org/'+zoom+'/'+x+'/'+ty+'.png';
    img.style.left=(tx*256-c.x+w/2)+'px'; img.style.top=(ty*256-c.y+h/2)+'px';
    img.onerror=()=>{img.style.display='none'};
    map.appendChild(img);
  }
  for(const m of markers){
    if(!Number.isFinite(m.lat)||!Number.isFinite(m.lon)) continue;
    const p=worldXY(m.lat,m.lon,zoom);
    const px=p.x-c.x+w/2,py=p.y-c.y+h/2;
    if(px<-25||px>w+25||py<-25||py>h+25) continue;
    const el=document.createElement('div');el.className='marker';el.style.left=px+'px';el.style.top=py+'px';
    el.onclick=()=>showPopup(m,px,py);map.appendChild(el);
  }
}
function showPopup(m,x,y){
 const old=document.querySelector('.popup');if(old)old.remove();
 const p=document.createElement('div');p.className='popup';p.style.left=Math.min(Math.max(x,8),Math.max(8,map.clientWidth-195))+'px';p.style.top=Math.min(Math.max(y,35),Math.max(35,map.clientHeight-90))+'px';
 p.innerHTML='<b>'+escapeHtml(m.title)+'</b>'+escapeHtml(m.subtitle||'');
 p.onclick=()=>p.remove();map.appendChild(p);
}
function escapeHtml(v){return String(v).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
function setZoom(delta){zoom=Math.max(2,Math.min(12,zoom+delta));render()}
function center(){centerLat=initial.lat;centerLon=initial.lon;zoom=initial.zoom;render()}
window.addEventListener('resize',render);render();
</script>
</body></html>
""".trimIndent()
}

private fun fmt(value: Double): String = String.format(Locale.US, "%.6f", value)
