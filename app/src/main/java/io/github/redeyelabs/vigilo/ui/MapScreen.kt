package io.github.redeyelabs.vigilo.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.location.Geocoder
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.util.Locale

@Composable
fun MapScreen(vm: MainViewModel, onBack: (() -> Unit)? = null) {
    val s by vm.snapshot.collectAsState()
    val ctx = LocalContext.current
    val lat = s?.latitude
    val lon = s?.longitude
    if (lat == null || lon == null) {
        ColumnPad {
            ScreenHeader("Posizione", "Posizione dell'auto in tempo reale.", onBack = onBack)
            VigiloCard {
                val msg = if (s?.locationScopeMissing == true)
                    "La mappa ha bisogno dello scope \"Vehicle Location\": abilitalo nel portale sviluppatori Tesla (app Personal Sentry Notifier), poi in Vigilo vai su Impostazioni → Esci e rifai \"Accedi con Tesla\" accettando la posizione."
                else "Posizione non disponibile. Aggiorna lo stato con l'auto online."
                Text(msg, style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    var address by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lat, lon) {
        address = withContext(Dispatchers.IO) { reverseGeocode(ctx, lat, lon) }
    }

    val markerIcon = remember { BitmapDrawable(ctx.resources, carDotBitmap()) }
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBackgroundColor(0xFF171717.toInt())
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            minZoomLevel = 4.0
            maxZoomLevel = 20.0
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.5)
        }
    }
    DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }

    Column(Modifier.fillMaxSize().background(ComposeColor(0xFF171717))) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { map }, modifier = Modifier.fillMaxSize(), update = { m ->
                val p = GeoPoint(lat, lon)
                m.overlays.removeAll { it is Marker }
                m.overlays.add(Marker(m).apply {
                    position = p
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = markerIcon
                    title = s?.name ?: "La mia auto"
                    snippet = listOfNotNull(
                        s?.batteryLevel?.let { "Batteria $it%" },
                        when (s?.locked) { true -> "chiusa"; false -> "aperta"; null -> null },
                    ).joinToString(" · ")
                })
                m.controller.animateTo(p, 16.5, 600L)
                m.invalidate()
            })
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = ComposeColor.White)
                }
            }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { map.controller.animateTo(GeoPoint(lat, lon), 16.5, 600L) }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Centra auto")
                }
                FilledTonalButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(Auto)")))
                }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Apri in Google Maps")
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(ComposeColor(0xFF171717))
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(address ?: "Indirizzo in caricamento…", style = MaterialTheme.typography.titleMedium, color = ComposeColor.White)
            Spacer(Modifier.height(4.dp))
            Text(String.format(Locale.US, "%.5f, %.5f", lat, lon), style = MaterialTheme.typography.bodySmall, color = VigiloIconGray)
            val lock = when (s?.locked) { true -> "Chiusa"; false -> "Aperta"; null -> null }
            val batt = s?.batteryLevel?.let { "Batteria $it%" }
            val line = listOfNotNull(lock, batt).joinToString("  ·  ")
            if (line.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(line, style = MaterialTheme.typography.bodySmall, color = VigiloIconGray)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "© OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun reverseGeocode(ctx: android.content.Context, lat: Double, lon: Double): String? = runCatching {
    @Suppress("DEPRECATION")
    Geocoder(ctx, Locale.ITALY).getFromLocation(lat, lon, 1)?.firstOrNull()?.getAddressLine(0)
}.getOrNull()

private fun carDotBitmap(): Bitmap {
    val size = 72
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(cx, cy + 2f, 22f, intArrayOf(0x88000000.toInt(), 0x00000000), floatArrayOf(0.4f, 1f), Shader.TileMode.CLAMP)
    }
    canvas.drawCircle(cx, cy + 2f, 22f, shadow)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    canvas.drawCircle(cx, cy, 13f, fill)
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF171717.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    canvas.drawCircle(cx, cy, 13f, ring)
    return bmp
}

@Composable
internal fun ColumnPad(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}
