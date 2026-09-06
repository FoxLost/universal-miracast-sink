package foxlost.miracast.sink

/** Resolves a P2P client's IPv4 address from the kernel ARP table. */
internal object P2pPeerAddressResolver {
    private val macPattern = Regex("^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")
    private val ipv4Pattern = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")

    fun parseArpTable(contents: String): Map<String, String> = buildMap {
        contents.lineSequence().forEach { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 4) return@forEach
            val ip = fields[0]
            val mac = fields[3].lowercase()
            if (!ipv4Pattern.matches(ip) || !macPattern.matches(mac)) return@forEach
            if (ip == "0.0.0.0" || mac == "00:00:00:00:00:00") return@forEach
            put(mac, ip)
        }
    }

    /**
     * Read the ARP table from the normal app view first, then use a privileged
     * reader when procfs permissions deny that view. Both readers are invoked
     * at most once; the caller owns the bounded retry policy.
     */
    fun readTableWithFallback(
        directReader: () -> String?,
        rootReader: () -> String?,
    ): String? {
        val direct = runCatching { directReader() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (direct != null) return direct
        return runCatching { rootReader() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun resolveFromArp(
        peerMac: String,
        readTable: () -> String?,
        attempts: Int = 8,
        retryDelayMs: Long = 250L,
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
    ): String? {
        val normalizedMac = peerMac.trim().lowercase()
        if (!macPattern.matches(normalizedMac) || attempts <= 0) return null
        repeat(attempts) { attempt ->
            val address = readTable()?.let(::parseArpTable)?.get(normalizedMac)
            if (address != null) return address
            if (attempt + 1 < attempts && retryDelayMs > 0) sleeper(retryDelayMs)
        }
        return null
    }
}
