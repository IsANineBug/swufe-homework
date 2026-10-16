package com.swufe.homework.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

const val MIN_PASSWORD = 8

@HiltViewModel
class LoginViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    var nickname by mutableStateOf("")
    var password by mutableStateOf("")
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    fun submit(onDone: () -> Unit) {
        if (nickname.isBlank() || password.isEmpty()) { error = "请填写昵称和密码"; return }
        loading = true; error = null
        viewModelScope.launch {
            auth.login(nickname, password).onSuccess { onDone() }.onFailure { error = it.userMessage }
            loading = false
        }
    }
}

@HiltViewModel
class RegisterViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    var nickname by mutableStateOf("")
    var password by mutableStateOf("")
    var password2 by mutableStateOf("")
    var inviteCode by mutableStateOf("")

    /** null = 检测中；true = 校园网，无需邀请码；false = 需要邀请码。 */
    var campus by mutableStateOf<Boolean?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    init { checkCampus() }

    fun checkCampus() {
        campus = null
        viewModelScope.launch {
            // 检测失败时按「需要邀请码」处理，用户仍可注册
            campus = auth.campusCheck().getOrDefault(false)
        }
    }

    fun validate(): String? = when {
        nickname.isBlank() -> "请填写昵称"
        nickname.trim().length > 20 -> "昵称最多 20 个字"
        AuthRepository.isReservedNickname(nickname) -> "这个昵称不能使用，请换一个"
        password.length < MIN_PASSWORD -> "密码至少 $MIN_PASSWORD 位"
        password.length > 64 -> "密码最多 64 位"
        password != password2 -> "两次输入的密码不一致"
        campus != true && inviteCode.isBlank() -> "当前不在校园网，需要填写邀请码"
        else -> null
    }

    fun submit(onDone: (recoveryCode: String) -> Unit) {
        validate()?.let { error = it; return }
        loading = true; error = null
        viewModelScope.launch {
            auth.register(nickname, password, inviteCode.takeIf { it.isNotBlank() })
                .onSuccess(onDone)
                .onFailure { error = it.userMessage }
            loading = false
        }
    }
}

/** 恢复码页：注册流程下确认后才把 token 落盘。 */
@HiltViewModel
class RecoveryCodeViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    /** onDone(true) 进首页；onDone(false) 说明进程被回收过，需重新登录。 */
    fun confirm(fromRecover: Boolean, onDone: (Boolean) -> Unit) {
        if (fromRecover) { onDone(false); return }
        viewModelScope.launch { onDone(auth.confirmRegistration()) }
    }
}

@HiltViewModel
class RecoverViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    var nickname by mutableStateOf("")
    var code by mutableStateOf("")
    var password by mutableStateOf("")
    var password2 by mutableStateOf("")
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    fun submit(onDone: (newCode: String) -> Unit) {
        error = when {
            nickname.isBlank() || code.isBlank() -> "请填写昵称和恢复码"
            password.length < MIN_PASSWORD -> "新密码至少 $MIN_PASSWORD 位"
            password != password2 -> "两次输入的密码不一致"
            else -> null
        }
        if (error != null) return
        loading = true
        viewModelScope.launch {
            auth.recover(nickname, code, password).onSuccess(onDone).onFailure { error = it.userMessage }
            loading = false
        }
    }
}
