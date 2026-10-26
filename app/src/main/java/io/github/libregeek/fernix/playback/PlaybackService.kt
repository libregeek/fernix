// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.C
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.libregeek.fernix.FernixApp
import io.github.libregeek.fernix.MainActivity

/**
 * Hosts the single ExoPlayer and its media session (spec/architecture.md §8), independent of the
 * UI lifecycle. The UI talks to it through a `MediaController`. Media3 runs the foreground
 * notification, so playback survives the app being swiped away while it is playing.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var equalizer: EqualizerEngine? = null
    private var savedQueue: SavedQueue? = null

    // Player.setAudioSessionId and the session callback are marked unstable by Media3.
    @OptIn(markerClass = [UnstableApi::class])
    override fun onCreate() {
        super.onCreate()
        val player = PlayerFactory.create(this)
        // A fixed session id (instead of the one ExoPlayer picks) keeps the equalizer attached.
        val audioSessionId = getSystemService(AudioManager::class.java).generateAudioSessionId()
        player.audioSessionId = audioSessionId
        val container = (application as FernixApp).container
        PlayRecorder(container::recordPlay).attach(player)
        SkipOnError(player, container::recordPlayFailure).attach()
        LockScreenCredits(player, container.database.trackDao()).attach()
        savedQueue = SavedQueue(player, container.database.queueDao(), container.settings).also { it.start() }
        val engine = EqualizerEngine(audioSessionId, container.settings)
        equalizer = engine
        session = MediaSession.Builder(this, player)
            .setCallback(EqualizerCallback(engine))
            // Tapping the notification (or the lock-screen card) opens the app.
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Stop when the user swipes the app away and nothing is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            savedQueue?.release()
            savedQueue = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        savedQueue?.release()
        savedQueue = null
        equalizer?.release()
        equalizer = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
