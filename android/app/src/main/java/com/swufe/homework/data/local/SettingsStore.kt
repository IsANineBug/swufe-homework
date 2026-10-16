package com.swufe.homework.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** 本地设置（非敏感）。WS5 的提醒相关设置也可放这里。 */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val kSkippedVersion = stringPreferencesKey("skipped_update_version")
    private val kLastUpdateCheck = longPreferencesKey("last_update_check_ms")

    suspend fun skippedVersion(): String? = ctx.settingsStore.data.first()[kSkippedVersion]
    suspend fun setSkippedVersion(v: String) = ctx.settingsStore.edit { it[kSkippedVersion] = v }

    suspend fun lastUpdateCheck(): Long = ctx.settingsStore.data.first()[kLastUpdateCheck] ?: 0L
    suspend fun setLastUpdateCheck(ms: Long) = ctx.settingsStore.edit { it[kLastUpdateCheck] = ms }
}
