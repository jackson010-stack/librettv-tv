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
    private Sources.Source source;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        Bundle b = getIntent().getExtras();
        name = str(b, "name");
        String pic = str(b, "pic");
        String year = str(b, "year");
        String area = str(b, "area");
        String type = str(b, "type");
        String remarks = str(b, "remarks");
        String content = str(b, "content");
        String playUrl = str(b, "playUrl");
        String playFrom = str(b, "playFrom");
        String sourceKey = str(b, "sourceKey");
        String sourceName = str(b, "sourceName");

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
                lineBtn.setOnClickListener(v -> showLine(lines, index, label, list, lineBox));
                lineBox.addView(lineBtn, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        showLine(lines, 0, label, list, lineBox);
    }

    /** Shows the episodes of one play line and marks the active line button. */
    private void showLine(List<Api.Line> lines, int index, TextView label, RecyclerView list, LinearLayout lineBox) {
        Api.Line line = lines.get(index);
        label.setText("剧集（" + line.episodes.size() + "）");
        list.setAdapter(new EpisodeAdapter(this, line.episodes, name, source));
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
        private final String title;
        private final Sources.Source source;

        EpisodeAdapter(DetailActivity host, List<String[]> episodes, String title, Sources.Source source) {
            this.host = host;
            this.episodes = episodes;
            this.title = title;
            this.source = source;
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
            holder.itemView.setOnClickListener(v -> {
                Intent i = new Intent(host, PlayerActivity.class);
                i.putExtra("url", ep[1]);
                i.putExtra("title", title + " · " + ep[0]);
                i.putExtra("referer", source == null ? "" : source.origin() + "/");
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
