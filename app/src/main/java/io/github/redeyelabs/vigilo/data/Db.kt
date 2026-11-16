package io.github.redeyelabs.vigilo.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Categorie di evento nello storico. */
object EventType {
    const val SENTRY_ALERT = "SENTRY_ALERT"     // evento sospetto con Sentinella attiva
    const val UNLOCKED = "UNLOCKED"
    const val WINDOW = "WINDOW"
    const val CHARGE = "CHARGE"
    const val LOW_BATTERY = "LOW_BATTERY"
    const val SCHEDULE = "SCHEDULE"             // azione programmata eseguita
    const val COMMAND = "COMMAND"               // comando manuale/automatico
    const val SENTRY_CHANGE = "SENTRY_CHANGE"   // Sentinella accesa/spenta
    const val ERROR = "ERROR"
    const val SENTRY_AWARE = "SENTRY_AWARE"     // da Home Assistant: qualcuno vicino all'auto
    const val SENTRY_PANIC = "SENTRY_PANIC"     // da Home Assistant: allarme
    const val TELEMETRY = "TELEMETRY"           // da Home Assistant: auto connessa/disconnessa
    const val SETUP = "SETUP"                   // da Home Assistant: configurazione

    /** Eventi da evidenziare in rosso e contare come "avvisi Sentinella". */
    fun isAlert(type: String) = type == SENTRY_ALERT || type == SENTRY_AWARE || type == SENTRY_PANIC
}

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String,
    val title: String,
    val message: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * Regola settimanale della Sentinella.
 * daysMask: bit0 = lunedì ... bit6 = domenica.
 */
@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val daysMask: Int,
    val hour: Int,
    val minute: Int,
    val enableSentry: Boolean,
    val active: Boolean = true,
)

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT 500")
    fun observeAll(): Flow<List<EventEntity>>

    @Insert suspend fun insert(e: EventEntity): Long

    @Query("DELETE FROM events") suspend fun clear()

    @Query("DELETE FROM events WHERE timestamp < :before") suspend fun deleteOlderThan(before: Long)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY hour, minute")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules") suspend fun all(): List<ScheduleEntity>
    @Query("SELECT * FROM schedules WHERE id = :id") suspend fun byId(id: Long): ScheduleEntity?
    @Insert suspend fun insert(s: ScheduleEntity): Long
    @Update suspend fun update(s: ScheduleEntity)
    @Delete suspend fun delete(s: ScheduleEntity)
}

@Database(entities = [EventEntity::class, ScheduleEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun schedules(): ScheduleDao

    companion object {
        @Volatile private var db: AppDatabase? = null
        fun get(context: Context): AppDatabase = db ?: synchronized(this) {
            db ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "vigilo.db")
                .build().also { db = it }
        }
    }
}
