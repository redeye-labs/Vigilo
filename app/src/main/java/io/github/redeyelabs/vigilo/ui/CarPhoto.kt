package io.github.redeyelabs.vigilo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import io.github.redeyelabs.vigilo.data.Prefs
import java.io.File

/**
 * Foto dell'auto scelta dall'utente: copiata (ridimensionata) nella memoria interna dell'app,
 * così non servono permessi di archiviazione e resta anche se l'originale viene cancellato.
 */
object CarPhoto {
    private const val FILE = "car_custom.jpg"
    private const val MAX_SIDE = 1600

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** Copia la foto scelta. True se riuscita. Da chiamare fuori dal thread principale. */
    fun import(ctx: Context, uri: Uri): Boolean = runCatching {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bmp = cr.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val rotation = runCatching {
            cr.openInputStream(uri)!!.use { ExifInterface(it).rotationDegrees }
        }.getOrDefault(0)
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        if (rotation != 0 || scale < 1f) {
            val m = Matrix().apply { postRotate(rotation.toFloat()); postScale(scale, scale) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (!tmp.renameTo(file(ctx))) { tmp.delete(); return false }
        Prefs.get(ctx).carPhotoVersion = System.currentTimeMillis()
        true
    }.getOrDefault(false)

    fun load(ctx: Context): ImageBitmap? = runCatching {
        val f = file(ctx)
        if (!f.isFile) null else BitmapFactory.decodeFile(f.path)?.asImageBitmap()
    }.getOrNull()

    fun hasCustom(ctx: Context) = Prefs.get(ctx).carPhotoVersion != 0L && file(ctx).isFile

    fun reset(ctx: Context) {
        file(ctx).delete()
        Prefs.get(ctx).carPhotoVersion = 0
    }
}
