package foxlost.miracast.sink.uibc

/** One deterministic, supported selection, never a union of unrelated HID paths. */
data class UibcCapabilityParseResult(
    val capabilities: Set<UibcCapability> = emptySet(),
    val port: Int? = null,
    val advertised: Boolean = false,
    val error: String? = null,
    val category: UibcCategory = UibcCategory.GENERIC,
    val path: UibcInputPath? = null,
    val hidType: UibcCapability? = null,
) {
    val valid: Boolean get() = error == null
}

enum class UibcCategory { GENERIC, HIDC }
enum class UibcInputPath(val wireValue: Int) { USB(1) }
enum class UibcSetting { ENABLED, DISABLED }
data class UibcSettingParseResult(val setting: UibcSetting?, val present: Boolean, val error: String? = null) {
    val valid: Boolean get() = error == null
}

object UibcCapabilityParser {
    private fun bodyValues(body: String, name: String): List<String> = body.lineSequence().mapNotNull {
        val colon = it.indexOf(':')
        if (colon > 0 && it.substring(0, colon).trim().equals(name, true)) it.substring(colon + 1).trim() else null
    }.toList()

    fun parseBody(body: String): UibcCapabilityParseResult {
        val values = bodyValues(body, "wfd_uibc_capability")
        return when (values.size) {
            0 -> UibcCapabilityParseResult()
            1 -> parse(values.single())
            else -> invalid("duplicate UIBC capability parameter")
        }
    }

    fun parse(value: String): UibcCapabilityParseResult {
        if (value.trim().equals("none", true)) return UibcCapabilityParseResult(advertised = true)
        val fields = linkedMapOf<String, String>()
        for (part in value.trim().split(';')) {
            if (part.isBlank()) return invalid("empty UIBC field")
            val pair = part.split('=', limit = 2)
            if (pair.size != 2 || pair[1].isBlank()) return invalid("malformed UIBC field")
            val key = pair[0].trim().lowercase().replace('-', '_')
            if (key !in setOf("input_category_list", "generic_cap_list", "hidc_cap_list", "port")) return invalid("unknown UIBC field")
            if (fields.put(key, pair[1].trim()) != null) return invalid("duplicate UIBC field: $key")
        }
        fun tokens(key: String): List<String> = fields[key]?.split(',')?.map { it.trim().lowercase() } ?: emptyList()
        val categories = tokens("input_category_list")
        if (categories.isEmpty() || categories.any { it !in setOf("generic", "hidc") } || categories.distinct().size != categories.size) return invalid("invalid UIBC categories")
        val generic = tokens("generic_cap_list")
        val hidc = tokens("hidc_cap_list")
        val genericNames = setOf("keyboard", "mouse", "singletouch", "multitouch", "joystick", "camera", "gesture", "remotecontrol")
        val pathNames = setOf("infrared", "usb", "bt", "bluetooth", "zigbee", "wi-fi", "wifi", "no-sp", "nosp", "nospecific")
        if (generic.distinct().size != generic.size || hidc.distinct().size != hidc.size) return invalid("duplicate UIBC capability")
        if (generic.any { it != "none" && it !in genericNames } || ("none" in generic && generic.size != 1)) return invalid("invalid Generic capability")
        if (generic.any { it != "none" } && "generic" !in categories) return invalid("Generic capability without category")
        for (token in hidc) {
            if (token == "none" && hidc.size == 1) continue
            val pair = token.split('/')
            if (pair.size != 2 || pair[0] !in genericNames || pair[1] !in pathNames) return invalid("invalid HIDC type/path")
            if ("hidc" !in categories) return invalid("HIDC capability without category")
        }
        val portValue = fields["port"] ?: return invalid("UIBC capability has no port")
        val port = if (portValue.equals("none", true)) null else {
            if (!portValue.all { it in '0'..'9' }) return invalid("invalid UIBC port")
            portValue.toIntOrNull()?.takeIf { it in 1..65535 } ?: return invalid("UIBC port out of range")
        }
        val hidType = when {
            "multitouch/usb" in hidc -> UibcCapability.MultiTouch
            "singletouch/usb" in hidc -> UibcCapability.SingleTouch
            else -> null
        }
        if (hidType != null) return UibcCapabilityParseResult(setOf(hidType), port, true,
            category = UibcCategory.HIDC, path = UibcInputPath.USB, hidType = hidType)
        val touches = buildSet {
            if ("singletouch" in generic) add(UibcCapability.SingleTouch)
            if ("multitouch" in generic) add(UibcCapability.MultiTouch)
        }
        if (touches.isEmpty()) return invalid("no supported touch category/type/path")
        return UibcCapabilityParseResult(touches, port, true)
    }

    fun parseSettingBody(body: String): UibcSettingParseResult {
        val values = bodyValues(body, "wfd_uibc_setting")
        return when (values.size) {
            0 -> UibcSettingParseResult(null, false)
            1 -> parseSetting(values.single())
            else -> UibcSettingParseResult(null, true, "duplicate UIBC setting")
        }
    }

    fun parseSetting(value: String): UibcSettingParseResult = when (value.trim().lowercase()) {
        "1", "enable", "enabled", "on", "true" -> UibcSettingParseResult(UibcSetting.ENABLED, true)
        "0", "disable", "disabled", "off", "false" -> UibcSettingParseResult(UibcSetting.DISABLED, true)
        else -> UibcSettingParseResult(null, true, "unsupported UIBC setting")
    }

    private fun invalid(reason: String) = UibcCapabilityParseResult(advertised = true, error = reason)
}
