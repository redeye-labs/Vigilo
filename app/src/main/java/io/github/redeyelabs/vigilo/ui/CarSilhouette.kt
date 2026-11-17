package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import io.github.redeyelabs.vigilo.R
import io.github.redeyelabs.vigilo.data.Prefs
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

/**
 * Silhouette laterale originale di un'auto compatta elettrica, disegnata per Vigilo.
 * Non è un modello esistente e non riproduce loghi o asset di terze parti.
 */
val VigiloCarSide: ImageVector by lazy {
    ImageVector.Builder(
        name = "VigiloCarSide",
        defaultWidth = 280.dp,
        defaultHeight = 100.dp,
        viewportWidth = 280f,
        viewportHeight = 100f,
    ).apply {
        // Carrozzeria + passaruota (even-odd)
        path(
            fill = SolidColor(Color.White),
            fillAlpha = 0.94f,
            pathFillType = PathFillType.EvenOdd,
        ) {
            moveTo(26f, 71f)
            curveTo(16f, 71f, 13f, 64f, 16f, 58f)
            curveTo(19f, 50f, 30f, 46f, 42f, 44f)
            curveTo(54f, 31f, 74f, 21f, 98f, 19f)
            curveTo(128f, 16f, 156f, 16f, 182f, 20f)
            curveTo(204f, 24f, 218f, 36f, 232f, 46f)
            curveTo(248f, 48f, 260f, 51f, 267f, 57f)
            curveTo(274f, 63f, 274f, 69f, 268f, 71f)
            lineTo(230f, 71f)
            arcTo(21f, 21f, 0f, false, false, 188f, 71f)
            lineTo(90f, 71f)
            arcTo(21f, 21f, 0f, false, false, 48f, 71f)
            close()
            // vetri
            moveTo(86f, 42f)
            curveTo(96f, 28f, 122f, 23f, 150f, 23f)
            curveTo(172f, 23f, 192f, 28f, 206f, 42f)
            curveTo(168f, 41f, 124f, 41f, 86f, 42f)
            close()
        }
        // Montante centrale del vetro
        path(fill = SolidColor(Color.White), fillAlpha = 0.94f) {
            moveTo(146f, 24f)
            lineTo(150f, 24f)
            lineTo(148f, 42f)
            lineTo(144f, 42f)
            close()
        }
        // Ruota posteriore (cerchio + canale)
        path(
            fill = SolidColor(Color.White),
            fillAlpha = 0.88f,
            pathFillType = PathFillType.EvenOdd,
        ) {
            moveTo(69f, 71f)
            arcTo(17f, 17f, 0f, true, true, 68.99f, 71f)
            close()
            moveTo(69f, 71f)
            arcTo(8.5f, 8.5f, 0f, true, false, 68.99f, 71f)
            close()
        }
        // Ruota anteriore
        path(
            fill = SolidColor(Color.White),
            fillAlpha = 0.88f,
            pathFillType = PathFillType.EvenOdd,
        ) {
            moveTo(209f, 71f)
            arcTo(17f, 17f, 0f, true, true, 208.99f, 71f)
            close()
            moveTo(209f, 71f)
            arcTo(8.5f, 8.5f, 0f, true, false, 208.99f, 71f)
            close()
        }
        // Linea di cintura, sottile
        path(
            stroke = SolidColor(Color.White),
            strokeAlpha = 0.35f,
            strokeLineWidth = 1.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(46f, 52f)
            curveTo(90f, 50f, 150f, 50f, 226f, 52f)
        }
    }.build()
}

@Composable
fun HeroCar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = Prefs.get(context).carPhotoVersion
    val custom = remember(version) { if (version != 0L) CarPhoto.load(context) else null }
    val photoId = remember {
        context.resources.getIdentifier("car_highland", "drawable", context.packageName)
    }
    Box(modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        if (custom != null) {
            Image(
                bitmap = custom,
                contentDescription = "Auto",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)),
            )
        } else if (photoId != 0) {
            Image(
                painter = painterResource(photoId),
                contentDescription = "Auto",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
        } else {
            Image(
                imageVector = VigiloCarSide,
                contentDescription = "Auto",
                modifier = Modifier.fillMaxWidth(0.92f),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFF4F5F7)),
            )
        }
    }
}
