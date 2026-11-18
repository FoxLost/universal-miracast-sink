package foxlost.miracast.sink

/** Resolves only the selected peer on the current P2P interface, within the control-startup window. */
internal object P2pPeerAddressResolver {
    private val macPattern = Regex("^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")
    private val interfacePattern = Regex("^[a-zA-Z0-9_.-]{1,40}$")
    private val whitespace = Regex("\\s+")

    fun resolveFromArp(
        peerMac: String,
        interfaceName: String,
        groupOwnerAddress: String?,
        startedNanos: Long,
        readInterfaceMac: () -> String?,
        readTable: () -> String?,
        isCurrent: () -> Boolean,
        nowNanos: () -> Long = System::nanoTime,
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
    ): String? {
        val deviceMac = validMac(peerMac) ?: return null
        if (!interfacePattern.matches(interfaceName)) return null
        var interfaceMac: String? = null
        fun active(): Boolean = !Thread.currentThread().isInterrupted && isCurrent() &&
            remainingControlConnectMs(startedNanos, nowNanos()) > 0L
        while (active()) {
            if (interfaceMac == null) interfaceMac = readInterfaceMac()?.let(::validMac)
            if (!active()) return null
            val table = readTable()
            if (!active()) return null
            // An authoritative interface identity takes precedence over a potentially stale
            // device-MAC row. Without one, only an exact device identity is eligible, never
            // the first neighbor or a MAC manufactured by flipping address bits.
            val address = table?.let {
                findAddress(it, interfaceName, interfaceMac ?: deviceMac, groupOwnerAddress)
            }
            if (address != null) return address
            val remaining = remainingControlConnectMs(startedNanos, nowNanos())
            if (remaining <= 0L) return null
            try {
                sleeper(minOf(250L, remaining))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun validMac(value: String): String? {
        val mac = value.trim().lowercase()
        if (!macPattern.matches(mac) || mac.substring(0, 2).toInt(16) and 1 != 0 ||
            mac == "00:00:00:00:00:00") return null
        return mac
    }

    private fun findAddress(contents: String, iface: String, mac: String, ownAddress: String?): String? {
        var match: String? = null
        for (line in contents.lineSequence()) {
            val fields = line.trim().split(whitespace)
            if (fields.size < 6 || fields[5] != iface || !fields[3].equals(mac, ignoreCase = true)) continue
            val flags = fields[2].removePrefix("0x").toIntOrNull(16) ?: continue
            if (flags and 2 == 0) continue // Incomplete neighbor entries are not readiness.
            val ip = fields[0]
            if (ip == ownAddress || !isUnicastIpv4(ip)) continue
            if (match != null && match != ip) return null // Do not guess among multiple addresses.
            match = ip
        }
        return match
    }

    private fun isUnicastIpv4(value: String): Boolean {
        val octets = value.split('.')
        if (octets.size != 4) return false
        for ((index, octet) in octets.withIndex()) {
            if (octet.isEmpty() || octet.length > 3 || (octet.length > 1 && octet[0] == '0') ||
                octet.any { it !in '0'..'9' }) return false
            val number = octet.toInt()
            if (number !in 0..255 || (index == 0 && (number !in 1..223 || number == 127))) return false
        }
        return true
    }
}
