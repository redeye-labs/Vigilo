package io.github.redeyelabs.vigilo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChargeScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s by vm.snapshot.collectAsState()
    val pct = (s?.batteryLevel ?: 0).coerceIn(0, 100)
    val bar by animateFloatAsState(if (s?.batteryLevel != null) pct / 100f else 0f, animationSpec = tween(700), label = "batt")
    val charging = s?.chargingState == "Charging"
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        ScreenHeader("Ricarica", "Stato della batteria e della ricarica.", onBack = onBack)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (s?.batteryLevel != null) "$pct%" else "—",
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                if (s?.rangeKm != null) "${s!!.rangeKm!!.toInt()} km di autonomia" else "Autonomia non disponibile",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { bar },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (charging) VigiloOk else MaterialTheme.colorScheme.onSurface,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        VigiloCard {
            Text(
                "Ricarica: ${chargingLabel(s?.chargingState)}" +
                    (s?.chargeLimit?.let { " · limite $it%" } ?: "") +
                    (s?.minutesToFull?.takeIf { it > 0 && charging }?.let { " · piena tra ${it / 60}h ${it % 60}m" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
