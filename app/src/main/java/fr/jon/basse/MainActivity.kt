package fr.jon.basse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import fr.jon.basse.ui.BasseApp
import fr.jon.basse.ui.UiCallbacks

class MainActivity : ComponentActivity() {
    private val resumeTick = androidx.compose.runtime.mutableIntStateOf(0)
    private var watcher: OutputWatcher? = null

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        watcher = OutputWatcher(this) { EqState.setOutput(it, this) }.also { it.start() }
    }

    override fun onPause() {
        watcher?.stop(); watcher = null
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EqState.init(this)
        enableEdgeToEdge()
        if (EqState.settings.value.enabled) EqService.start(this)

        setContent {
            val settings by EqState.settings.collectAsState()
            val status by EqState.status.collectAsState()
            val output by EqState.output.collectAsState()
            val lastApp by EqState.lastApp.collectAsState()
            val tick = resumeTick.intValue
            val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick.intValue++ }

            val cb = UiCallbacks(
                toggle = {
                    val on = !EqState.settings.value.enabled
                    if (on && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    EqState.update(this) { it.copy(enabled = on) }
                    if (on) EqService.start(this)
                },
                select = { id ->
                    EqState.select(this, id)
                    if (EqState.settings.value.enabled) EqService.start(this)
                },
                setMode = { video ->
                    val s = EqState.settings.value
                    EqState.select(this, if (video) s.lastVideoId else s.lastMusicId)
                },
                update = { f -> EqState.update(this, f) },
                askMic = { micPerm.launch(Manifest.permission.RECORD_AUDIO) },
            )
            MaterialTheme(colorScheme = darkColorScheme()) {
                BasseApp(settings, status, output, lastApp, tick, cb)
            }
        }
    }
}
