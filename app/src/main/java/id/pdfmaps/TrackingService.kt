package id.pdfmaps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Foreground service agar GPS tetap aktif saat merekam track atau memantau geofence. */
class TrackingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pelacakan GPS", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("PdfMaps aktif")
            .setContentText("Merekam track atau memantau geofence")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, n)
        (application as PdfMapsApp).state.startGps()
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "tracking"

        fun sync(ctx: Context, on: Boolean) {
            val i = Intent(ctx, TrackingService::class.java)
            try {
                if (on) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
            } catch (_: Exception) {
            }
        }
    }
}
