// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Lets controllers of the session read and change the equalizer, which only the service can reach. */
@OptIn(markerClass = [UnstableApi::class])
class EqualizerCallback(private val engine: EqualizerEngine) : MediaSession.Callback {

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            .apply { EqualizerCommands.ALL.forEach { add(SessionCommand(it, Bundle.EMPTY)) } }
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            EqualizerCommands.GET -> Unit
            EqualizerCommands.SET_ENABLED -> engine.setEnabled(args.getBoolean(EqualizerCommands.ARG_ENABLED))
            EqualizerCommands.SET_BAND ->
                engine.setBandLevel(args.getInt(EqualizerCommands.ARG_BAND), args.getInt(EqualizerCommands.ARG_LEVEL))
            EqualizerCommands.USE_PRESET -> engine.usePreset(args.getInt(EqualizerCommands.ARG_PRESET))
            EqualizerCommands.RESET -> engine.reset()
            else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        val state = engine.state()
            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, state.toBundle()))
    }
}
