package eu.stgm.sol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import eu.stgm.sol.ascolto.AscoltoService

// Schermata minima: Sol vive nella voce e nella notifica, non qui. Questa
// activity serve a tre cose: chiedere i permessi, avviare il servizio (anche
// dopo un riavvio: il microfono vuole un'app in primo piano) e provare la
// pipeline senza wake word col pulsante Parla.
class MainActivity : ComponentActivity() {

    private var permessoMic by mutableStateOf(false)

    private val chiediPermessi =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permessoMic = haMicrofono()
            if (permessoMic) avviaServizio()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permessoMic = haMicrofono()
        if (permessoMic && intent.getBooleanExtra(EXTRA_RIATTIVA, false)) {
            avviaServizio()
        }
        setContent {
            MaterialTheme {
                Schermata()
            }
        }
    }

    @Composable
    private fun Schermata() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Sol", style = MaterialTheme.typography.headlineMedium)
            if (!permessoMic) {
                Text(stringResource(R.string.serve_microfono))
                Button(onClick = { chiedi() }) {
                    Text(stringResource(R.string.dai_permessi))
                }
            } else {
                Text(stringResource(R.string.pronto))
                Button(onClick = { avviaServizio() }) {
                    Text(stringResource(R.string.avvia_ascolto))
                }
                Button(onClick = { parlaOra() }) {
                    Text(stringResource(R.string.parla_ora))
                }
            }
        }
    }

    private fun haMicrofono(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun chiedi() {
        val permessi = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            permessi += Manifest.permission.POST_NOTIFICATIONS
        }
        chiediPermessi.launch(permessi.toTypedArray())
    }

    private fun avviaServizio() {
        ContextCompat.startForegroundService(this, Intent(this, AscoltoService::class.java))
    }

    private fun parlaOra() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, AscoltoService::class.java).setAction(AscoltoService.AZIONE_PARLA),
        )
    }

    companion object {
        const val EXTRA_RIATTIVA = "riattiva"
    }
}
