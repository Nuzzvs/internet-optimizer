package com.internetoptimizer.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.internetoptimizer.R
import com.internetoptimizer.app.ConfigManager
import com.internetoptimizer.app.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Foreground service that hosts the VpnService and runs the native tunnel.
 *
 * This service is started by [MainActivity] when the user toggles the
 * tunnel on. It:
 *
 * 1. Reads the VpnService (ensures [VpnServiceProvider] is populated).
 * 2. Establishes the TUN interface via [OptimizerVpnService.establishTunnel].
 * 3. Passes the TUN fd to the native Rust tunnel engine.
 * 4. Maintains a persistent notification (required for foreground services
 *    on Android 8+).\n *
 * The service runs on a background coroutine. When the system kills the
 * process, the tunnel is lost, but [BootReceiver] can restart it if
 * auto-start is enabled.
 *
 * KEY FIX: The service now publishes its [TunnelState] to
 * [TunnelStateRepository] so the UI can observe the real tunnel status
 * instead of guessing.
 */
class TunnelService : Service(), CoroutineScope {

    companion object {
        private const val TAG = "TunnelService"
        const val CHANNEL_ID = "tunnel_channel"
        const val NOTIFICATION_ID = 1

        // Intent actions
        const val ACTION_START = "com.internetoptimizer.action.START_TUNNEL"
        const val ACTION_STOP = "com.internetoptimizer.action.STOP_TUNNEL"
    }

    private val job = Job()
    override val coroutineContext: CoroutineContext = Dispatchers.IO + job

    private var configManager: ConfigManager? = null
    private var tunnelManager: TunnelManager? = null

    // Internal tunnel state — kept for debugging, but the authoritative
    // state that the UI reads is TunnelStateRepository
    val tunnelState: StateFlow<TunnelState> = TunnelStateRepository.tunnelState

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: ${intent?.action}")

        // Initialize ConfigManager
        if (configManager == null) {
            configManager = ConfigManager(this)
        }

        when (intent?.action) {
            ACTION_START -> {
                // MUST call startForeground() IMMEDIATELY (before any potentially
                // long-running work) to avoid ForegroundServiceDidNotStartInTimeException.
                startForeground(NOTIFICATION_ID, buildNotification(isRunning = false))
                TunnelStateRepository.updateState(TunnelState.Starting)

                // Ensure the VpnService is running so VpnServiceProvider is populated
                val vpnIntent = Intent(this, OptimizerVpnService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(vpnIntent)
                } else {
                    startService(vpnIntent)
                }
                startTunnel(intent)
            }
            ACTION_STOP -> {
                stopTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY // Restart if killed by the system
    }

    private fun startTunnel(intent: Intent?) {
        val config = configManager?.loadConfig() ?: return

        // Wait for the VpnService to be registered (with timeout)
        launch {
            TunnelStateRepository.updateState(TunnelState.Connecting)
            updateNotification(isRunning = false, status = "Conectando...")

            // Poll for up to 5 seconds waiting for VpnServiceProvider to be set
            var vpnService: OptimizerVpnService? = VpnServiceProvider.get() as? OptimizerVpnService
            var attempts = 0
            while (vpnService == null && attempts < 50) {
                delay(100)
                vpnService = VpnServiceProvider.get() as? OptimizerVpnService
                attempts++
            }

            if (vpnService == null) {
                Log.e(TAG, "VpnService not registered after timeout")
                TunnelStateRepository.updateState(TunnelState.Stopped)
                updateNotification(isRunning = false, status = "Pronto")
                return@launch
            }

            // Extract config extras (if provided from MainActivity)
            val dnsEndpoint = intent?.getStringExtra("dns_endpoint") ?: config.dnsEndpoint
            val dnsUpstreamIp = intent?.getStringExtra("dns_upstream") ?: config.dnsUpstreamIp
            val excludedApps = intent?.getStringArrayListExtra("excluded_apps") ?: config.excludedApps

            tunnelManager = TunnelManager(vpnService)

            val result = tunnelManager?.startTunnel(
                dnsEndpoint = dnsEndpoint,
                dnsUpstreamIp = dnsUpstreamIp,
                excludedApps = excludedApps,
            )
            if (result == true) {
                TunnelStateRepository.updateState(TunnelState.Running)
                updateNotification(isRunning = true, status = "Otimizando conexão...")
            } else {
                TunnelStateRepository.updateState(TunnelState.Stopped)
                updateNotification(isRunning = false, status = "Desativado")
            }
        }
    }

    private fun stopTunnel() {
        tunnelManager?.stopTunnel()
        TunnelStateRepository.updateState(TunnelState.Stopped)
        updateNotification(isRunning = false, status = "Pronto para otimizar")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTunnel()
        job.cancel()
    }

    private fun updateNotification(isRunning: Boolean, status: String) {
        val notification = buildNotification(isRunning, statusText = status)
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description =
                getString(R.string.notification_channel_desc)
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        isRunning: Boolean,
        statusText: String = "",
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Internet Optimizer")
            .setSmallIcon(R.drawable.ic_stat_name)

        if (isRunning || statusText.isNotEmpty()) {
            builder.setContentText(statusText.ifEmpty { getString(R.string.notification_content_running) })
            builder.setOngoing(true)
            builder.addAction(
                android.R.drawable.ic_delete,
                getString(R.string.notification_action_stop),
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(ACTION_STOP).setClassName(
                        packageName, TunnelService::class.java.name
                    ),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ),
            )
        } else {
            builder.setContentText("Pronto para otimizar")
            builder.setOngoing(false)
        }

        return builder.build()
    }
}
