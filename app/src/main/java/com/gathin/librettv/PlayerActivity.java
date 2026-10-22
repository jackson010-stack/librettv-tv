package com.gathin.librettv;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

import java.util.Collections;

/** Full-screen playback through Media3, so no WebView is involved. */
public class PlayerActivity extends AppCompatActivity {

    private static final long SAVE_EVERY_MS = 5000;

    private ExoPlayer player;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** The title this playback belongs to, used to file the position under. */
    private String historyKey = "";
    private String sourceKey = "";
    private String sourceName = "";
    private String vodId = "";
    private String title = "";
    private String pic = "";
    private String year = "";
    private String type = "";
    private String remarks = "";
    private String playUrl = "";
    private String playFrom = "";
    private String episodeName = "";
    /** The address the episode was started from, so it can be played again later. */
    private String episodeUrl = "";
    private String referer = "";

    private long resumePosition;
    private long knownDuration;
    private boolean seeked;
    private boolean recorded;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        final String url = getIntent().getStringExtra("url");
        final String referer = getIntent().getStringExtra("referer");
        this.episodeUrl = url == null ? "" : url;
        this.referer = referer == null ? "" : referer;
        final TextView error = findViewById(R.id.error);
        final TextView overlay = findViewById(R.id.overlay);
        PlayerView view = findViewById(R.id.player);

        historyKey = extra("historyKey");
        sourceKey = extra("sourceKey");
        sourceName = extra("sourceName");
        vodId = extra("vodId");
        title = extra("title");
        pic = extra("pic");
        year = extra("year");
        type = extra("type");
        remarks = extra("remarks");
        playUrl = extra("playUrl");
        playFrom = extra("playFrom");
        episodeName = extra("episodeName");
        resumePosition = getIntent().getLongExtra("resumePosition", 0L);
        knownDuration = getIntent().getLongExtra("durationMs", 0L);

        player = new ExoPlayer.Builder(this).build();
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                error.setVisibility(View.VISIBLE);
                error.setText(friendlyError(e));
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    if (!seeked && resumePosition > 0) {
                        seeked = true;
                        player.seekTo(resumePosition);
                        showOverlay(overlay, "从 " + History.formatMs(resumePosition) + " 继续播放");
                    }
                    scheduleSave();
                } else if (state == Player.STATE_ENDED) {
                    saveProgress(true);
                }
            }
        });

        if (url == null || url.isEmpty()) {
            error.setVisibility(View.VISIBLE);
            error.setText("没有播放地址");
            return;
        }

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Api.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000);
        if (referer != null && !referer.isEmpty()) {
            http.setDefaultRequestProperties(Collections.singletonMap("Referer", referer));
        }

        if (Api.looksLikeMedia(url)) {
            start(http, url);
            return;
        }
        // A "/share/xxxx" page: follow it to the real playlist before handing it to the player.
        error.setVisibility(View.VISIBLE);
        error.setText("正在解析播放地址…");
        new Thread(() -> {
            final String media = Api.resolveMediaUrl(url, referer);
            main.post(() -> {
                if (media == null) {
                    error.setText("该线路无法播放，换一条线路试试");
                    return;
                }
                error.setVisibility(View.GONE);
                start(http, media);
            });
        }).start();
    }

    private String extra(String key) {
        String v = getIntent().getStringExtra(key);
        return v == null ? "" : v;
    }

    /** Shows a short hint about what is playing and disappears again. */
    private void showOverlay(TextView overlay, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        overlay.setText(text);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(200).start();
        main.postDelayed(() -> overlay.animate().alpha(0f).setDuration(600).withEndAction(
                () -> overlay.setVisibility(View.GONE)).start(), 3200);
    }

    private void start(DefaultHttpDataSource.Factory http, String mediaUrl) {
        MediaSource mediaSource = new DefaultMediaSourceFactory(http)
                .createMediaSource(MediaItem.fromUri(mediaUrl));
        player.setMediaSource(mediaSource);
        player.prepare();
        player.play();
    }

    private void scheduleSave() {
        main.removeCallbacks(saveTask);
        main.postDelayed(saveTask, SAVE_EVERY_MS);
    }

    private final Runnable saveTask = new Runnable() {
        @Override
        public void run() {
            saveProgress(false);
            if (player != null && player.isPlaying()) {
                scheduleSave();
            }
        }
    };

    /**
     * Files how far the title was watched. {@code ended} means the last episode ran to its end,
     * so the title is offered as a fresh start instead of a resume.
     */
    private void saveProgress(boolean ended) {
        if (player == null || historyKey.isEmpty() || recorded && ended) {
            return;
        }
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (dur <= 0) {
            dur = knownDuration;
        }
        if (ended) {
            pos = 0;
        }
        if (!ended && !History.worthKeeping(pos)) {
            // Barely started: there is nothing to come back to.
            History.remove(this, historyKey);
            recorded = true;
            return;
        }
        History.Entry e = new History.Entry();
        e.key = historyKey;
        e.sourceKey = sourceKey;
        e.sourceName = sourceName;
        e.id = vodId;
        e.name = title;
        e.pic = pic;
        e.year = year;
        e.type = type;
        e.remarks = remarks;
        e.playUrl = playUrl;
        e.playFrom = playFrom;
        e.episodeName = episodeName;
        e.episodeUrl = episodeUrl;
        e.referer = referer;
        e.positionMs = pos;
        e.durationMs = dur;
        e.finished = ended;
        History.record(this, e);
        if (ended) {
            recorded = true;
        }
    }

    private String friendlyError(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "播放失败：网络连接不上";
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return "播放失败：服务器拒绝了访问（换一条线路试试）";
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
                return "播放失败：播放地址已失效（换一条线路试试）";
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
                return "播放失败：电视不支持该视频格式";
            default:
                return "播放失败：" + (e.getErrorCodeName() == null ? e.getMessage() : e.getErrorCodeName());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            player.pause();
        }
        saveProgress(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(saveTask);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    public void onBackPressed() {
        saveProgress(false);
        if (player != null) {
            player.stop();
        }
        super.onBackPressed();
    }
}
