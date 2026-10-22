package com.gathin.librettv;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home screen: the collector list on the left, the results on the right. */
public class MainActivity extends AppCompatActivity {

    private static final int MAX_RESULTS = 150;
    private static final int MAX_BROWSE = 600;

    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Api.Movie> found = new LinkedHashMap<>();
    private final LinkedHashMap<String, Api.Page> cache = new LinkedHashMap<>();
    private final List<Api.Movie> browsed = new ArrayList<>();

    private SourceAdapter sourceAdapter;
    private PosterAdapter posterAdapter;
    private EditText search;
    private TextView status;
    private TextView sourceCount;
    private RecyclerView results;
    private Button moreBtn;
    private String query = "";
    private int generation = 0;
    private boolean initialFocusDone = false;
    private boolean imeOpen = false;
    private boolean loadingMore = false;
    private InputMethodManager imm;

    /** The collector the right-hand panel is narrowed down to; null means all enabled ones. */
    private Sources.Source selected;
    private int browsePage = 1;
    private int browsePageCount = 1;
    private int browseTotal = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

        search = findViewById(R.id.search);
        status = findViewById(R.id.status);
        sourceCount = findViewById(R.id.source_count);
        results = findViewById(R.id.results);
        moreBtn = findViewById(R.id.more_btn);

        sourceAdapter = new SourceAdapter(this, new SourceAdapter.OnToggle() {
            @Override
            public void onToggle(Sources.Source s, boolean on) {
                updateSourceCount();
                runSearch(query);
            }
        }, new SourceAdapter.OnSelect() {
            @Override
            public void onSelect(Sources.Source s) {
                selected = s;
                sourceAdapter.rebind();
                runSearch(query);
            }
        });
        RecyclerView sourceList = findViewById(R.id.sources);
        sourceList.setLayoutManager(new LinearLayoutManager(this));
        sourceList.setAdapter(sourceAdapter);
        sourceAdapter.attach(sourceList);

        posterAdapter = new PosterAdapter(this);
        GridLayoutManager grid = new GridLayoutManager(this, 3);
        results.setLayoutManager(grid);
        results.setAdapter(posterAdapter);
        results.post(() -> {
            int avail = results.getWidth() / dp(180);
            grid.setSpanCount(Math.max(3, avail));
        });

        Button searchBtn = findViewById(R.id.search_btn);
        searchBtn.setOnClickListener(v -> {
            runSearch(search.getText().toString());
            hideIme();
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(search.getText().toString());
                hideIme();
                results.requestFocus();
                return true;
            }
            return false;
        });
        // A focused EditText swallows DPAD_DOWN for cursor movement and relies on the ROM to raise
        // the IME on OK, so both are handled explicitly for a remote-driven screen.
        search.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // focusSearch only resolves the target, it does not move the focus.
                    View next = search.focusSearch(View.FOCUS_DOWN);
                    if (next != null) {
                        next.requestFocus();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // While the IME is up the arrows belong to the cursor, otherwise an empty
                    // field would eat them and the D-pad could never leave the search box.
                    if (imeOpen) {
                        return false;
                    }
                    int dir = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT : View.FOCUS_LEFT;
                    View next = search.focusSearch(dir);
                    if (next != null) {
                        next.requestFocus();
                    } else if (dir == View.FOCUS_LEFT) {
                        focusSourceList();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    showIme(v);
                    return true;
                }
            }
            return false;
        });

        moreBtn.setOnClickListener(v -> loadMore());

        Button allOn = findViewById(R.id.all_on);
        allOn.setOnClickListener(v -> {
            Prefs.setAll(this, true);
            sourceAdapter.refresh();
            updateSourceCount();
            runSearch(query);
        });
        Button allOff = findViewById(R.id.all_off);
        allOff.setOnClickListener(v -> {
            Prefs.setAll(this, false);
            sourceAdapter.refresh();
            updateSourceCount();
            runSearch(query);
        });

        updateSourceCount();
        String preset = getIntent().getStringExtra("q");
        if (preset != null && !preset.isEmpty()) {
            search.setText(preset);
        }
        runSearch(preset == null ? "" : preset);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String q = intent.getStringExtra("q");
        if (q != null && !q.isEmpty()) {
            search.setText(q);
            runSearch(q);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The window hands initial focus to the first focusable view, which is the search field.
        // Move it to the grid so the remote starts on content.
        if (hasFocus && !initialFocusDone) {
            initialFocusDone = true;
            results.postDelayed(() -> focusGrid(), 1500);
        }
    }

    /** Puts the D-pad on the result grid, away from the search field. */
    private void focusGrid() {
        search.setFocusableInTouchMode(false);
        results.setFocusable(true);
        results.setFocusableInTouchMode(true);
        if (!results.requestFocus() && results.getChildCount() > 0) {
            results.getChildAt(0).requestFocus();
        }
    }

    /** Puts the D-pad on the collector list, always at its first row. */
    private void focusSourceList() {
        final RecyclerView list = findViewById(R.id.sources);
        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(0);
        if (vh != null) {
            vh.itemView.requestFocus();
            return;
        }
        list.scrollToPosition(0);
        list.postDelayed(() -> {
            RecyclerView.ViewHolder top = list.findViewHolderForAdapterPosition(0);
            if (top != null) {
                top.itemView.requestFocus();
            }
        }, 150);
    }

    /** While paging a single collector, up from the grid should land on the load-more button. */
    private void updateGridFocusTarget() {
        int up = gridUpTarget();
        results.setNextFocusUpId(up);
        for (int i = 0; i < results.getChildCount(); i++) {
            results.getChildAt(i).setNextFocusUpId(up);
        }
    }

    int gridUpTarget() {
        return moreBtn.getVisibility() == View.VISIBLE ? R.id.more_btn : R.id.search;
    }

    private int dp(int px) {
        return (int) (px * getResources().getDisplayMetrics().density);
    }

    private void showIme(View v) {
        imeOpen = true;
        search.setFocusableInTouchMode(true);
        search.requestFocus();
        imm.showSoftInput(v, InputMethodManager.SHOW_FORCED);
    }

    private void hideIme() {
        imeOpen = false;
        imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private void updateSourceCount() {
        int on = 0;
        for (Sources.Source s : Sources.ALL) {
            if (Prefs.isEnabled(this, s)) {
                on++;
            }
        }
        sourceCount.setText("已启用 " + on + " / " + Sources.ALL.size() + " 个采集站");
    }

    private void runSearch(String q) {
        String next = q == null ? "" : q.trim();
        query = next;
        if (selected == null) {
            mergedSearch(next);
        } else {
            browseReset(next);
        }
    }

    /** The shelf: every enabled collector, merged. */
    private void mergedSearch(String next) {
        final int gen = ++generation;
        found.clear();
        posterAdapter.setItems(new ArrayList<>());
        moreBtn.setVisibility(View.GONE);
        updateGridFocusTarget();

        final List<Sources.Source> active = new ArrayList<>();
        for (Sources.Source s : Sources.ALL) {
            if (Prefs.isEnabled(this, s)) {
                active.add(s);
            }
        }
        if (active.isEmpty()) {
            status.setText("没有启用任何采集站");
            return;
        }
        status.setText((next.isEmpty() ? "正在加载 " : "正在搜索 ") + active.size() + " 个源…");

        final int[] done = {0};
        final int[] empty = {0};
        final int[] failed = {0};
        for (final Sources.Source s : active) {
            pool.execute(() -> {
                Api.Page p = cached(next, s);
                main.post(() -> {
                    if (gen != generation) {
                        return;
                    }
                    if (p.failed) {
                        failed[0]++;
                    } else if (p.items.isEmpty()) {
                        empty[0]++;
                    } else {
                        for (Api.Movie m : p.items) {
                            add(m);
                        }
                        posterAdapter.setItems(new ArrayList<>(found.values()));
                    }
                    done[0]++;
                    int usable = done[0] - empty[0] - failed[0];
                    status.setText("已返回 " + found.size() + " 条 · " + usable + "/" + active.size()
                            + " 个源可用" + (empty[0] > 0 ? " · " + empty[0] + " 个源无结果" : "")
                            + (failed[0] > 0 ? " · " + failed[0] + " 个源连不上" : ""));
                    if (done[0] == active.size()) {
                        // Rebinding the grid drops the focused card, so hand the focus back.
                        View current = getCurrentFocus();
                        if (current == null || current == search) {
                            focusGrid();
                        }
                    }
                });
            });
        }
    }

    /** One collector's own catalogue, page by page, so its contents can be browsed. */
    private void browseReset(String next) {
        final Sources.Source s = selected;
        final int gen = ++generation;
        browsed.clear();
        browsePage = 1;
        browsePageCount = 1;
        browseTotal = 0;
        posterAdapter.setItems(new ArrayList<>());
        moreBtn.setVisibility(View.GONE);
        moreBtn.setEnabled(true);
        updateGridFocusTarget();
        status.setText("正在读取 " + s.name + "…");

        pool.execute(() -> {
            Api.Page p = next.isEmpty() ? Api.catalogue(s, 1) : Api.searchPage(s, next, 1);
            main.post(() -> {
                if (gen != generation) {
                    return;
                }
                applyPage(p, true);
            });
        });
    }

    private void loadMore() {
        final Sources.Source s = selected;
        if (s == null || loadingMore) {
            return;
        }
        final int next = browsePage + 1;
        if (next > browsePageCount) {
            return;
        }
        final String q = query;
        final int gen = generation;
        // The button stays enabled so it keeps the D-pad focus while the page loads.
        loadingMore = true;
        status.setText("正在读取 " + s.name + " 第 " + next + " 页…");

        pool.execute(() -> {
            Api.Page p = q.isEmpty() ? Api.catalogue(s, next) : Api.searchPage(s, q, next);
            main.post(() -> {
                if (gen != generation) {
                    return;
                }
                applyPage(p, false);
            });
        });
    }

    private void applyPage(Api.Page p, boolean replace) {
        loadingMore = false;
        if (p.failed) {
            moreBtn.setVisibility(View.GONE);
            updateGridFocusTarget();
            status.setText(selected.name + " · 连不上（网络被阻断或站点已下线）");
            return;
        }
        if (replace) {
            browsed.clear();
        }
        browsed.addAll(p.items);
        while (browsed.size() > MAX_BROWSE) {
            browsed.remove(0);
        }
        if (p.page > 0) {
            browsePage = p.page;
        }
        browsePageCount = Math.max(1, p.pageCount);
        browseTotal = p.total;
        posterAdapter.setItems(new ArrayList<>(browsed));

        StringBuilder sb = new StringBuilder(selected.name);
        sb.append(" · 已显示 ").append(browsed.size()).append(" 条");
        if (browseTotal > 0) {
            sb.append(" · 共 ").append(browseTotal).append(" 条");
        }
        sb.append(" · 第 ").append(browsePage).append("/").append(browsePageCount).append(" 页");
        if (browsed.isEmpty()) {
            sb.append(" · 该源没有内容");
        }
        status.setText(sb.toString());

        moreBtn.setEnabled(true);
        if (browsePage < browsePageCount) {
            moreBtn.setVisibility(View.VISIBLE);
            moreBtn.setText("更多 (" + browsePage + "/" + browsePageCount + ")");
        } else {
            moreBtn.setVisibility(View.GONE);
        }
        updateGridFocusTarget();

        View current = getCurrentFocus();
        if (current == null || current == search) {
            focusGrid();
        }
    }

    /** Per-source results of the current query, kept so switching back and forth is instant. */
    private Api.Page cached(String q, Sources.Source s) {
        String key = q + "\u0000" + s.key;
        Api.Page hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        Api.Page page = q.isEmpty() ? Api.catalogue(s, 1) : Api.searchPage(s, q, 1);
        if (!page.failed) {
            if (cache.size() > 300) {
                cache.clear();
            }
            cache.put(key, page);
        }
        return page;
    }

    private void add(Api.Movie m) {
        if (found.size() >= MAX_RESULTS) {
            return;
        }
        String key = m.name + "|" + m.year;
        if (!found.containsKey(key)) {
            found.put(key, m);
        }
    }

    private void openDetail(Api.Movie m) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("id", m.id);
        i.putExtra("name", m.name);
        i.putExtra("pic", m.pic);
        i.putExtra("content", m.content);
        i.putExtra("year", m.year);
        i.putExtra("area", m.area);
        i.putExtra("type", m.type);
        i.putExtra("remarks", m.remarks);
        i.putExtra("playUrl", m.playUrl);
        i.putExtra("playFrom", m.playFrom);
        i.putExtra("sourceKey", m.sourceKey);
        i.putExtra("sourceName", m.sourceName);
        startActivity(i);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    /**
     * The collector list. A row shows the site's contents, the switch on its right turns the
     * collector on or off.
     */
    static class SourceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        interface OnToggle {
            void onToggle(Sources.Source s, boolean on);
        }

        interface OnSelect {
            void onSelect(Sources.Source s);
        }

        private static final int TYPE_SOURCE = 0;
        private static final int TYPE_HEADER = 1;
        private static final int TYPE_ALL = 2;
        private static final Object ALL_ROW = new Object();

        private final MainActivity host;
        private final OnToggle onToggle;
        private final OnSelect onSelect;
        private final List<Object> items = new ArrayList<>();
        private RecyclerView list;

        SourceAdapter(MainActivity host, OnToggle onToggle, OnSelect onSelect) {
            this.host = host;
            this.onToggle = onToggle;
            this.onSelect = onSelect;
            build();
        }

        void attach(RecyclerView list) {
            this.list = list;
        }

        private void build() {
            items.clear();
            items.add(ALL_ROW);
            items.addAll(Sources.NORMAL);
            if (!Sources.ADULT.isEmpty()) {
                items.add("成人站（上游默认关闭）");
                items.addAll(Sources.ADULT);
            }
        }

        void refresh() {
            build();
            rebind();
        }

        /**
         * Redraws the rows that are on screen. A full notifyDataSetChanged would detach the
         * focused row and throw the D-pad back to the result grid.
         */
        void rebind() {
            if (list == null) {
                notifyDataSetChanged();
                return;
            }
            for (int i = 0; i < list.getChildCount(); i++) {
                RecyclerView.ViewHolder vh = list.getChildViewHolder(list.getChildAt(i));
                int pos = vh.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    onBindViewHolder(vh, pos);
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            Object item = items.get(position);
            if (item == ALL_ROW) {
                return TYPE_ALL;
            }
            return item instanceof String ? TYPE_HEADER : TYPE_SOURCE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            return new SourceVH(inflater.inflate(R.layout.item_source, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final SourceVH vh = (SourceVH) holder;
            Object item = items.get(position);
            if (item instanceof String) {
                vh.name.setText((String) item);
                vh.name.setTextColor(host.getColor(R.color.text_dim));
                vh.mark.setText("");
                vh.state.setVisibility(View.GONE);
                vh.itemView.setNextFocusRightId(View.NO_ID);
                vh.itemView.setBackgroundResource(R.drawable.bg_source);
                vh.itemView.setOnClickListener(null);
                return;
            }
            if (item == ALL_ROW) {
                vh.name.setText("全部采集站（合并）");
                vh.mark.setText("全");
                vh.mark.setTextColor(host.getColor(R.color.accent));
                vh.state.setVisibility(View.GONE);
                vh.state.setOnClickListener(null);
                vh.itemView.setNextFocusRightId(View.NO_ID);
                vh.itemView.setBackgroundResource(
                        host.selected == null ? R.drawable.bg_source_sel : R.drawable.bg_source);
                vh.name.setTextColor(host.getColor(host.selected == null ? R.color.accent : R.color.text));
                vh.itemView.setOnClickListener(v -> onSelect.onSelect(null));
                return;
            }

            final Sources.Source s = (Sources.Source) item;
            vh.name.setText(s.name);
            boolean on = Prefs.isEnabled(host, s);
            vh.mark.setText(on ? "✓" : "—");
            vh.mark.setTextColor(host.getColor(on ? R.color.ok : R.color.text_dim));
            vh.state.setVisibility(View.VISIBLE);
            vh.state.setText(on ? "开" : "关");
            vh.itemView.setNextFocusRightId(R.id.state);

            boolean sel = host.selected == s;
            vh.name.setTextColor(host.getColor(sel ? R.color.accent : R.color.text));
            vh.itemView.setBackgroundResource(sel ? R.drawable.bg_source_sel : R.drawable.bg_source);

            vh.state.setOnClickListener(v -> {
                boolean now = !Prefs.isEnabled(host, s);
                Prefs.setEnabled(host, s, now);
                vh.mark.setText(now ? "✓" : "—");
                vh.mark.setTextColor(host.getColor(now ? R.color.ok : R.color.text_dim));
                vh.state.setText(now ? "开" : "关");
                onToggle.onToggle(s, now);
            });
            vh.itemView.setOnClickListener(view -> onSelect.onSelect(s));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class SourceVH extends RecyclerView.ViewHolder {
            final TextView mark;
            final TextView name;
            final TextView state;

            SourceVH(View v) {
                super(v);
                mark = v.findViewById(R.id.mark);
                name = v.findViewById(R.id.name);
                state = v.findViewById(R.id.state);
            }
        }
    }

    /** The result grid. */
    static class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.VH> {
        private final MainActivity host;
        private List<Api.Movie> items = new ArrayList<>();

        PosterAdapter(MainActivity host) {
            this.host = host;
        }

        void setItems(List<Api.Movie> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_poster, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Api.Movie m = items.get(position);
            holder.title.setText(m.name);
            StringBuilder sub = new StringBuilder();
            if (!TextUtils.isEmpty(m.year)) {
                sub.append(m.year);
            }
            if (!TextUtils.isEmpty(m.type)) {
                if (sub.length() > 0) {
                    sub.append(" · ");
                }
                sub.append(m.type);
            }
            sub.append(" · ").append(m.sourceName);
            if (!TextUtils.isEmpty(m.remarks)) {
                sub.append(" · ").append(m.remarks);
            }
            holder.subtitle.setText(sub.toString());
            Sources.Source s = Sources.byKey(m.sourceKey);
            ImageLoader.load(holder.poster, m.pic, s == null ? null : s.origin() + "/");
            holder.itemView.setOnClickListener(v -> host.openDetail(m));
            holder.itemView.setNextFocusUpId(host.gridUpTarget());
            // The geometric focus search picks an arbitrary row of the collector list, so the
            // D-pad always enters it at the top.
            holder.itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    host.focusSourceList();
                    return true;
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView poster;
            final TextView title;
            final TextView subtitle;

            VH(View v) {
                super(v);
                poster = v.findViewById(R.id.poster);
                title = v.findViewById(R.id.title);
                subtitle = v.findViewById(R.id.subtitle);
            }
        }
    }
}
