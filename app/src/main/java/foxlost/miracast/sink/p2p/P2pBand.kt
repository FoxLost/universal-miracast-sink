package foxlost.miracast.sink.p2p

/** Session-only radio policy. WFD/RTSP capabilities do not select a Wi-Fi band. */
enum class P2pBand(val excludedFrequencies: String?) {
    AUTO(null),
    GHZ_2_4("0-2400,2500-7125"),
    GHZ_5("0-4900,5900-7125");

    fun supportedBy(has5Ghz: Boolean): Boolean = this != GHZ_5 || has5Ghz

    fun accepts(frequencyMhz: Int): Boolean = when (this) {
        AUTO -> true
        GHZ_2_4 -> frequencyMhz > 2400 && frequencyMhz < 2500
        GHZ_5 -> frequencyMhz > 4900 && frequencyMhz < 5900
    }

    companion object {
        fun fromPreference(raw: String?): P2pBand = entries.firstOrNull { it.name == raw } ?: AUTO
    }
}
