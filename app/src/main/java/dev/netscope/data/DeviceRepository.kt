package dev.netscope.data

import dev.netscope.net.DeviceType
import dev.netscope.net.NetDevice

class DeviceRepository(db: AppDatabase) {
    private val devices = db.devices()
    private val scans = db.scans()

    suspend fun list(networkId: String) = devices.list(networkId)
    suspend fun all() = devices.all()
    suspend fun save(networkId: String, list: List<NetDevice>) = devices.upsert(list.map { it.toEntity(networkId) })
    suspend fun rename(networkId: String, key: String, name: String?) = devices.rename(networkId, key, name)
    suspend fun setKnown(networkId: String, key: String, known: Boolean) = devices.setKnown(networkId, key, known)
    suspend fun setNotes(networkId: String, key: String, notes: String?) = devices.setNotes(networkId, key, notes)
    suspend fun setPorts(networkId: String, key: String, ports: List<Int>, time: Long) =
        devices.setPorts(networkId, key, ports.joinToString(",").ifEmpty { null }, time)
    suspend fun delete(networkId: String, key: String) = devices.delete(networkId, key)
    suspend fun recordScan(scan: ScanEntity) = scans.insert(scan)
    fun recentScans() = scans.recent()
    suspend fun clearAll() {
        devices.clear()
        scans.clear()
    }
}

fun String?.toPortList(): List<Int> = this?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()

fun NetDevice.toEntity(networkId: String) = DeviceEntity(
    networkId = networkId,
    deviceKey = key,
    ip = ip,
    mac = mac,
    hostname = bestName ?: hostname,
    vendor = vendor,
    type = type.name,
    customName = customName,
    notes = notes,
    isKnown = isKnown,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    openPorts = openPorts.joinToString(",").ifEmpty { null },
    lastPortScan = lastPortScan,
)

fun DeviceEntity.toNetDevice(online: Boolean) = NetDevice(
    ip = ip,
    key = deviceKey,
    mac = mac,
    hostname = hostname,
    vendor = vendor,
    type = runCatching { DeviceType.valueOf(type) }.getOrDefault(DeviceType.UNKNOWN),
    isGateway = deviceKey == "gateway",
    isSelf = deviceKey == "self",
    online = online,
    customName = customName,
    notes = notes,
    isKnown = isKnown,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    openPorts = openPorts.toPortList(),
    lastPortScan = lastPortScan,
)
