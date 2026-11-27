package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.api.Snapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CommandsScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (Screen) -> Unit = {}) {
    val s by vm.snapshot.collectAsState()
    val pending by vm.pendingAction.collectAsState()
    val loggedIn by vm.loggedIn.collectAsState()
    val ready = loggedIn && vm.prefs.vin.isNotBlank()
    CommandsScreenContent(
        s = s, pending = pending, ready = ready, lastError = vm.prefs.lastError,
        onCommand = { action, backend -> vm.command(action, backend = backend) },
        onSetSentry = { vm.setSentry(it) },
        onBack = onBack, onOpen = onOpen,
    ) {
        V2HubScreen(vm, onBack = onBack, onOpen = onOpen, onlyModule = "commands", embedded = true)
    }
}

@Composable
internal fun CommandsScreenContent(
    s: Snapshot?,
    pending: String?,
    ready: Boolean,
    lastError: String,
    onCommand: (String, String) -> Unit,
    onSetSentry: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpen: (Screen) -> Unit,
    additionalCommands: @Composable () -> Unit,
) {
    val busyOther = pending != null
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        ScreenHeader("Comandi", "Ogni comando può svegliare l'auto se dorme.", onBack = onBack)

        Text("Chiusura e apertura", style = MaterialTheme.typography.titleSmall)
        ToggleRow(
            title = "Chiusura",
            subtitle = when (s?.locked) { true -> "Chiusa"; false -> "Aperta"; null -> "Stato sconosciuto" },
            checked = s?.locked == true,
            enabled = ready && (!busyOther || pending == "lock"),
            busy = pending == "lock",
            onCheckedChange = { wantLocked ->
                if (wantLocked && s?.locked != true) onCommand("lock", "lock")
                else if (!wantLocked && s?.locked != false) onCommand("lock", "unlock")
            },
            leading = {
                Icon(
                    if (s?.locked == false) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    contentDescription = null,
                    tint = VigiloIconGray,
                    modifier = Modifier.size(24.dp),
                )
            },
        )
        Text("Sicurezza", style = MaterialTheme.typography.titleSmall)
        ToggleRow(
            title = "Sentinella",
            subtitle = when (s?.sentry) { true -> "Attiva"; false -> "Spenta"; null -> "Stato sconosciuto" },
            checked = s?.sentry == true,
            enabled = ready && (!busyOther || pending == "sentry"),
            busy = pending == "sentry",
            onCheckedChange = onSetSentry,
            leading = { SentryIcon(active = s?.sentry == true, size = 22.dp) },
        )
        Text("Luci e clacson", style = MaterialTheme.typography.titleSmall)
        ActionRow(
            icon = { Icon(Icons.Filled.FlashOn, contentDescription = null, tint = VigiloIconGray, modifier = Modifier.size(24.dp)) },
            title = "Lampeggia",
            enabled = ready && (!busyOther || pending == "flash"),
            busy = pending == "flash",
            onClick = { onCommand("flash", "flash") },
        )
        ActionRow(
            icon = { Icon(Icons.Filled.Campaign, contentDescription = null, tint = VigiloIconGray, modifier = Modifier.size(24.dp)) },
            title = "Clacson",
            enabled = ready && (!busyOther || pending == "honk"),
            busy = pending == "honk",
            onClick = { onCommand("honk", "honk") },
        )

        Spacer(Modifier.height(12.dp))
        additionalCommands()
        Spacer(Modifier.height(20.dp))
        VigiloCard {
            Row2("Porte", yesNo(s?.doorsOpen, "Aperte", "Chiuse"))
            Row2("Finestrini", yesNo(s?.windowsOpen, "Aperti", "Chiusi"))
            Row2("Bagagliaio / frunk", "${yesNo(s?.trunkOpen, "aperto", "chiuso")} / ${yesNo(s?.frunkOpen, "aperto", "chiuso")}")
            Row2("Temperatura int./est.", "${s?.insideTemp?.let { "%.1f°".format(it) } ?: "—"} / ${s?.outsideTemp?.let { "%.1f°".format(it) } ?: "—"}")
            Row2("Connessione", if (s?.online == true) "Online" else when (s?.connectionState) {
                "asleep" -> "Addormentata"; "offline" -> "Offline"; else -> "—"
            })
            Row2("Contachilometri", s?.odometerKm?.let { "%,.0f km".format(Locale.ITALY, it) } ?: "—")
            Spacer(Modifier.height(6.dp))
            Text(
                "Ultimo aggiornamento: " + (s?.timestamp?.let { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(it)) } ?: "mai"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s?.locationScopeMissing == true) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Posizione non disponibile: nel portale Tesla abilita lo scope Vehicle Location, poi rifai il login Tesla nella pagina di configurazione di Vigilo.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (lastError.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Ultimo errore: ${lastError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        MenuRow(
            Icons.Filled.LocationOn,
            "Posizione",
            onClick = { onOpen(Screen.MAP) },
        )
        MenuRow(
            Icons.Filled.BatteryChargingFull,
            "Ricarica",
            onClick = { onOpen(Screen.CHARGE) },
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    busy: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = VigiloIconGray)
        } else {
            VigiloSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun ActionRow(
    icon: @Composable () -> Unit,
    title: String,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = VigiloIconGray)
    }
}

@Composable
private fun Row2(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(v, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun yesNo(b: Boolean?, yes: String, no: String) = when (b) { true -> yes; false -> no; null -> "—" }
