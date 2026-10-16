package com.swufe.homework.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.swufe.homework.data.api.ClassInfo
import com.swufe.homework.data.api.Period
import com.swufe.homework.data.api.Slot
import kotlinx.coroutines.flow.Flow

// 本地缓存：断网时课表照常显示。WS5 的作业缓存请在这里加表并升 version。

@Entity(tableName = "my_class", primaryKeys = ["id"])
data class ClassEntity(
    val id: Int,
    val name: String,
    val teacher: String?,
    val memberCount: Int,
)

@Entity(tableName = "my_class_slot", primaryKeys = ["classId", "weekday", "start"])
data class SlotEntity(
    val classId: Int,
    val room: String,
    val weekday: Int,
    val start: Int,
    val end: Int,
)

@Entity(tableName = "period", primaryKeys = ["no"])
data class PeriodEntity(val no: Int, val start: String, val end: String)

@Dao
interface TimetableDao {
    @Query("SELECT * FROM my_class ORDER BY name")
    fun classes(): Flow<List<ClassEntity>>

    @Query("SELECT * FROM my_class_slot")
    fun slots(): Flow<List<SlotEntity>>

    @Query("SELECT * FROM my_class WHERE id = :id")
    suspend fun classById(id: Int): ClassEntity?

    @Query("SELECT * FROM my_class_slot WHERE classId = :id ORDER BY weekday, start")
    suspend fun slotsOf(id: Int): List<SlotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClasses(c: List<ClassEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlots(s: List<SlotEntity>)

    @Query("DELETE FROM my_class")
    suspend fun clearClasses()

    @Query("DELETE FROM my_class_slot")
    suspend fun clearSlots()

    @Query("DELETE FROM my_class WHERE id = :id")
    suspend fun deleteClass(id: Int)

    @Query("DELETE FROM my_class_slot WHERE classId = :id")
    suspend fun deleteSlotsOf(id: Int)

    @Transaction
    suspend fun replaceAll(list: List<ClassInfo>) {
        clearSlots(); clearClasses()
        insertClasses(list.map { ClassEntity(it.id, it.name, it.teacher, it.memberCount) })
        insertSlots(list.flatMap { c -> c.slots.map { SlotEntity(c.id, it.room, it.weekday, it.start, it.end) } })
    }

    @Transaction
    suspend fun removeClass(id: Int) { deleteSlotsOf(id); deleteClass(id) }

    @Query("SELECT * FROM period ORDER BY no")
    fun periods(): Flow<List<PeriodEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeriods(p: List<PeriodEntity>)
}

@Database(entities = [ClassEntity::class, SlotEntity::class, PeriodEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timetableDao(): TimetableDao
}

fun SlotEntity.toSlot() = Slot(room, weekday, start, end)
fun PeriodEntity.toPeriod() = Period(no, start, end)
