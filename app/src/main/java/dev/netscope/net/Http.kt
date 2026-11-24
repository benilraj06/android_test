package dev.netscope.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    const val USER_AGENT = "NetScope/1.0 (Android)"

    fun get(url: String, timeoutMs: Int = 8000, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        for ((k, v) in headers) c.setRequestProperty(k, v)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
