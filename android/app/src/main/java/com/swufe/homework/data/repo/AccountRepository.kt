package com.swufe.homework.data.repo

import com.swufe.homework.data.api.Invites
import com.swufe.homework.data.api.Me
import com.swufe.homework.data.api.MePatch
import com.swufe.homework.data.api.SwufeApi
import com.swufe.homework.data.api.apiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(private val api: SwufeApi) {
    private val _me = MutableStateFlow<Me?>(null)
    val me: StateFlow<Me?> = _me

    suspend fun refreshMe(): Result<Me> = apiCall { api.me() }.onSuccess { _me.value = it }

    suspend fun update(patch: MePatch): Result<Me> = apiCall { api.patchMe(patch) }.onSuccess { _me.value = it }

    suspend fun invites(): Result<Invites> = apiCall { api.invites() }

    suspend fun createInvite(): Result<String> = apiCall { api.createInvite().code }

    fun clear() { _me.value = null }
}
