package com.swufe.homework.di

import com.swufe.homework.data.local.TokenSource
import com.swufe.homework.data.local.TokenStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BindModule {
    @Binds abstract fun tokenSource(store: TokenStore): TokenSource
}
