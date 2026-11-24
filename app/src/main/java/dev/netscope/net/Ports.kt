package dev.netscope.net

enum class Risk(val label: String) { INFO("Info"), LOW("Low"), MEDIUM("Medium"), HIGH("High"), CRITICAL("Critical") }

object Ports {
    val services: Map<Int, String> = mapOf(
        20 to "FTP data", 21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS",
        80 to "HTTP", 81 to "HTTP alt", 88 to "Kerberos", 110 to "POP3", 111 to "RPC bind", 135 to "MS RPC",
        139 to "NetBIOS", 143 to "IMAP", 389 to "LDAP", 443 to "HTTPS", 445 to "SMB", 465 to "SMTPS",
        515 to "LPD printer", 548 to "AFP", 554 to "RTSP", 587 to "SMTP submit", 631 to "IPP printer",
        636 to "LDAPS", 873 to "rsync", 993 to "IMAPS", 995 to "POP3S", 1080 to "SOCKS proxy",
        1194 to "OpenVPN", 1433 to "MS SQL", 1521 to "Oracle DB", 1723 to "PPTP VPN", 1883 to "MQTT",
        1900 to "UPnP", 2049 to "NFS", 2375 to "Docker API", 2376 to "Docker TLS", 3000 to "Web app",
        3128 to "HTTP proxy", 3306 to "MySQL", 3389 to "Remote Desktop", 3689 to "iTunes DAAP",
        5000 to "UPnP / web", 5001 to "Synology HTTPS", 5060 to "SIP", 5222 to "XMPP", 5432 to "PostgreSQL",
        5555 to "Android ADB", 5900 to "VNC", 5985 to "WinRM", 6379 to "Redis", 6443 to "Kubernetes API",
        6668 to "IoT (Tuya)", 7000 to "AirPlay", 7547 to "TR-069", 8000 to "HTTP alt", 8008 to "Chromecast",
        8009 to "Chromecast", 8080 to "HTTP proxy", 8081 to "HTTP alt", 8086 to "InfluxDB",
        8123 to "Home Assistant", 8443 to "HTTPS alt", 8554 to "RTSP alt", 8883 to "MQTT TLS",
        8888 to "HTTP alt", 9000 to "Web / Portainer", 9090 to "Web console", 9100 to "JetDirect printer",
        9200 to "Elasticsearch", 9443 to "HTTPS alt", 10000 to "Webmin", 11211 to "Memcached",
        27017 to "MongoDB", 32400 to "Plex", 49152 to "UPnP", 62078 to "iPhone sync",
    )

    val quick: List<Int> = services.keys.sorted()

    val httpPorts = setOf(80, 81, 3000, 5000, 7000, 8000, 8008, 8080, 8081, 8086, 8123, 8888, 9000, 9090, 10000, 32400, 49152)
    val tlsPorts = setOf(443, 465, 636, 993, 995, 5001, 8443, 8883, 9443, 6443)

    fun name(port: Int): String = services[port] ?: "Unknown"

    fun risk(port: Int): Pair<Risk, String>? = when (port) {
        23 -> Risk.CRITICAL to "Telnet sends passwords in plain text. Disable it."
        2375 -> Risk.CRITICAL to "Unauthenticated Docker API allows full takeover of the host."
        5555 -> Risk.CRITICAL to "Android Debug Bridge is exposed: anyone on the network can get a shell."
        21 -> Risk.HIGH to "FTP is unencrypted. Prefer SFTP."
        3389 -> Risk.HIGH to "Remote Desktop is a common brute-force target."
        5900 -> Risk.HIGH to "VNC often has weak or no password."
        6379 -> Risk.HIGH to "Redis often runs without authentication."
        27017 -> Risk.HIGH to "MongoDB may allow unauthenticated access."
        9200 -> Risk.HIGH to "Elasticsearch may expose data without auth."
        11211 -> Risk.HIGH to "Memcached can leak data and be abused for amplification."
        445, 139 -> Risk.MEDIUM to "File sharing: keep patched and disable guest access."
        1883 -> Risk.MEDIUM to "MQTT without TLS. Make sure authentication is on."
        554, 8554 -> Risk.MEDIUM to "Camera stream. Make sure it is password protected."
        7547 -> Risk.MEDIUM to "ISP remote management (TR-069) is exposed."
        3306, 5432, 1433, 1521 -> Risk.MEDIUM to "Database is reachable from the network."
        1900, 49152 -> Risk.LOW to "UPnP can let apps open ports on your router."
        80, 8080, 8000, 8081, 8888 -> Risk.LOW to "Unencrypted web interface."
        22 -> Risk.INFO to "SSH: use key authentication and strong passwords."
        else -> null
    }
}

enum class PortProfile(val label: String) {
    QUICK("Quick"), COMMON("1–1024"), FULL("All 65535"), CUSTOM("Custom");

    fun ports(from: Int = 1, to: Int = 1024): List<Int> = when (this) {
        QUICK -> Ports.quick
        COMMON -> ((1..1024) + Ports.quick).distinct().sorted()
        FULL -> (1..65535).toList()
        CUSTOM -> (from.coerceIn(1, 65535)..to.coerceIn(1, 65535)).toList()
    }
}
