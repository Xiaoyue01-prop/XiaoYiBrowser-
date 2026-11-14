package com.example.webedge

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

// Media của trang web (video/audio trong WebView) -> MediaSession của Android qua Media3:
// thông báo điều khiển, màn hình khóa, tai nghe/Bluetooth và nút điều khiển trong cửa sổ PiP.
//
// Luồng dữ liệu: MEDIA_JS (trong trang) -> EdgeBridge.mediaState -> WebMediaController -> WebMediaPlayer
// -> WebMediaService (MediaSession). Lệnh đi ngược lại: MediaSession -> WebMediaPlayer -> evaluateJavascript.

/** Trạng thái media của tab đang xem, do trang báo lên. */
data class WebMediaInfo(
    val active: Boolean = false,
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val rate: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0
) {
    val seekable: Boolean get() = durationMs > 0L
    val aspect: Float get() = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else 0f
}

/** Cầu nối dùng chung giữa Activity và Service (cùng một process). */
object WebMediaHub {
    /** Chạy một đoạn JS trong tab đang xem; Activity gán khi đang chạy. */
    @Volatile
    var jsRunner: ((String) -> Unit)? = null

    val player: WebMediaPlayer by lazy { WebMediaPlayer() }
}

/** Player "ảo": không tự phát gì, chỉ phản chiếu thẻ video/audio của trang và chuyển lệnh về trang. */
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
                info.artworkUrl.takeIf { it.startsWith("http") }?.let { setArtworkUri(Uri.parse(it)) }
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
        run(if (playWhenReady) "play()" else "pause()")
        info = info.copy(playing = playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = positionMs.coerceAtLeast(0L)
        run("seek(${target / 1000.0})")
        info = info.copy(positionMs = target)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        run("pause()")
        info = WebMediaInfo()
        return Futures.immediateVoidFuture()
    }

    private fun run(call: String) {
        WebMediaHub.jsRunner?.invoke(mediaJs(call))
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
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, WebMediaHub.player)
            .setId("summit_web")
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

/** Phía Activity: nhận trạng thái từ trang, nối với service và xin quyền thông báo khi cần. */
class WebMediaController(private val activity: ComponentActivity) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var notificationAsked = false

    private val notificationPermission = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    /** Trả về thông tin mới nhất để nơi khác (PiP) dùng. */
    fun onPageState(info: WebMediaInfo) {
        if (info.active) {
            connect()
            askNotificationPermission()
        }
        WebMediaHub.player.update(info)
    }

    /** Đổi tab hoặc tải trang mới: bỏ trạng thái cũ, trang mới sẽ tự báo lại. */
    fun reset() = WebMediaHub.player.clear()

    fun release() {
        WebMediaHub.jsRunner = null
        WebMediaHub.player.clear()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    // Kết nối một MediaController để hệ thống tạo và gắn service vào app.
    private fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(activity, ComponentName(activity, WebMediaService::class.java))
        controllerFuture = MediaController.Builder(activity, token).buildAsync()
    }

    private fun askNotificationPermission() {
        if (notificationAsked) return
        notificationAsked = true
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * Tạo mã JS gọi lệnh của __summitMedia ở MỌI khung. evaluateJavascript chỉ chạy ở khung chính, mà trình phát của nhiều web phim
 * nằm trong iframe; broadcast chuyển lệnh xuống các khung con bằng postMessage. [call] dạng "pause()" hoặc "seek(12.5)".
 */
internal fun mediaJs(call: String): String {
    val name = call.substringBefore('(')
    val args = call.substringAfter('(', "").removeSuffix(")")
    return "window.__summitMedia&&window.__summitMedia.broadcast('$name',[$args])"
}

// Script chạy trong trang: tìm thẻ video/audio đang phát, báo trạng thái về EdgeBridge.mediaState
// và nhận lệnh play/pause/seek qua window.__summitMedia. Chỉ chạy một lần cho mỗi tài liệu.
internal const val MEDIA_JS = """
(function(){
if (window.__summitMedia) return;
var cur = null, lastSent = 0, pipEl = null;

// WebView không hỗ trợ Picture-in-Picture của web (nút PiP trong trình phát bấm không có tác dụng).
// Giả lập: chuyển video sang toàn màn hình rồi nhờ app vào PiP của Android.
try {
  Object.defineProperty(document, 'pictureInPictureEnabled', { get: function(){ return true; }, configurable: true });
  Object.defineProperty(HTMLVideoElement.prototype, 'disablePictureInPicture',
    { get: function(){ return false; }, set: function(){}, configurable: true });
  HTMLVideoElement.prototype.requestPictureInPicture = function(){
    var v = this;
    cur = v; watch();
    // Đợi app xác nhận đã đặt cờ PiP rồi mới xin toàn màn hình: nếu không, app có thể xoay ngang trước khi biết là đang xin PiP.
    // (Quyền kích hoạt do người dùng chạm kéo dài vài giây nên xin trễ một chút vẫn được.)
    return new Promise(function(resolve, reject){
      function goFs(){
        var p;
        try { p = v.requestFullscreen ? v.requestFullscreen() : (v.webkitRequestFullscreen && v.webkitRequestFullscreen()); } catch (e) {}
        Promise.resolve(p).then(function(){ resolve({ width: v.videoWidth, height: v.videoHeight }); }, reject);
      }
      // Đường WebMessageListener nhận callback xác nhận; đường addJavascriptInterface cũ gọi đồng bộ nên không cần đợi.
      try {
        if (window.__summitNative) EdgeBridge.requestPip(goFs);
        else { EdgeBridge.requestPip(); goFs(); }
      } catch (e) { goFs(); }
    });
  };
  document.exitPictureInPicture = function(){ return Promise.resolve(); };
  // Trang cần biết video đang ở PiP để đổi giao diện: pictureInPictureElement + sự kiện enter/leavepictureinpicture
  // (app gọi __summitMedia.pip(true/false) khi cửa sổ vào/ra PiP).
  Object.defineProperty(document, 'pictureInPictureElement', { get: function(){ return pipEl; }, configurable: true });
} catch (e) {}

function info(){
  var md = null;
  try { md = navigator.mediaSession && navigator.mediaSession.metadata; } catch (e) {}
  var art = '';
  try {
    if (md && md.artwork && md.artwork.length) {
      art = new URL(md.artwork[md.artwork.length - 1].src, location.href).href;
    }
  } catch (e) {}
  return {
    title: (md && md.title) || document.title || location.hostname,
    artist: (md && md.artist) || location.hostname,
    art: art
  };
}

function send(force){
  var now = Date.now();
  if (!force && now - lastSent < 1000) return;
  lastSent = now;
  var m = cur;
  if (!m || !m.isConnected) { EdgeBridge.mediaState(0, false, '', '', '', 0, 0, 1, 0, 0); return; }
  var i = info();
  var dur = isFinite(m.duration) ? m.duration * 1000 : 0;
  EdgeBridge.mediaState(1, !m.paused && !m.ended, i.title, i.artist, i.art,
    Math.round(m.currentTime * 1000), Math.round(dur), m.playbackRate || 1,
    m.videoWidth || 0, m.videoHeight || 0);
}

function onEvent(e){
  var t = e.target;
  if (!(t instanceof HTMLMediaElement)) return;
  if (e.type === 'play' || e.type === 'playing') { cur = t; watch(); }
  if (t !== cur) return;
  send(e.type !== 'timeupdate');
}

['play', 'playing', 'pause', 'ended', 'seeked', 'ratechange', 'durationchange', 'loadedmetadata', 'emptied']
  .forEach(function(n){ document.addEventListener(n, onEvent, true); });
document.addEventListener('timeupdate', function(e){ if (e.target === cur) send(false); }, true);
window.addEventListener('pagehide', function(){ cur = null; send(true); });
var watchTimer = 0;
function watch(){
  if (cur && !watchTimer) watchTimer = setInterval(function(){
    if (cur && !cur.isConnected) { cur = null; send(true); }
    if (!cur) { clearInterval(watchTimer); watchTimer = 0; }
  }, 3000);
}

window.__summitMedia = {
  play: function(){ if (cur) { var p = cur.play(); if (p && p.catch) p.catch(function(){}); } },
  pause: function(){ if (cur) cur.pause(); },
  seek: function(sec){ if (cur) cur.currentTime = sec; },
  pip: function(on){
    try {
      var v = on ? cur : pipEl;
      if (!v) return;
      // Video đang toàn màn hình thì không báo "đã vào PiP" cho trang: nhiều trình phát coi đó là tín hiệu để tự thoát
      // toàn màn hình (log: fullscreen hide ~250ms sau khi vào PiP), khiến cửa sổ PiP chỉ còn giao diện trình duyệt.
      if (on && (document.fullscreenElement || document.webkitFullscreenElement)) return;
      pipEl = on ? v : null;
      var ev = new Event(on ? 'enterpictureinpicture' : 'leavepictureinpicture', { bubbles: true });
      ev.pictureInPictureWindow = { width: v.clientWidth, height: v.clientHeight,
        addEventListener: function(){}, removeEventListener: function(){} };
      v.dispatchEvent(ev);
    } catch (e) {}
  },
  // Buộc trình duyệt vẽ lại lớp video (sau khi thu từ PiP về toàn màn hình mà video đen): báo resize và ép tạo lại lớp.
  nudge: function(seek){
    try {
      window.dispatchEvent(new Event('resize'));
      var v = cur;
      if (!v) return;
    } catch (e) {}
  },
  // Dùng quanh web.onPause()/onResume(): nhớ video đang phát để phát tiếp nếu WebView tạm dừng nó khi ẩn/hiện lại.
  holdPlaying: function(){ try { window.__summitWasPlaying = !!(cur && !cur.paused && !cur.ended); } catch (e) {} },
  restorePlaying: function(){
    try { if (window.__summitWasPlaying && cur && cur.paused) { var p = cur.play(); if (p && p.catch) p.catch(function(){}); } } catch (e) {}
  },
  report: function(){
    if (!cur) {
      var list = document.querySelectorAll('video,audio');
      for (var k = 0; k < list.length; k++) { if (!list[k].paused) { cur = list[k]; break; } }
    }
    watch();
    send(true);
  }
};
// Chuyển lệnh từ app xuống mọi khung con (iframe chứa trình phát). Chỉ nhận tin từ khung cha, chỉ các lệnh trong danh sách.
var ALLOWED = { play: 1, pause: 1, seek: 1, pip: 1, nudge: 1, holdPlaying: 1, restorePlaying: 1 };
function relay(msg){
  try { var fr = window.frames; for (var i = 0; i < fr.length; i++) { try { fr[i].postMessage(msg, '*'); } catch (e) {} } } catch (e) {}
}
window.__summitMedia.broadcast = function(name, args){
  if (!ALLOWED[name]) return;
  try { window.__summitMedia[name].apply(window.__summitMedia, args || []); } catch (e) {}
  relay({ __summitCmd: name, args: args || [] });
};
window.addEventListener('message', function(e){
  var d = e.data;
  if (window === window.top || e.source !== window.parent) return;
  if (!d || typeof d !== 'object' || typeof d.__summitCmd !== 'string') return;
  window.__summitMedia.broadcast(d.__summitCmd, d.args);
});
window.__summitMedia.report();
})();
"""
