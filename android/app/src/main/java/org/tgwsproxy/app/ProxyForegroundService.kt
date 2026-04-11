package org.tgwsproxy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.tgwsproxy.core.CfProxyDomainStore
import org.tgwsproxy.core.MtProtoProxyServer
import org.tgwsproxy.core.Stats
import org.tgwsproxy.core.WsPool

class ProxyForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    private var acceptJob: Job? = null
    private var proxyServer: MtProtoProxyServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopProxy()
            stopSelf()
            return START_NOT_STICKY
        }
        startProxy()
        return START_STICKY
    }

    private fun startProxy() {
        val channelId = ensureChannel()
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_proxy_notif)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        val repo = ProxyPreferencesRepository(this)
        val config = try {
            runBlocking { repo.currentConfig() }
        } catch (e: IllegalArgumentException) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        runBlocking {
            CfProxyDomainStore.refreshFromGitHubIfEnabled(config)
        }

        acceptJob?.cancel()
        proxyServer?.stop()

        val stats = Stats()
        val wsPool = WsPool(serviceScope, config.poolSize)
        val server = MtProtoProxyServer(config, stats, wsPool)
        proxyServer = server
        acceptJob = server.start(serviceScope)
    }

    private fun stopProxy() {
        acceptJob?.cancel()
        acceptJob = null
        proxyServer?.stop()
        proxyServer = null
    }

    override fun onDestroy() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopProxy()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel(): String {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
        return channelId
    }

    companion object {
        private const val CHANNEL_ID = "tg_ws_proxy"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "org.tgwsproxy.app.STOP_PROXY"

        fun start(context: Context) {
            val i = Intent(context, ProxyForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                @Suppress("DEPRECATION")
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ProxyForegroundService::class.java).apply {
                    action = ACTION_STOP
                },
            )
        }
    }
}
