package com.swufe.homework.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.swufe.homework.data.api.ClassInfo
import com.swufe.homework.data.api.Period
import com.swufe.homework.data.api.Slot
import kotlinx.coroutines.flow.Flow

// 本地缓存：断网时课表照常显示；WS5 的作业缓存（提醒用）在 v2 加入。

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

/** WS5：已同步的作业（本地提醒的依据）。只存提醒用得到的字段，不存正文和图片。 */
@Entity(tableName = "assignment", primaryKeys = ["id"])
data class AssignmentEntity(
    val id: Int,
    val classId: Int,
    val className: String,
    val title: String,
    val ddl: String,
    val ddlUncertain: Boolean,
    val ddlConflict: Boolean,
    val done: Boolean,
    val sourceCount: Int,
    /** reminder_text 原样 JSON，可空。 */
    val reminderText: String?,
    val updatedAt: String?,
)

@Dao
interface AssignmentDao {
    @Query("SELECT * FROM assignment")
    suspend fun all(): List<AssignmentEntity>

    @Query("SELECT * FROM assignment WHERE id = :id")
    suspend fun byId(id: Int): AssignmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(list: List<AssignmentEntity>)

    @Query("DELETE FROM assignment WHERE id IN (:ids)")
    suspend fun delete(ids: List<Int>)

    @Query("UPDATE assignment SET done = :done WHERE id = :id")
    suspend fun setDone(id: Int, done: Boolean)

    @Query("DELETE FROM assignment WHERE classId = :classId")
    suspend fun deleteClass(classId: Int)

    @Query("DELETE FROM assignment")
    suspend fun clear()
}

@Database(
    entities = [ClassEntity::class, SlotEntity::class, PeriodEntity::class, AssignmentEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timetableDao(): TimetableDao
    abstract fun assignmentDao(): AssignmentDao

    companion object {
        /** v1 → v2：WS5 新增作业缓存表。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `assignment` (`id` INTEGER NOT NULL, `classId` INTEGER NOT NULL, " +
                        "`className` TEXT NOT NULL, `title` TEXT NOT NULL, `ddl` TEXT NOT NULL, `ddlUncertain` INTEGER NOT NULL, " +
                        "`ddlConflict` INTEGER NOT NULL, `done` INTEGER NOT NULL, `sourceCount` INTEGER NOT NULL, " +
                        "`reminderText` TEXT, `updatedAt` TEXT, PRIMARY KEY(`id`))",
                )
            }
        }
    }
}

fun SlotEntity.toSlot() = Slot(room, weekday, start, end)
fun PeriodEntity.toPeriod() = Period(no, start, end)
