package dev.netscope.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** MAC vendor lookup, backed by a list downloaded on demand (Wireshark manuf, IEEE fallback). */
class OuiDatabase(context: Context) {
    private val file = File(context.filesDir, "oui.tsv")
    private val lock = Any()
    @Volatile private var map: Map<String, String>? = null

    val isAvailable: Boolean get() = file.exists() && file.length() > 0
    val lastUpdated: Long? get() = file.takeIf { it.exists() }?.lastModified()
    val entryCount: Int get() = if (isAvailable) load().size else 0

    fun lookup(mac: String?): String? {
        val hex = mac?.uppercase()?.filter { it.isDigit() || it in 'A'..'F' } ?: return null
        if (hex.length < 6) return null
        if (isRandomized(hex)) return PRIVATE
        return load()[hex.substring(0, 6)]
    }

    private fun load(): Map<String, String> {
        map?.let { return it }
        synchronized(lock) {
            map?.let { return it }
            if (!isAvailable) return emptyMap()
            val m = runCatching {
                HashMap<String, String>(50000).also { hm ->
                    file.forEachLine { line ->
                        val tab = line.indexOf('\t')
                        if (tab == 6) hm[line.substring(0, 6)] = line.substring(7)
                    }
                }
            }.getOrDefault(HashMap())
            if (m.isNotEmpty()) map = m
            return m
        }
    }

    suspend fun download(): Int = withContext(Dispatchers.IO) {
        val entries = runCatching { parseWireshark(Http.get(WIRESHARK_URL, 30000)) }.getOrNull()
            ?.takeIf { it.size > 1000 }
            ?: parseIeee(Http.get(IEEE_URL, 30000))
        if (entries.size < 1000) throw IOException("Vendor list looks incomplete")
        val tmp = File(file.parentFile, "oui.tmp")
        tmp.bufferedWriter().use { w ->
            entries.forEach { (k, v) -> w.write(k); w.write("\t"); w.write(v); w.write("\n") }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        synchronized(lock) { map = entries }
        entries.size
    }

    private fun parseWireshark(text: String): Map<String, String> {
        val m = HashMap<String, String>(50000)
        text.lineSequence().forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val parts = line.split('\t')
            if (parts.size < 2) return@forEach
            val prefix = parts[0].trim()
            if (prefix.contains('/')) return@forEach
            val hex = prefix.replace(":", "").replace("-", "").uppercase()
            if (hex.length != 6) return@forEach
            val name = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() } ?: parts[1].trim()
            m[hex] = name
        }
        return m
    }

    private fun parseIeee(text: String): Map<String, String> {
        val m = HashMap<String, String>(50000)
        text.lineSequence().drop(1).forEach { line ->
            val first = line.indexOf(',')
            if (first < 0) return@forEach
            val second = line.indexOf(',', first + 1)
            if (second < 0) return@forEach
            val hex = line.substring(first + 1, second).uppercase()
            if (hex.length != 6) return@forEach
            val rest = line.substring(second + 1)
            val name = if (rest.startsWith("\"")) rest.substring(1).substringBefore("\"") else rest.substringBefore(",")
            m[hex] = name.trim()
        }
        return m
    }

    companion object {
        const val PRIVATE = "Private address"
        private const val WIRESHARK_URL = "https://www.wireshark.org/download/automated/data/manuf"
        private const val IEEE_URL = "https://standards-oui.ieee.org/oui/oui.csv"

        /** Locally-administered bit set => randomized / private MAC. */
        fun isRandomized(mac: String): Boolean {
            val hex = mac.filter { it.isLetterOrDigit() }
            return hex.length >= 2 && hex[1].uppercaseChar() in "26AE"
        }
    }
}
