package dev.jasonpearson.motiondots.stream

import android.content.Context
import androidx.core.content.edit
import dev.jasonpearson.motiondots.net.DiscoveredMac

/** Remembers the last Mac so Start can reconnect without discovery. */
class MacPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mac", Context.MODE_PRIVATE)

    fun load(): DiscoveredMac? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val port = prefs.getInt(KEY_PORT, -1)
        if (port <= 0) return null
        return DiscoveredMac(prefs.getString(KEY_NAME, null) ?: host, host, port)
    }

    fun save(mac: DiscoveredMac) {
        prefs.edit {
            putString(KEY_HOST, mac.host)
            putInt(KEY_PORT, mac.port)
            putString(KEY_NAME, mac.name)
        }
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_NAME = "name"
    }
}
