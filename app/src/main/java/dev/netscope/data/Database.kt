package dev.netscope.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "devices", primaryKeys = ["networkId", "deviceKey"])
data class DeviceEntity(
    val networkId: String,
    val deviceKey: String,
    val ip: String,
    val mac: String?,
    val hostname: String?,
    val vendor: String?,
    val type: String,
    val customName: String?,
    val notes: String?,
    val isKnown: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
    val openPorts: String?,
    val lastPortScan: Long?,
)

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: String,
    val timestamp: Long,
    val deviceCount: Int,
    val newCount: Int,
    val durationMs: Long,
)

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices WHERE networkId = :networkId")
    suspend fun list(networkId: String): List<DeviceEntity>

    @Query("SELECT * FROM devices")
    suspend fun all(): List<DeviceEntity>

    @Upsert
    suspend fun upsert(devices: List<DeviceEntity>)

    @Query("UPDATE devices SET customName = :name WHERE networkId = :networkId AND deviceKey = :key")
    suspend fun rename(networkId: String, key: String, name: String?)

    @Query("UPDATE devices SET isKnown = :known WHERE networkId = :networkId AND deviceKey = :key")
    suspend fun setKnown(networkId: String, key: String, known: Boolean)

    @Query("UPDATE devices SET notes = :notes WHERE networkId = :networkId AND deviceKey = :key")
    suspend fun setNotes(networkId: String, key: String, notes: String?)

    @Query("UPDATE devices SET openPorts = :ports, lastPortScan = :time WHERE networkId = :networkId AND deviceKey = :key")
    suspend fun setPorts(networkId: String, key: String, ports: String?, time: Long)

    @Query("DELETE FROM devices WHERE networkId = :networkId AND deviceKey = :key")
    suspend fun delete(networkId: String, key: String)

    @Query("DELETE FROM devices")
    suspend fun clear()
}

@Dao
interface ScanDao {
    @Insert
    suspend fun insert(scan: ScanEntity)

    @Query("SELECT * FROM scans ORDER BY timestamp DESC LIMIT 30")
    fun recent(): Flow<List<ScanEntity>>

    @Query("DELETE FROM scans")
    suspend fun clear()
}

@Database(entities = [DeviceEntity::class, ScanEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao
    abstract fun scans(): ScanDao
}
