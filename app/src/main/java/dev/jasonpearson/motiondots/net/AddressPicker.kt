package dev.jasonpearson.motiondots.net

import java.net.Inet4Address
import java.net.InetAddress

/** Chooses the best address to connect to from an mDNS resolution. Prefers IPv4. */
object AddressPicker {
    fun pick(addresses: List<InetAddress>): String? {
        addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.let {
            return it.hostAddress
        }
        val v6 = addresses.firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }
        val chosen = v6 ?: addresses.firstOrNull() ?: return null
        // Strip any "%scope" suffix; callers wrap IPv6 literals in brackets when building URLs.
        return chosen.hostAddress?.substringBefore('%')
    }
}
