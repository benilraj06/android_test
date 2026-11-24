package dev.netscope.net

import java.io.ByteArrayOutputStream

sealed interface DnsRecord {
    val name: String
    data class A(override val name: String, val address: String) : DnsRecord
    data class Ptr(override val name: String, val target: String) : DnsRecord
    data class Srv(override val name: String, val target: String, val port: Int) : DnsRecord
    data class Txt(override val name: String, val entries: List<String>) : DnsRecord
    data class Other(override val name: String, val type: Int) : DnsRecord
}

/** Minimal DNS wire-format encoder/decoder, enough for mDNS discovery. */
object DnsPacket {
    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_SRV = 33

    fun buildQuery(id: Int, questions: List<Pair<String, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write((v shr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        u16(id); u16(0); u16(questions.size); u16(0); u16(0); u16(0)
        for ((name, type) in questions) {
            name.trimEnd('.').split('.').forEach { label ->
                val b = label.toByteArray(Charsets.UTF_8)
                out.write(b.size)
                out.write(b)
            }
            out.write(0)
            u16(type)
            u16(1)
        }
        return out.toByteArray()
    }

    fun parse(data: ByteArray, length: Int): List<DnsRecord> {
        val r = Reader(data, length)
        r.pos = 4
        val qd = r.u16()
        val an = r.u16()
        val ns = r.u16()
        val ar = r.u16()
        repeat(qd) {
            r.name()
            r.pos += 4
        }
        val out = ArrayList<DnsRecord>()
        repeat(an + ns + ar) {
            val name = r.name()
            val type = r.u16()
            r.u16() // class
            r.pos += 4 // ttl
            val rdlen = r.u16()
            val start = r.pos
            if (start + rdlen > length) return out
            val rec: DnsRecord = when (type) {
                TYPE_A -> if (rdlen == 4) {
                    DnsRecord.A(
                        name,
                        "${data[start].toInt() and 0xFF}.${data[start + 1].toInt() and 0xFF}." +
                            "${data[start + 2].toInt() and 0xFF}.${data[start + 3].toInt() and 0xFF}",
                    )
                } else DnsRecord.Other(name, type)
                TYPE_PTR -> DnsRecord.Ptr(name, r.name())
                TYPE_SRV -> {
                    r.pos += 4
                    val port = r.u16()
                    DnsRecord.Srv(name, r.name(), port)
                }
                TYPE_TXT -> {
                    val list = ArrayList<String>()
                    var p = start
                    while (p < start + rdlen) {
                        val l = data[p].toInt() and 0xFF
                        p++
                        if (p + l > start + rdlen) break
                        if (l > 0) list += String(data, p, l, Charsets.UTF_8)
                        p += l
                    }
                    DnsRecord.Txt(name, list)
                }
                else -> DnsRecord.Other(name, type)
            }
            out += rec
            r.pos = start + rdlen
        }
        return out
    }

    private class Reader(val data: ByteArray, val length: Int) {
        var pos = 0

        fun u8(): Int {
            if (pos >= length) throw IndexOutOfBoundsException()
            return data[pos++].toInt() and 0xFF
        }

        fun u16(): Int {
            val hi = u8()
            val lo = u8()
            return (hi shl 8) or lo
        }

        fun name(): String {
            val labels = ArrayList<String>()
            var p = pos
            var jumped = false
            var jumps = 0
            while (true) {
                if (p >= length) throw IndexOutOfBoundsException()
                val len = data[p].toInt() and 0xFF
                if (len == 0) {
                    p++
                    break
                } else if ((len and 0xC0) == 0xC0) {
                    if (p + 1 >= length) throw IndexOutOfBoundsException()
                    val ptr = ((len and 0x3F) shl 8) or (data[p + 1].toInt() and 0xFF)
                    if (!jumped) pos = p + 2
                    jumped = true
                    p = ptr
                    if (++jumps > 32) throw IllegalStateException("DNS pointer loop")
                } else {
                    p++
                    if (p + len > length) throw IndexOutOfBoundsException()
                    labels += String(data, p, len, Charsets.UTF_8)
                    p += len
                }
            }
            if (!jumped) pos = p
            return labels.joinToString(".")
        }
    }
}
