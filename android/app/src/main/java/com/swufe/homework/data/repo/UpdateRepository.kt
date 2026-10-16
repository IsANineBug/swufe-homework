package com.swufe.homework.data.repo

import com.swufe.homework.BuildConfig
import com.swufe.homework.data.api.GitHubApi
import com.swufe.homework.data.local.SettingsStore
import com.swufe.homework.util.Version
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(val version: String, val notes: String, val downloadUrl: String)

/**
 * 启动时查 GitHub Releases latest。匿名请求每 IP 每小时 60 次，所以自动检查最多 6 小时一次，
 * 设置页的「检查更新」不受限。
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val gh: GitHubApi,
    private val settings: SettingsStore,
) {
    suspend fun check(manual: Boolean): Result<UpdateInfo?> = runCatching {
        val now = System.currentTimeMillis()
        if (!manual && now - settings.lastUpdateCheck() < 6 * 3600_000L) return@runCatching null
        settings.setLastUpdateCheck(now)
        val (owner, repo) = BuildConfig.GITHUB_REPO.split('/')
        val r = gh.latestRelease(owner, repo)
        if (!Version.isNewer(r.tagName, BuildConfig.VERSION_NAME)) return@runCatching null
        if (!manual && settings.skippedVersion() == r.tagName) return@runCatching null
        val apk = r.assets.firstOrNull { it.name.endsWith(".apk") }?.downloadUrl ?: r.htmlUrl
        UpdateInfo(r.tagName, r.body.orEmpty().take(800), apk)
    }

    suspend fun skip(version: String) = settings.setSkippedVersion(version)
}
