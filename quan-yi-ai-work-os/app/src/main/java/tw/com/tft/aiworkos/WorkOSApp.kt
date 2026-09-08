package tw.com.tft.aiworkos

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class WorkOSApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("workos_reminders", "工作提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "全益 AI Work OS 案件提醒"
                }
            )
        }
    }
}
