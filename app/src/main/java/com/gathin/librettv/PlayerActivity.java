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

    private ExoPlayer player;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        final String url = getIntent().getStringExtra("url");
        final String referer = getIntent().getStringExtra("referer");
        final TextView error = findViewById(R.id.error);
        PlayerView view = findViewById(R.id.player);

        player = new ExoPlayer.Builder(this).build();
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                error.setVisibility(View.VISIBLE);
                error.setText(friendlyError(e));
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

    private void start(DefaultHttpDataSource.Factory http, String mediaUrl) {
        MediaSource mediaSource = new DefaultMediaSourceFactory(http)
                .createMediaSource(MediaItem.fromUri(mediaUrl));
        player.setMediaSource(mediaSource);
        player.prepare();
        player.play();
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
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (player != null) {
            player.stop();
        }
        super.onBackPressed();
    }
}
