package com.example.webedge

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.mozilla.geckoview.GeckoSession
import java.io.ByteArrayOutputStream
import org.mozilla.geckoview.MediaSession as GeckoMediaSession

// Media của trang trong GeckoView -> MediaSession của Android qua Media3 (cùng cách làm với bản WebView):
// thông báo điều khiển, màn hình khóa, tai nghe/Bluetooth và nút điều khiển trong cửa sổ PiP.
//
// Luồng dữ liệu: GeckoSession MediaSession.Delegate -> GeckoMediaController -> WebMediaPlayer -> WebMediaService.
// Lệnh đi ngược lại: MediaSession (Media3) -> WebMediaPlayer -> WebMediaHub.commandRunner -> GeckoView MediaSession.

/** Trạng thái media đang phát, do GeckoView báo lên. */
data class WebMediaInfo(
    val active: Boolean = false,
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val artwork: ByteArray? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val rate: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0
) {
    val seekable: Boolean get() = durationMs > 0L
}

/** Cầu nối dùng chung giữa Activity và Service (cùng một process). */
object WebMediaHub {
    /** Lệnh từ MediaSession: "play", "pause", "stop" hoặc "seek" (giây). Activity gán khi đang chạy. */
    @Volatile
    var commandRunner: ((command: String, seconds: Double) -> Unit)? = null

    val player: WebMediaPlayer by lazy { WebMediaPlayer() }
}

/** Player "ảo": không tự phát gì, chỉ phản chiếu media của trang và chuyển lệnh về GeckoView. */
class WebMediaPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var info = WebMediaInfo()

    fun update(newInfo: WebMediaInfo) {
        info = newInfo
        invalidateState()
    }

    fun clear() = update(WebMediaInfo())

    override fun getState(): State {
        if (!info.active) {
            return State.Builder()
                .setAvailableCommands(Player.Commands.EMPTY)
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(info.title)
            .setArtist(info.artist)
            .apply {
                info.artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            }
            .build()
        val mediaItem = MediaItem.Builder().setMediaId(MEDIA_ID).setMediaMetadata(metadata).build()
        val item = MediaItemData.Builder(MEDIA_ID)
            .setMediaItem(mediaItem)
            .setMediaMetadata(metadata)
            .setDurationUs(if (info.seekable) info.durationMs * 1000L else C.TIME_UNSET)
            .setIsSeekable(info.seekable)
            .setIsDynamic(!info.seekable)
            .build()

        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE
            )
            .apply {
                if (info.seekable) {
                    addAll(
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_BACK,
                        Player.COMMAND_SEEK_FORWARD
                    )
                }
            }
            .build()

        val position = if (info.playing) {
            PositionSupplier.getExtrapolating(info.positionMs, info.rate)
        } else {
            PositionSupplier.getConstant(info.positionMs)
        }
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(info.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY)
            .setPlaybackParameters(PlaybackParameters(info.rate))
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(position)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        WebMediaHub.commandRunner?.invoke(if (playWhenReady) "play" else "pause", 0.0)
        info = info.copy(playing = playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = positionMs.coerceAtLeast(0L)
        WebMediaHub.commandRunner?.invoke("seek", target / 1000.0)
        info = info.copy(positionMs = target)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        WebMediaHub.commandRunner?.invoke("stop", 0.0)
        info = WebMediaInfo()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val MEDIA_ID = "summit_web_media"
    }
}

/** Service Media3: giữ MediaSession và tự hiện thông báo media, chạy foreground khi đang phát. */
class WebMediaService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent().setClassName(this, "com.example.summitgecko.GeckoPrototypeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, WebMediaHub.player)
            .setId("summit_gecko_web")
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Người dùng vuốt app khỏi danh sách gần đây: nếu không còn phát thì dừng service.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}

/**
 * Phía Activity: nhận trạng thái media từ GeckoView, nối với service và chuyển lệnh điều khiển về trang.
 * Chỉ theo dõi một media "hiện hành" (tab vừa bắt đầu phát); tab khác phát sau sẽ thay thế nó.
 */
internal class GeckoMediaController(
    private val activity: Activity,
    private val requestNotificationPermission: () -> Unit,
    private val onVideoInfo: (width: Int, height: Int, title: String, subtitle: String) -> Unit
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var currentSession: GeckoSession? = null
    private var currentMedia: GeckoMediaSession? = null
    private var info = WebMediaInfo()

    init {
        WebMediaHub.commandRunner = { command, seconds ->
            activity.runOnUiThread {
                val media = currentMedia
                if (media != null) {
                    when (command) {
                        "play" -> media.play()
                        "pause" -> media.pause()
                        "stop" -> media.stop()
                        "seek" -> media.seekTo(seconds, false)
                    }
                }
            }
        }
    }

    val delegate = object : GeckoMediaSession.Delegate {
        override fun onActivated(session: GeckoSession, mediaSession: GeckoMediaSession) {
            currentSession = session
            currentMedia = mediaSession
            info = WebMediaInfo(active = true, playing = false)
            publish(connectNow = true)
        }

        override fun onDeactivated(session: GeckoSession, mediaSession: GeckoMediaSession) {
            if (currentMedia !== mediaSession) return
            reset()
        }

        override fun onMetadata(
            session: GeckoSession,
            mediaSession: GeckoMediaSession,
            meta: GeckoMediaSession.Metadata
        ) {
            if (currentMedia !== mediaSession) return
            info = info.copy(title = meta.title.orEmpty(), artist = meta.artist.orEmpty())
            publish()
            // Ảnh bìa tải bất đồng bộ; bỏ qua nếu lỗi hoặc media đã đổi.
            runCatching {
                meta.artwork?.getBitmap(ARTWORK_SIZE)?.accept({ bitmap ->
                    if (bitmap != null && currentMedia === mediaSession) {
                        val bytes = ByteArrayOutputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            out.toByteArray()
                        }
                        activity.runOnUiThread {
                            if (currentMedia === mediaSession) {
                                info = info.copy(artwork = bytes)
                                publish()
                            }
                        }
                    }
                }, { })
            }
        }

        override fun onPositionState(
            session: GeckoSession,
            mediaSession: GeckoMediaSession,
            state: GeckoMediaSession.PositionState
        ) {
            if (currentMedia !== mediaSession) return
            val duration = if (state.duration.isFinite() && state.duration > 0.0) state.duration else 0.0
            info = info.copy(
                positionMs = (state.position * 1000.0).toLong().coerceAtLeast(0L),
                durationMs = (duration * 1000.0).toLong(),
                rate = state.playbackRate.toFloat().takeIf { it > 0f } ?: 1f
            )
            publish()
        }

        override fun onPlay(session: GeckoSession, mediaSession: GeckoMediaSession) {
            if (currentMedia !== mediaSession) return
            info = info.copy(playing = true)
            publish(connectNow = true)
        }

        override fun onPause(session: GeckoSession, mediaSession: GeckoMediaSession) {
            if (currentMedia !== mediaSession) return
            info = info.copy(playing = false)
            publish()
        }

        override fun onStop(session: GeckoSession, mediaSession: GeckoMediaSession) {
            if (currentMedia !== mediaSession) return
            reset()
        }

        override fun onFullscreen(
            session: GeckoSession,
            mediaSession: GeckoMediaSession,
            enabled: Boolean,
            meta: GeckoMediaSession.ElementMetadata?
        ) {
            if (currentMedia !== mediaSession || !enabled || meta == null) return
            info = info.copy(videoWidth = meta.width.toInt(), videoHeight = meta.height.toInt())
            onVideoInfo(info.videoWidth, info.videoHeight, info.title, info.artist)
        }
    }

    /** Tab bị đóng: nếu đang theo dõi media của nó thì bỏ. */
    fun forget(session: GeckoSession) {
        if (currentSession === session) reset()
    }

    fun release() {
        WebMediaHub.commandRunner = null
        WebMediaHub.player.clear()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        currentSession = null
        currentMedia = null
    }

    private fun reset() {
        currentSession = null
        currentMedia = null
        info = WebMediaInfo()
        WebMediaHub.player.clear()
    }

    private fun publish(connectNow: Boolean = false) {
        if (connectNow) {
            connect()
            requestNotificationPermission()
        }
        WebMediaHub.player.update(info)
        if (info.active && info.videoWidth > 0) onVideoInfo(info.videoWidth, info.videoHeight, info.title, info.artist)
    }

    // Kết nối một MediaController để hệ thống tạo và gắn service vào app.
    private fun connect() {
        if (controllerFuture != null) return
        runCatching {
            val token = SessionToken(activity, ComponentName(activity, WebMediaService::class.java))
            controllerFuture = MediaController.Builder(activity, token).buildAsync()
        }.onFailure { Log.w("SummitGeckoMedia", "Không kết nối được MediaSession", it) }
    }

    private companion object {
        const val ARTWORK_SIZE = 256
    }
}
