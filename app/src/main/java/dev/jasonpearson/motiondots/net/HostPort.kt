package dev.jasonpearson.motiondots.net

data class HostPort(val host: String, val port: Int) {
    companion object {
        /** Parses "host:port", "ws://host:port/" or "[v6]:port". Returns null if invalid. */
        fun parse(input: String): HostPort? {
            var s = input.trim()
            if (s.startsWith("ws://")) s = s.removePrefix("ws://")
            s = s.trimEnd('/')
            if (s.isEmpty()) return null
            val host: String
            val portText: String
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 2 || s.getOrNull(end + 1) != ':') return null
                host = s.substring(1, end)
                portText = s.substring(end + 2)
            } else {
                val idx = s.indexOf(':')
                if (idx <= 0 || idx != s.lastIndexOf(':')) return null
                host = s.substring(0, idx)
                portText = s.substring(idx + 1)
            }
            val port = portText.toIntOrNull() ?: return null
            if (port !in 1..65535 || host.any { it.isWhitespace() }) return null
            return HostPort(host, port)
        }
    }
}
