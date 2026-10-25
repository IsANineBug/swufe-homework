package com.swufe.homework.ui.homework

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.homeworkPrefs by preferencesDataStore(name = "homework_prefs")

/**
 * WS11 的本地偏好（非敏感），单独一个 DataStore 文件，不改 WS4/WS10 的 SettingsStore。
 * - 首页上次选择的切换（今天/明天/本周/全部）；
 * - 我提议过 / 赞同过的合并提议 id：v2 起以服务器的 MergeProposal.voted_by_me 为准；服务器还没带这个字段时
 *   用本机记录把「赞同」按钮禁用（issue #8：提议者对自己的提议点赞同会得到 409 already_voted）。
 */
@Singleton
class HomeworkPrefs @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val kHomeRange = stringPreferencesKey("home_range")
    private val kVotedMerges = stringSetPreferencesKey("voted_merge_proposals")

    suspend fun homeRange(): HomeRange = HomeRange.fromKey(ctx.homeworkPrefs.data.first()[kHomeRange])
    suspend fun setHomeRange(r: HomeRange) { ctx.homeworkPrefs.edit { it[kHomeRange] = r.key } }

    /** 本机提议过或赞同过的合并提议（提议人算第 1 票，不能再赞同）。 */
    val votedMerges: Flow<Set<Int>> = ctx.homeworkPrefs.data.map { p ->
        p[kVotedMerges].orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
    }

    suspend fun markMergeVoted(id: Int) {
        ctx.homeworkPrefs.edit { p ->
            // 只留 id 最大的 200 条，避免无限增长
            val cur = p[kVotedMerges].orEmpty().mapNotNull { it.toIntOrNull() }
            p[kVotedMerges] = (cur + id).distinct().sortedDescending().take(200).map { it.toString() }.toSet()
        }
    }
}
