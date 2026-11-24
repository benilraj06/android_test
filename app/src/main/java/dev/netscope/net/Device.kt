package dev.netscope.net

enum class DeviceType(val label: String) {
    ROUTER("Router"),
    PHONE("Phone"),
    TABLET("Tablet"),
    COMPUTER("Computer"),
    TV("TV & streaming"),
    SPEAKER("Speaker"),
    PRINTER("Printer"),
    CAMERA("Camera"),
    NAS("Storage"),
    CONSOLE("Game console"),
    IOT("Smart home"),
    UNKNOWN("Unknown device"),
}

data class UpnpDevice(
    val server: String? = null,
    val location: String? = null,
    val friendlyName: String? = null,
    val manufacturer: String? = null,
    val modelName: String? = null,
    val deviceType: String? = null,
)

data class NetDevice(
    val ip: String,
    val key: String = "ip:$ip",
    val mac: String? = null,
    val hostname: String? = null,
    val mdnsName: String? = null,
    val netbiosName: String? = null,
    val model: String? = null,
    val upnp: UpnpDevice? = null,
    val services: Set<String> = emptySet(),
    val serviceLabels: List<String> = emptyList(),
    val vendor: String? = null,
    val type: DeviceType = DeviceType.UNKNOWN,
    val latencyMs: Long? = null,
    val probePorts: Set<Int> = emptySet(),
    val isGateway: Boolean = false,
    val isSelf: Boolean = false,
    val online: Boolean = true,
    val isNew: Boolean = false,
    val customName: String? = null,
    val notes: String? = null,
    val isKnown: Boolean = false,
    val firstSeen: Long = 0L,
    val lastSeen: Long = 0L,
    val openPorts: List<Int> = emptyList(),
    val lastPortScan: Long? = null,
) {
    val bestName: String?
        get() = upnp?.friendlyName?.takeIf { it.isNotBlank() }
            ?: serviceLabels.firstOrNull()
            ?: mdnsName?.removeSuffix(".")?.removeSuffix(".local")?.takeIf { it.isNotBlank() }
            ?: netbiosName?.takeIf { it.isNotBlank() }
            ?: hostname?.takeIf { it != ip && it.isNotBlank() }

    val displayName: String
        get() {
            customName?.takeIf { it.isNotBlank() }?.let { return it }
            if (isSelf) return "This device"
            bestName?.let { return it }
            return if (isGateway) "Router" else ip
        }

    val manufacturer: String?
        get() = vendor?.takeIf { it != OuiDatabase.PRIVATE } ?: upnp?.manufacturer

    fun computeKey(): String {
        if (isSelf) return "self"
        if (isGateway) return "gateway"
        val m = mac
        if (m != null && !OuiDatabase.isRandomized(m)) return "mac:" + m.lowercase()
        val name = bestName
        if (name != null) return "name:" + name.lowercase()
        return "ip:$ip"
    }
}

object DeviceClassifier {
    fun classify(d: NetDevice): DeviceType {
        if (d.isGateway) return DeviceType.ROUTER
        if (d.isSelf) return DeviceType.PHONE
        val text = (
            listOfNotNull(
                d.hostname, d.mdnsName, d.netbiosName, d.vendor, d.model,
                d.upnp?.friendlyName, d.upnp?.manufacturer, d.upnp?.modelName, d.upnp?.deviceType, d.upnp?.server,
            ) + d.serviceLabels
            ).joinToString(" ").lowercase()
        val ports = d.probePorts + d.openPorts
        fun has(vararg k: String) = k.any { text.contains(it) }
        fun svc(vararg k: String) = k.any { key -> d.services.any { it.startsWith(key) } }

        return when {
            has("printer", "laserjet", "officejet", "deskjet", "epson", "brother", "canon", "kyocera", "lexmark") ||
                svc("_ipp", "_printer", "_pdl-datastream", "_uscan", "_scanner") || 9100 in ports || 631 in ports -> DeviceType.PRINTER
            has("camera", "ipcam", "hikvision", "dahua", "reolink", "wyze", "arlo", "nvr", "dvr", "doorbell") ||
                554 in ports || 8554 in ports -> DeviceType.CAMERA
            has("playstation", "xbox", "nintendo") -> DeviceType.CONSOLE
            svc("_googlecast", "_androidtvremote", "_amzn-wplay", "_nvstream", "_airplay") ||
                has("chromecast", "roku", "firetv", "fire-tv", "bravia", "smart tv", "smarttv", "tizen", "webos",
                    "appletv", "apple-tv", "apple tv", "shield", "mediarenderer", "-tv", "tv-", " tv") -> DeviceType.TV
            svc("_sonos", "_spotify-connect", "_raop") ||
                has("sonos", "echo", "homepod", "nest-mini", "nest mini", "google-home", "google home", "speaker", "bose") -> DeviceType.SPEAKER
            has("synology", "diskstation", "qnap", "truenas", "unraid", "mycloud", " nas", "nas-") -> DeviceType.NAS
            has("ipad", "tablet", "galaxy-tab", "galaxy tab") -> DeviceType.TABLET
            has("iphone", "android", "pixel", "galaxy", "oneplus", "xiaomi", "redmi", "huawei", "oppo", "vivo", "motorola", "phone") ||
                62078 in ports -> DeviceType.PHONE
            has("espressif", "esp32", "esp8266", "esp-", "tasmota", "shelly", "tuya", "sonoff", "philips hue", "hue bridge", "nest",
                "ecobee", "smartthings", "homeassistant", "home-assistant", "home assistant", "wled", "broadlink", "meross",
                "kasa", "roborock", "roomba", "dyson", "yeelight", "lifx", "wemo") ||
                svc("_hap", "_homekit", "_matter", "_esphomelib", "_mqtt", "_miio", "_hue", "_home-assistant") || 1883 in ports -> DeviceType.IOT
            has("macbook", "imac", "mac-mini", "mac mini", "macmini", "desktop", "laptop", "windows", "ubuntu", "raspberrypi",
                "raspberry", "workstation", "thinkpad", "-pc", "pc-") ||
                svc("_workstation", "_smb", "_ssh", "_sftp-ssh", "_rfb", "_afpovertcp") ||
                445 in ports || 139 in ports || 3389 in ports || 22 in ports -> DeviceType.COMPUTER
            else -> DeviceType.UNKNOWN
        }
    }
}
