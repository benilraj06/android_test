package dev.netscope.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.netscope.net.NetDevice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Exporter {
    fun share(context: Context, devices: List<NetDevice>, format: String, ssid: String?) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val file = File(dir, "netscope-devices-$stamp.$format")
        file.writeText(if (format == "csv") toCsv(devices) else toJson(devices, ssid))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (format == "csv") "text/csv" else "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "NetScope device list")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export devices").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun esc(v: String?): String {
        val s = v ?: ""
        return if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun toCsv(devices: List<NetDevice>): String = buildString {
        appendLine("name,ip,mac,vendor,type,hostname,online,trusted,open_ports,first_seen,last_seen")
        devices.forEach { d ->
            appendLine(
                listOf(
                    d.displayName, d.ip, d.mac, d.manufacturer, d.type.label, d.bestName,
                    d.online.toString(), d.isKnown.toString(), d.openPorts.joinToString(" "),
                    d.firstSeen.toString(), d.lastSeen.toString(),
                ).joinToString(",") { esc(it) },
            )
        }
    }

    private fun toJson(devices: List<NetDevice>, ssid: String?): String {
        val arr = JSONArray()
        devices.forEach { d ->
            arr.put(
                JSONObject()
                    .put("name", d.displayName)
                    .put("ip", d.ip)
                    .put("mac", d.mac ?: JSONObject.NULL)
                    .put("vendor", d.manufacturer ?: JSONObject.NULL)
                    .put("type", d.type.label)
                    .put("hostname", d.bestName ?: JSONObject.NULL)
                    .put("services", JSONArray(d.services.toList()))
                    .put("online", d.online)
                    .put("trusted", d.isKnown)
                    .put("openPorts", JSONArray(d.openPorts))
                    .put("firstSeen", d.firstSeen)
                    .put("lastSeen", d.lastSeen),
            )
        }
        return JSONObject()
            .put("network", ssid ?: JSONObject.NULL)
            .put("exportedAt", System.currentTimeMillis())
            .put("devices", arr)
            .toString(2)
    }
}
