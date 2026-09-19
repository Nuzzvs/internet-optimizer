package com.internetoptimizer.tunnel

import android.app.Notification
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.internetoptimizer.R

/**
 * The VpnService that creates the TUN interface.
 *
 * This is the core Android component required by the app's architecture:
 *
 * - Declared in AndroidManifest.xml with
 *   `android:permission="android.permission.BIND_VPN_SERVICE"`.
 * - [onStartCommand] is called when the service is explicitly started
 *   by the app's MainActivity toggle.
 * - [Builder] configures the TUN interface: IP addresses, routes, DNS,
 *   and excluded applications (addDisallowedApplication).
 *
 * The actual tunnel work (reading/writing packets from the TUN fd) is
 * delegated to [TunnelService], which calls the native Rust library.
 *
 * Why a separate VpnService + Service split?
 *   Android requires the VPN setup in VpnService (it has the privileged
 *   `establish()` method). But we want the tunnel logic
 *   (native JNI, coroutines) in a regular Service for cleaner lifecycle
 *   management and foreground notification handling.
 *
 * Flow:
 *   1. MainActivity → startForegroundService(TunnelService.ACTION_START)
 *   2. TunnelService.onStartCommand → starts tunnel
 *   3. TunnelManager uses VpnServiceProvider to get the VpnService
 *   4. VpnService.Builder.establish() creates the TUN fd
 *   5. TunnelManager passes the fd to nativeRunTunnel()
 *   6. Native Rust code reads/writes packets on the fd
 */
class OptimizerVpnService : VpnService() {

    companion object {
        private const val TAG = "OptimizerVpnService"
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        // Register this VpnService instance so TunnelManager can access it
        VpnServiceProvider.set(this)
        Log.i(TAG, "VpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "VpnService startCommand: ${intent?.action}")
        // MUST call startForeground() IMMEDIATELY to avoid
        // ForegroundServiceDidNotStartInTimeException on Android 8+.
        // This service is launched via startForegroundService() from TunnelService,
        // so we must promote it to foreground right away, even before the tunnel
        // is established. The notification is a basic placeholder; the real
        // tunnel notification is managed by TunnelService.
        startForeground(2, buildPlaceholderNotification())
        return START_STICKY
    }

    private fun buildPlaceholderNotification(): Notification {
        val channelId = "vpn_service_channel"
        // Ensure the notification channel exists (required on Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Serviço VPN",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Notificação do serviço VPN do Internet Optimizer"
            val mgr = getSystemService(android.app.NotificationManager::class.java)
            mgr?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Internet Optimizer")
            .setContentText("Túnel VPN em estabelecimento")
            .setSmallIcon(R.drawable.ic_stat_name)
            .build()
    }

    /**
     * Build and establish the TUN interface.
     * Called by [TunnelManager] via [VpnServiceProvider].
     *
     * Sets up:
     * - TUN IP: 10.0.0.2/32 (IPv4), fe80::2/128 (IPv6)
     * - Routes: all traffic (0.0.0.0/0, ::/0)
     * - DNS server: the configured DoH upstream IP
     * - Excluded apps: apps the user chose to bypass the tunnel
     *
     * @param dnsServer The DNS server IP to set (e.g. "1.1.1.1")
     * @param excludedApps List of package names to exclude from the tunnel
     * @return ParcelFileDescriptor of the TUN interface, or null on failure
     */
    fun establishTunnel(
        dnsServer: String = "1.1.1.1",
        excludedApps: List<String> = emptyList(),
    ): ParcelFileDescriptor? {
        return try {
            val builder = this.Builder()
                .setSession("Internet Optimizer")
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)
                .addAddress("fe80::2", 128)
                .addRoute("0.0.0.0", 0) // All IPv4 traffic
                .addRoute("::", 0)       // All IPv6 traffic
                .addDnsServer(dnsServer)
                .setBlocking(true)

            // Exclude specific apps from the tunnel (e.g. banking apps)
            for (pkg in excludedApps) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "App not found, skipping exclusion: $pkg")
                }
            }

            vpnInterface = builder.establish()
            vpnInterface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish TUN interface", e)
            null
        }
    }

    fun getTunnelFd(): ParcelFileDescriptor? = vpnInterface

    override fun onDestroy() {
        super.onDestroy()
        vpnInterface?.close()
        vpnInterface = null
        VpnServiceProvider.clear()
        Log.i(TAG, "VpnService destroyed")
    }

    override fun onRevoke() {
        // Called when the user revokes VPN permission from system settings
        Log.w(TAG, "VPN permission revoked by user")
        stopService(Intent(this, TunnelService::class.java))
        super.onRevoke()
    }
}
