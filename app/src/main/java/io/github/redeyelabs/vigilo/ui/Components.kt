package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = VigiloIconGray)
            }
        }
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun VigiloCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, elevation = elevation, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = colors, elevation = elevation, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

/**
 * Icona Sentinella originale: cerchio pieno rosso con anello morbido se attiva,
 * solo contorno chiaro se spenta. Non è un asset Tesla.
 */
@Composable
fun SentryIcon(active: Boolean, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Canvas(modifier.size(size)) {
        val r = size.toPx() / 2f
        val c = center
        if (active) {
            drawCircle(color = VigiloDanger.copy(alpha = 0.20f), radius = r * 0.98f, center = c)
            drawCircle(color = VigiloDanger, radius = r * 0.62f, center = c)
        } else {
            drawCircle(
                color = VigiloIconGray,
                radius = r * 0.62f,
                center = c,
                style = Stroke(width = (size.toPx() / 14f).coerceAtLeast(2f)),
            )
        }
    }
}

@Composable
fun QuickAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
    iconContent: (@Composable () -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = VigiloIconGray,
                )
                iconContent != null -> iconContent()
                icon != null -> Icon(icon, contentDescription = label, tint = VigiloIconGray, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatusChip(text: String, emphasized: Boolean = false, modifier: Modifier = Modifier) {
    val bg = if (emphasized) MaterialTheme.colorScheme.error.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (emphasized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = CircleShape, color = bg, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
fun MenuRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
        } else {
            Icon(icon, contentDescription = null, tint = VigiloIconGray, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Interruttore monocromatico ben visibile anche da spento: contorno e pallino grigio chiaro su fondo scuro;
 * acceso = binario bianco. Con [danger] il pallino acceso è rosso (Sentinella attiva). Niente blu.
 */
@Composable
fun VigiloSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = SwitchOnThumb,
            checkedTrackColor = Color.White,
            checkedBorderColor = Color.White,
            checkedIconColor = Color.White,
            uncheckedThumbColor = VigiloIconGray,
            uncheckedTrackColor = SwitchOffTrack,
            uncheckedBorderColor = VigiloIconGray,
            uncheckedIconColor = SwitchOffTrack,
            disabledCheckedThumbColor = SwitchOnThumb.copy(alpha = 0.6f),
            disabledCheckedTrackColor = Color.White.copy(alpha = 0.35f),
            disabledCheckedBorderColor = Color.White.copy(alpha = 0.35f),
            disabledUncheckedThumbColor = VigiloIconGray.copy(alpha = 0.4f),
            disabledUncheckedTrackColor = SwitchOffTrack,
            disabledUncheckedBorderColor = VigiloIconGray.copy(alpha = 0.3f),
        ),
    )
}

/** Cursore con binario ben visibile (grigio) anche nella parte non selezionata. */
@Composable
fun VigiloSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = SliderInactive,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            disabledThumbColor = VigiloIconGray.copy(alpha = 0.5f),
            disabledActiveTrackColor = VigiloIconGray.copy(alpha = 0.4f),
            disabledInactiveTrackColor = SliderInactive.copy(alpha = 0.5f),
        ),
    )
}

private val SwitchOnThumb = Color(0xFF171717)
private val SwitchOffTrack = Color(0xFF1C1C1C)
private val SliderInactive = Color(0xFF636366)
