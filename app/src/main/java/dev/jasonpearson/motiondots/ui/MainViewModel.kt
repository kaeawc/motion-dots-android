package dev.jasonpearson.motiondots.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.jasonpearson.motiondots.net.DiscoveredMac
import dev.jasonpearson.motiondots.net.MacDiscovery
import dev.jasonpearson.motiondots.stream.MacPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns UI-side discovery (only while the Activity is visible) and the remembered Mac. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val discovery = MacDiscovery(app)
    private val prefs = MacPrefs(app)

    val macs: StateFlow<List<DiscoveredMac>> = discovery.macs

    private val _lastMac = MutableStateFlow(prefs.load())
    val lastMac: StateFlow<DiscoveredMac?> = _lastMac.asStateFlow()

    fun onVisible() {
        _lastMac.value = prefs.load()
        discovery.start()
    }

    fun onHidden() = discovery.stop()

    override fun onCleared() {
        discovery.stop()
    }
}
