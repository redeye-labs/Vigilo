package io.github.redeyelabs.vigilo.ui

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Scanner QR di Google Play Services: nessun permesso fotocamera per l'app, il modulo si scarica da solo.
 * Senza Play Services resta il link incollato (o la fotocamera del telefono, che apre vigilo://pair).
 */
object QrScan {
    fun start(ctx: Context, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        runCatching {
            GmsBarcodeScanning.getClient(ctx, options).startScan()
                .addOnSuccessListener { code -> code.rawValue?.let(onResult) }
                .addOnFailureListener { onError("Scanner non disponibile: incolla il link di abbinamento.") }
        }.onFailure { onError("Scanner non disponibile: incolla il link di abbinamento.") }
    }
}
