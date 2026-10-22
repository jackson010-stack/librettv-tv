package com.gathin.librettv;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Title, metadata and the episode list of one film. */
public class DetailActivity extends AppCompatActivity {

    private String name = "";
    private String pic = "";
    private String year = "";
    private String area = "";
    private String type = "";
    private String remarks = "";
    private String content = "";
    private String playUrl = "";
    private String playFrom = "";
    private String sourceKey = "";
    private String sourceName = "";
    private Sources.Source source;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        Bundle b = getIntent().getExtras();
        name = str(b, "name");
        pic = str(b, "pic");
        year = str(b, "year");
        area = str(b, "area");
        type = str(b, "type");
        remarks = str(b, "remarks");
        content = str(b, "content");
        playUrl = str(b, "playUrl");
        playFrom = str(b, "playFrom");
        sourceKey = str(b, "sourceKey");
        sourceName = str(b, "sourceName");

        ((TextView) findViewById(R.id.title)).setText(name);

        StringBuilder meta = new StringBuilder();
        if (!year.isEmpty()) {
            meta.append(year);
        }
        if (!area.isEmpty()) {
            meta.append(meta.length() > 0 ? " · " : "").append(area);
        }
        if (!type.isEmpty()) {
            meta.append(meta.length() > 0 ? " · " : "").append(type);
        }
        if (!remarks.isEmpty()) {
            meta.append(meta.length() > 0 ? " · " : "").append(remarks);
        }
        ((TextView) findViewById(R.id.meta)).setText(meta.toString());

        String sourceLine = "来源：" + sourceName;
        ((TextView) findViewById(R.id.source)).setText(sourceLine);
        ((TextView) findViewById(R.id.content)).setText(stripHtml(content));

        Sources.Source src = Sources.byKey(sourceKey);
        source = src;
        ImageLoader.load((ImageView) findViewById(R.id.poster), pic, src == null ? null : src.origin() + "/");

        History.Entry resume = History.find(this, sourceKey, str(b, "id"));
        showResume(resume);

        final List<Api.Line> lines = Api.parseLines(playUrl, playFrom);
        TextView label = findViewById(R.id.episodes_label);
        RecyclerView list = findViewById(R.id.episodes);
        if (lines.isEmpty()) {
            label.setText("没有可播放的剧集");
            return;
        }
        list.setLayoutManager(new GridLayoutManager(this, 6));

        View lineRow = findViewById(R.id.lines_scroll);
        LinearLayout lineBox = findViewById(R.id.lines);
        if (lines.size() > 1) {
            lineRow.setVisibility(View.VISIBLE);
            for (int i = 0; i < lines.size(); i++) {
                final int index = i;
                Button lineBtn = new Button(this);
                lineBtn.setText(lines.get(i).label);
                lineBtn.setTextSize(13f);
                lineBtn.setAllCaps(false);
                lineBtn.setFocusable(true);
                lineBtn.setOnClickListener(v -> showLine(lines, index, label, list, lineBox, resume));
                lineBox.addView(lineBtn, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        showLine(lines, 0, label, list, lineBox, resume);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the player, the position of this title may have changed.
        showResume(History.find(this, sourceKey, str(getIntent().getExtras(), "id")));
    }

    /** Offers to pick the title up where it was left. */
    private void showResume(History.Entry entry) {
        Button btn = findViewById(R.id.resume_btn);
        if (entry == null) {
            btn.setVisibility(View.GONE);
            return;
        }
        btn.setVisibility(View.VISIBLE);
        StringBuilder sb = new StringBuilder("▶ 继续播放");
        if (!entry.episodeName.isEmpty()) {
            sb.append(" · ").append(entry.episodeName);
        }
        if (entry.finished) {
            sb.append(" · 已看完");
        } else {
            sb.append(" · ").append(History.formatMs(entry.positionMs));
            if (entry.durationMs > 0) {
                sb.append("/").append(History.formatMs(entry.durationMs));
            }
        }
        btn.setText(sb.toString());
        btn.setOnClickListener(v -> {
            Intent i = playIntent(entry.episodeUrl, entry.episodeName);
            if (!entry.referer.isEmpty()) {
                i.putExtra("referer", entry.referer);
            }
            i.putExtra("resumePosition", entry.finished ? 0L : entry.positionMs);
            i.putExtra("durationMs", entry.durationMs);
            startActivity(i);
        });
    }

    /** Builds the intent that plays one episode and files the position under this title. */
    private Intent playIntent(String url, String episodeName) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", url);
        i.putExtra("referer", source == null ? "" : source.origin() + "/");
        i.putExtra("title", name);
        i.putExtra("episodeName", episodeName);
        i.putExtra("pic", pic);
        i.putExtra("year", year);
        i.putExtra("type", type);
        i.putExtra("remarks", remarks);
        i.putExtra("playUrl", playUrl);
        i.putExtra("playFrom", playFrom);
        i.putExtra("sourceKey", sourceKey);
        i.putExtra("sourceName", sourceName);
        i.putExtra("vodId", getIntent().getStringExtra("id"));
        i.putExtra("historyKey", History.keyOf(sourceKey, str(getIntent().getExtras(), "id")));
        return i;
    }

    /** Shows the episodes of one play line and marks the active line button. */
    private void showLine(List<Api.Line> lines, int index, TextView label, RecyclerView list,
                          LinearLayout lineBox, History.Entry resume) {
        Api.Line line = lines.get(index);
        label.setText("剧集（" + line.episodes.size() + "）");
        list.setAdapter(new EpisodeAdapter(this, line.episodes, resume));
        for (int i = 0; i < lineBox.getChildCount(); i++) {
            Button b = (Button) lineBox.getChildAt(i);
            b.setSelected(i == index);
            b.setTextColor(getColor(i == index ? R.color.accent : R.color.text_dim));
        }
    }

    private static String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("<br\\s*/?>", "\n")
                .replaceAll("</p>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .trim();
    }

    private static String str(Bundle b, String key) {
        if (b == null) {
            return "";
        }
        String v = b.getString(key);
        return v == null ? "" : v;
    }

    static class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.VH> {
        private final DetailActivity host;
        private final List<String[]> episodes;
        private final History.Entry resume;

        EpisodeAdapter(DetailActivity host, List<String[]> episodes, History.Entry resume) {
            this.host = host;
            this.episodes = episodes;
            this.resume = resume;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_episode, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            String[] ep = episodes.get(position);
            holder.name.setText(ep[0]);
            boolean watched = resume != null && !resume.finished && ep[0].equals(resume.episodeName);
            holder.name.setTextColor(host.getColor(watched ? R.color.accent : R.color.text));
            holder.name.setTypeface(null, watched ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            holder.itemView.setOnClickListener(v -> {
                Intent i = host.playIntent(ep[1], ep[0]);
                host.startActivity(i);
            });
        }

        @Override
        public int getItemCount() {
            return episodes.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView name;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.episode);
            }
        }
    }
}
