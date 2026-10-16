package dev.jasonpearson.motiondots

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val channel =
            NotificationChannel(
                STREAM_CHANNEL_ID,
                getString(R.string.notification_channel_stream),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.description = getString(R.string.notification_channel_stream_description)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        /** Notification channel used by the foreground streaming service. */
        const val STREAM_CHANNEL_ID = "stream"
    }
}
