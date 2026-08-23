package com.example.main.components.karaoke

import android.content.Context
import com.example.media.manager.AudioEffectsManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface KaraokeAudioEntryPoint {
    fun audioEffectsManager(): AudioEffectsManager
}

internal fun karaokeAudioEffectsManager(context: Context): AudioEffectsManager =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        KaraokeAudioEntryPoint::class.java,
    ).audioEffectsManager()
