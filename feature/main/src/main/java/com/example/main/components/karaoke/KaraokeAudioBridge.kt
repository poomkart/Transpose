package com.example.main.components.karaoke

import android.content.Context
import com.example.domain.repository.VideoRepository
import com.example.media.manager.AudioEffectsManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface KaraokeDependenciesEntryPoint {
    fun audioEffectsManager(): AudioEffectsManager
    fun videoRepository(): VideoRepository
}

private fun karaokeDependencies(context: Context): KaraokeDependenciesEntryPoint =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        KaraokeDependenciesEntryPoint::class.java,
    )

internal fun karaokeAudioEffectsManager(context: Context): AudioEffectsManager =
    karaokeDependencies(context).audioEffectsManager()

internal fun karaokeVideoRepository(context: Context): VideoRepository =
    karaokeDependencies(context).videoRepository()
