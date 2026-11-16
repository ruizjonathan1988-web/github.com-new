package fr.jon.basse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class EqService : Service() {
    companion object {
        private const val CHANNEL = "basse_eq"
        private const val NOTIF_ID = 17
        const val ACTION_STOP = "fr.jon.basse.STOP"
        const val ACTION_NEXT = "fr.jon.basse.NEXT"
        const val EXTRA_OPEN = "open_session"
        const val EXTRA_CLOSE = "close_session"

        fun start(ctx: Context, extras: Intent.() -> Unit = {}) {
            val i = Intent(ctx, EqService::class.java).apply(extras)
            runCatching {
                ctx.startForegroundService(i)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = EqEngine()
    private var lastGlobalWanted: Boolean? = null
    private lateinit var outputs: OutputWatcher
    private lateinit var poller: ForegroundPoller

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = handleSessionIntent(i)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        EqState.init(this)
        createChannel()
        goForeground(EqState.settings.value)
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(this, sessionReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        outputs = OutputWatcher(this) { EqState.setOutput(it) }
        outputs.start()
        poller = ForegroundPoller(this, onTick = { EqState.setVolume(musicVolume()) }) { pkg -> EqState.onForegroundApp(this, pkg) }
        poller.start()

        scope.launch {
            combine(EqState.settings, EqState.output, EqState.volume) { s, o, v -> Triple(s, o, v) }.collect { (s, o, v) ->
                if (!s.enabled) {
                    engine.setActive(false)
                    engine.releaseAll()
                    publish(null)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                engine.setCurve(EqState.currentChain(s, o, v), s.keepVolume, s.balance, s.ceilingDb)
                if (lastGlobalWanted != s.globalMode) {
                    lastGlobalWanted = s.globalMode
                    if (s.globalMode) engine.attach(0) else engine.detach(0)
                }
                engine.setActive(!EqState.pausedBySpeaker(s, o))
                publish(null)
                notifyUpdate(s)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(EqState.settings.value)
        when {
            intent?.action == ACTION_STOP -> EqState.update(this) { it.copy(enabled = false) }
            intent?.action == ACTION_NEXT -> EqState.nextFavorite(this)
            intent != null -> handleSessionIntent(intent)
        }
        return START_STICKY
    }

    private fun handleSessionIntent(i: Intent) {
        val open = i.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION || i.hasExtra(EXTRA_OPEN)
        val close = i.action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION || i.hasExtra(EXTRA_CLOSE)
        val session = when {
            i.hasExtra(EXTRA_OPEN) -> i.getIntExtra(EXTRA_OPEN, -1)
            i.hasExtra(EXTRA_CLOSE) -> i.getIntExtra(EXTRA_CLOSE, -1)
            else -> i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        }
        if (session <= 0) return
        if (open) engine.attach(session) else if (close) engine.detach(session)
        publish(null)
    }

    private fun publish(error: String?) {
        val wantGlobal = EqState.settings.value.globalMode
        EqState.setStatus(
            EngineStatus(
                globalOk = if (wantGlobal) engine.hasGlobal() else null,
                sessions = engine.playerSessions(),
                error = error,
            )
        )
    }

    private fun musicVolume(): Float {
        val am = getSystemService(android.media.AudioManager::class.java)
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    private fun createChannel() {
        run {
            val ch = NotificationChannel(CHANNEL, "Égaliseur actif", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(s: EqSettings): Notification {
        val name = EqState.find(s.presetId)?.second?.name ?: "—"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EqService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val next = PendingIntent.getService(
            this, 2, Intent(this, EqService::class.java).setAction(ACTION_NEXT), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL)
        if (s.favorites.size > 1) b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notif), "Favori suivant", next).build())
        return b.setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Basse · $name")
            .setContentText("Égaliseur FT1 actif")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notif), "Couper", stop).build())
            .build()
    }

    private fun goForeground(s: EqSettings) {
        val n = buildNotification(s)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifyUpdate(s: EqSettings) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s)) }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sessionReceiver) }
        if (::outputs.isInitialized) outputs.stop()
        if (::poller.isInitialized) poller.stop()
        engine.releaseAll()
        scope.cancel()
        EqState.setStatus(EngineStatus())
        super.onDestroy()
    }
}

/** Reçoit les sessions des lecteurs même si le service dort, et relance après redémarrage. */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        EqState.init(ctx)
        if (!EqState.settings.value.enabled) return
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                val s = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                EqService.start(ctx) { putExtra(EqService.EXTRA_OPEN, s) }
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                val s = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                EqService.start(ctx) { putExtra(EqService.EXTRA_CLOSE, s) }
            }
            else -> EqService.start(ctx)
        }
    }
}
