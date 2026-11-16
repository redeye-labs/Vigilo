package io.github.redeyelabs.vigilo

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.github.redeyelabs.vigilo.ui.MainViewModel
import io.github.redeyelabs.vigilo.ui.VigiloRoot
import io.github.redeyelabs.vigilo.ui.VigiloTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleIntent(intent)
        val startTab = intent?.getStringExtra("tab")
        setContent { VigiloTheme { VigiloRoot(vm, startTab) } }
    }

    override fun onResume() {
        super.onResume()
        vm.onAppResume()
    }

    override fun onPause() {
        vm.onAppPause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Abbinamento Home Assistant (vigilo://pair) o ritorno dal login Tesla: vigilo://oauth/callback?code=...&state=... (inoltrato dal companion web). */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "vigilo" && data.host == "pair") {
            vm.pairBackend(data.toString())
            intent.data = null
            return
        }
        if (data.scheme == "vigilo" && data.host == "oauth") {
            val err = data.getQueryParameter("error")
            val code = data.getQueryParameter("code")
            when {
                err != null -> vm.toast("Login Tesla annullato: $err")
                code != null -> vm.handleCallback(code, data.getQueryParameter("state"))
            }
            intent.data = null
        }
    }
}
