package com.swufe.homework.reminder

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.reminderStore by preferencesDataStore(name = "reminder")

/** /sync 游标与首次引导状态。退出登录时清空游标，下个账号从头全量同步。 */
@Singleton
class ReminderPrefs @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val kCursor = stringPreferencesKey("sync_cursor")
    private val kGuideDone = booleanPreferencesKey("permission_guide_done")

    suspend fun cursor(): String? = ctx.reminderStore.data.first()[kCursor]
    suspend fun setCursor(v: String) = ctx.reminderStore.edit { it[kCursor] = v }
    suspend fun clearCursor() = ctx.reminderStore.edit { it.remove(kCursor) }

    val guideDone: Flow<Boolean> = ctx.reminderStore.data.map { it[kGuideDone] == true }
    suspend fun setGuideDone() = ctx.reminderStore.edit { it[kGuideDone] = true }
}
