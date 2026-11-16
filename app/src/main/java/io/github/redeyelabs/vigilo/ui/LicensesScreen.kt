package io.github.redeyelabs.vigilo.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Voce dell'elenco licenze mostrato in Impostazioni. */
data class LicenseItem(
    val name: String,
    val license: String,
    val url: String,
    val note: String? = null,
)

/** Componenti dell'app e dei dati usati a runtime, con licenza letta dai sorgenti ufficiali. */
val AppLicenses: List<LicenseItem> = listOf(
    LicenseItem(
        "Vigilo",
        "MIT",
        "https://github.com/redeye-labs/Vigilo/blob/main/LICENSE",
    ),
    LicenseItem(
        "AndroidX (Compose, Material 3, Room, WorkManager e altre librerie Jetpack)",
        "Apache-2.0",
        "https://developer.android.com/jetpack",
    ),
    LicenseItem(
        "Kotlin, kotlinx-coroutines, kotlinx-serialization",
        "Apache-2.0",
        "https://github.com/JetBrains/kotlin",
    ),
    LicenseItem(
        "osmdroid",
        "Apache-2.0",
        "https://github.com/osmdroid/osmdroid",
    ),
    LicenseItem(
        "OpenStreetMap (dati e riquadri standard)",
        "ODbL 1.0",
        "https://www.openstreetmap.org/copyright",
        "© OpenStreetMap contributors",
    ),
    LicenseItem(
        "OkHttp",
        "Apache-2.0",
        "https://github.com/square/okhttp",
    ),
    LicenseItem(
        "Bouncy Castle",
        "MIT",
        "https://www.bouncycastle.org/licence.html",
    ),
    LicenseItem(
        "Google Play Services Code Scanner",
        "Termini Google Play Services / Android SDK",
        "https://developers.google.com/android/guides/licenses",
        "Non è una licenza open source OSI. Usato solo per inquadrare il QR di abbinamento.",
    ),
    LicenseItem(
        "Foto auto (Ethan Llamas, Wikimedia Commons)",
        "CC BY-SA 4.0",
        "https://commons.wikimedia.org/wiki/File:Tesla_Model_3_Highland_Performance_AWD_Pearl_White_Multi-Coat.jpg",
        "Model 3 Highland Pearl White Multi-Coat, usata solo se attivi la foto dell'auto.",
    ),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    fun open(url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            "Licenze open source",
            "Componenti e dati usati da questa app. L'elenco completo, compreso il componente aggiuntivo, è nel file THIRD_PARTY_LICENSES.md del repository.",
            onBack = onBack,
        )
        AppLicenses.forEach { item ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { open(item.url) },
            ) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    item.license,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!item.note.isNullOrBlank()) {
                    Text(
                        item.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    item.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
