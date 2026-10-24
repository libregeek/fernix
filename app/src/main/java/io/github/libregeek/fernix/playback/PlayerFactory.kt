// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/**
 * The one place the player is built (spec/architecture.md §8), so the service and the audio-path test use
 * the same configuration. **Sound quality is never traded for anything**: no custom audio processors, no
 * playback speed or pitch change, no silence skipping, no volume normalisation, no resampling or
 * downmixing; the decoders and the audio sink run with ExoPlayer's defaults. Add nothing here that
 * changes the signal without the maintainer's approval, and re-run `AudioPathTest` after any change.
 */
object PlayerFactory {

    fun create(context: Context): ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        // CPU wake lock only; no Wi-Fi lock since nothing is streamed.
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
}
