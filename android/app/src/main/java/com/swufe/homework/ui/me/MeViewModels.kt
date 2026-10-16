package com.swufe.homework.ui.me

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swufe.homework.data.api.Invites
import com.swufe.homework.data.api.MePatch
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.AccountRepository
import com.swufe.homework.data.repo.AuthRepository
import com.swufe.homework.data.repo.UpdateInfo
import com.swufe.homework.data.repo.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MeViewModel @Inject constructor(
    private val account: AccountRepository,
    private val auth: AuthRepository,
    private val updates: UpdateRepository,
) : ViewModel() {
    val me = account.me
    var error by mutableStateOf<String?>(null); private set
    var update by mutableStateOf<UpdateInfo?>(null); private set
    var updateMsg by mutableStateOf<String?>(null); private set
    var checking by mutableStateOf(false); private set

    init { refresh() }

    fun refresh() { viewModelScope.launch { account.refreshMe().onFailure { error = it.userMessage } } }

    fun checkUpdate() {
        checking = true; updateMsg = null
        viewModelScope.launch {
            updates.check(manual = true)
                .onSuccess { update = it; if (it == null) updateMsg = "已是最新版本" }
                .onFailure { updateMsg = "检查更新失败，请稍后再试" }
            checking = false
        }
    }

    fun dismissUpdate() { update = null }
    fun skipUpdate() { update?.let { v -> viewModelScope.launch { updates.skip(v.version) } }; update = null }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch { auth.logout(); account.clear(); onDone() }
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(private val account: AccountRepository) : ViewModel() {
    val me = account.me
    var nickname by mutableStateOf(account.me.value?.nickname ?: "")
    var saving by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var saved by mutableStateOf(false); private set

    init {
        viewModelScope.launch { account.refreshMe().onSuccess { if (nickname.isEmpty()) nickname = it.nickname } }
    }

    fun saveNickname() {
        val n = nickname.trim()
        if (n.isEmpty() || n.length > 20) { error = "昵称 1–20 个字"; return }
        saving = true; error = null; saved = false
        viewModelScope.launch {
            account.update(MePatch(nickname = n)).onSuccess { saved = true }.onFailure { error = it.userMessage }
            saving = false
        }
    }

    fun setAnonymous(v: Boolean) {
        error = null
        viewModelScope.launch { account.update(MePatch(leaderboardAnonymous = v)).onFailure { error = it.userMessage } }
    }
}

@HiltViewModel
class InvitesViewModel @Inject constructor(private val account: AccountRepository) : ViewModel() {
    var invites by mutableStateOf<Invites?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            account.invites().onSuccess { invites = it }.onFailure { error = it.userMessage }
        }
    }

    fun create() {
        loading = true; error = null
        viewModelScope.launch {
            account.createInvite().onSuccess { load(); account.refreshMe() }.onFailure { error = it.userMessage }
            loading = false
        }
    }
}
