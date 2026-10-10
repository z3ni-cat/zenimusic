package com.zenimusic.app.player

import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Background playback service. Hosts the ExoPlayer + MediaSession so Android shows
 * a media notification with transport controls while the app is in background.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_PREV = "com.zenimusic.app.ACTION_PREV"
        const val ACTION_NEXT = "com.zenimusic.app.ACTION_NEXT"
    }

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
        PlayerManager.attachPlayer(player)

        val callback = object : MediaSession.Callback {
            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    ACTION_PREV -> PlayerManager.previous()
                    ACTION_NEXT -> PlayerManager.next()
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

        session = MediaSession.Builder(this, player)
            .setCallback(callback)
            .build()
            .also { s ->
                s.setCustomLayout(
                    listOf(
                        CommandButton.Builder()
                            .setIconResId(CommandButton.ICON_PREVIOUS)
                            .setDisplayName("Previous")
                            .setSessionCommand(SessionCommand(ACTION_PREV, Bundle.EMPTY))
                            .setEnabled(true)
                            .build(),
                        CommandButton.Builder()
                            .setIconResId(CommandButton.ICON_NEXT)
                            .setDisplayName("Next")
                            .setSessionCommand(SessionCommand(ACTION_NEXT, Bundle.EMPTY))
                            .setEnabled(true)
                            .build()
                    )
                )
                addSession(s)
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
        PlayerManager.releasePlayer()
        super.onDestroy()
    }
}
