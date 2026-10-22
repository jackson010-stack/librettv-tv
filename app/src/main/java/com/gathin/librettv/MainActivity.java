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
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home screen: the collector list on the left, the results on the right. */
public class MainActivity extends AppCompatActivity {

    private static final int MAX_RESULTS = 150;
    private static final int MAX_BROWSE = 600;
    /** How many collectors are asked for their categories before giving up on auto detection. */
    private static final int CATEGORY_PROBES = 8;

    /** What the right hand panel shows. */
    private enum Panel { SHELF, CATEGORIES, CATEGORY_VIDEOS, HISTORY }

    private static final int BROWSE_SEARCH = 0;
    private static final int BROWSE_CATEGORY = 1;

    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Api.Movie> found = new LinkedHashMap<>();
    private final LinkedHashMap<String, Api.Page> cache = new LinkedHashMap<>();
    private final Map<String, List<Api.Category>> classCache = new HashMap<>();
    private final Map<String, History.Entry> historyIndex = new HashMap<>();
    private final List<String> resolvingSources = new ArrayList<>();
    private final List<Api.Movie> browsed = new ArrayList<>();

    private SourceAdapter sourceAdapter;
    private PosterAdapter posterAdapter;
    private CategoryAdapter categoryAdapter;
    private HistoryAdapter historyAdapter;
    private EditText search;
    private TextView status;
    private TextView sourceCount;
    private RecyclerView results;
    private GridLayoutManager grid;
    private Button moreBtn;
    private Button clearBtn;
    private String query = "";
    private int generation = 0;
    private int categoryGeneration = 0;
    private boolean initialFocusDone = false;
    private boolean imeOpen = false;
    private boolean loadingMore = false;
    private InputMethodManager imm;

    /** The collector the right-hand panel is narrowed down to; null means all enabled ones. */
    private Sources.Source selected;
    private int browsePage = 1;
    private int browsePageCount = 1;
    private int browseTotal = 0;

    private Panel panel = Panel.SHELF;
    /** The collector whose categories are shown; also the one the last shelf came from. */
    private Sources.Source categorySource;
    /** The top level category whose sub categories are on screen, if any. */
    private Api.Category categoryParent;
    private Api.Category currentCategory;
    private Sources.Source browseSource;
    private String browseLabel = "";
    private int browseType = BROWSE_SEARCH;

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
        clearBtn = findViewById(R.id.clear_btn);

        sourceAdapter = new SourceAdapter(this, new SourceAdapter.OnToggle() {
            @Override
            public void onToggle(Sources.Source s, boolean on) {
                updateSourceCount();
                runSearch(query);
            }
        }, new SourceAdapter.OnSelect() {
            @Override
            public void onSelect(Sources.Source s) {
                selectSource(s);
            }

            @Override
            public void onOpenHistory() {
                openHistory();
            }

            @Override
            public void onOpenCategories() {
                openCategories();
            }
        });
        RecyclerView sourceList = findViewById(R.id.sources);
        sourceList.setLayoutManager(new LinearLayoutManager(this));
        sourceList.setAdapter(sourceAdapter);
        sourceAdapter.attach(sourceList);

        posterAdapter = new PosterAdapter(this);
        categoryAdapter = new CategoryAdapter(this);
        historyAdapter = new HistoryAdapter(this);
        grid = new GridLayoutManager(this, 3);
        results.setLayoutManager(grid);
        results.setAdapter(posterAdapter);
        results.post(() -> {
            int avail = results.getWidth() / dp(180);
            grid.setSpanCount(Math.max(3, avail));
        });

        Button searchBtn = findViewById(R.id.search_btn);
        searchBtn.setOnClickListener(v -> {
            openShelf();
            runSearch(search.getText().toString());
            hideIme();
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                openShelf();
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
        clearBtn.setOnClickListener(v -> {
            History.clear(this);
            historyIndex.clear();
            showHistory();
        });

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
    protected void onResume() {
        super.onResume();
        // The player may have filed a new position while we were away.
        reloadHistory();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String q = intent.getStringExtra("q");
        if (q != null && !q.isEmpty()) {
            search.setText(q);
            openShelf();
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
        results.requestFocus();
        focusFirstCell(0);
    }

    /**
     * A freshly filled grid has no cells yet, so the first one is grabbed once the layout ran.
     */
    private void focusFirstCell(int delayMs) {
        RecyclerView.ViewHolder vh = results.findViewHolderForAdapterPosition(0);
        if (vh != null) {
            vh.itemView.requestFocus();
            return;
        }
        results.postDelayed(() -> {
            RecyclerView.ViewHolder first = results.findViewHolderForAdapterPosition(0);
            if (first != null) {
                first.itemView.requestFocus();
            }
        }, delayMs);
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

    /** While paging, up from the grid should land on the load-more or clear button. */
    private void updateGridFocusTarget() {
        int up = gridUpTarget();
        results.setNextFocusUpId(up);
        for (int i = 0; i < results.getChildCount(); i++) {
            results.getChildAt(i).setNextFocusUpId(up);
        }
    }

    int gridUpTarget() {
        if (moreBtn.getVisibility() == View.VISIBLE) {
            return R.id.more_btn;
        }
        if (clearBtn.getVisibility() == View.VISIBLE) {
            return R.id.clear_btn;
        }
        return R.id.search;
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

    // ---------------------------------------------------------------- panels

    /** Back to the merged or single source shelf. */
    private void openShelf() {
        panel = Panel.SHELF;
        clearBtn.setVisibility(View.GONE);
        results.setAdapter(posterAdapter);
        sourceAdapter.rebind();
    }

    /** The right panel lists the titles that were watched. */
    private void openHistory() {
        panel = Panel.HISTORY;
        moreBtn.setVisibility(View.GONE);
        clearBtn.setVisibility(View.VISIBLE);
        sourceAdapter.rebind();
        showHistory();
    }

    private void showHistory() {
        results.setAdapter(historyAdapter);
        grid.setSpanCount(Math.max(3, results.getWidth() / dp(180)));
        List<History.Entry> list = new ArrayList<>(historyIndex.values());
        historyAdapter.setItems(list);
        if (list.isEmpty()) {
            status.setText("还没有播放记录，看完一部就会出现在这里");
        } else {
            status.setText("播放记录 " + list.size() + " 条 · 点一下接着看");
        }
        updateGridFocusTarget();
        focusGrid();
    }

    private void reloadHistory() {
        historyIndex.clear();
        for (History.Entry e : History.list(this)) {
            historyIndex.put(e.key, e);
        }
        if (panel == Panel.HISTORY) {
            historyAdapter.setItems(new ArrayList<>(historyIndex.values()));
        } else if (posterAdapter != null) {
            posterAdapter.refreshBadges();
        }
    }

    /** Starts the title again where it was left, straight from the history grid. */
    private void resume(History.Entry e) {
        if (e == null || e.episodeUrl == null || e.episodeUrl.isEmpty()) {
            status.setText("这条记录没有播放地址");
            return;
        }
        Sources.Source s = Sources.byKey(e.sourceKey);
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", e.episodeUrl);
        i.putExtra("referer", e.referer.isEmpty()
                ? (s == null ? "" : s.origin() + "/") : e.referer);
        i.putExtra("resumePosition", e.finished ? 0L : e.positionMs);
        i.putExtra("durationMs", e.durationMs);
        i.putExtra("title", e.name);
        i.putExtra("episodeName", e.episodeName);
        i.putExtra("pic", e.pic);
        i.putExtra("year", e.year);
        i.putExtra("type", e.type);
        i.putExtra("remarks", e.remarks);
        i.putExtra("playUrl", e.playUrl);
        i.putExtra("playFrom", e.playFrom);
        i.putExtra("sourceKey", e.sourceKey);
        i.putExtra("sourceName", e.sourceName);
        i.putExtra("vodId", e.id);
        i.putExtra("historyKey", e.key);
        startActivity(i);
    }

    /** The right panel lists the categories of one collector. */
    private void openCategories() {
        panel = Panel.CATEGORIES;
        categoryParent = null;
        moreBtn.setVisibility(View.GONE);
        clearBtn.setVisibility(View.GONE);
        sourceAdapter.rebind();
        loadCategories();
    }

    private void loadCategories() {
        results.setAdapter(categoryAdapter);
        categoryAdapter.setItems(new ArrayList<>());
        grid.setSpanCount(Math.max(3, results.getWidth() / dp(180)));
        if (categorySource != null) {
            fetchCategories(categorySource);
        } else {
            detectCategories();
        }
    }

    /** Asks the enabled collectors for their categories and keeps the first that answers. */
    private void detectCategories() {
        final int gen = ++categoryGeneration;
        final List<Sources.Source> probes = new ArrayList<>();
        for (Sources.Source s : Sources.ALL) {
            if (Prefs.isEnabled(this, s) && probes.size() < CATEGORY_PROBES) {
                probes.add(s);
            }
        }
        if (probes.isEmpty()) {
            status.setText("没有启用任何采集站");
            return;
        }
        status.setText("正在找能用的分类…");
        final int[] done = {0};
        for (final Sources.Source s : probes) {
            pool.execute(() -> {
                List<Api.Category> cats = Api.classes(s);
                main.post(() -> {
                    if (gen != categoryGeneration) {
                        return;
                    }
                    if (!cats.isEmpty()) {
                        classCache.put(s.key, cats);
                        showCategories(cats, s);
                    } else {
                        done[0]++;
                        if (done[0] == probes.size()) {
                            status.setText("没有读到分类，换一个采集站再试");
                        }
                    }
                });
            });
        }
    }

    private void fetchCategories(Sources.Source s) {
        final int gen = ++categoryGeneration;
        List<Api.Category> cached = classCache.get(s.key);
        if (cached != null) {
            showCategories(cached, s);
            return;
        }
        status.setText("正在读取 " + s.name + " 的分类…");
        pool.execute(() -> {
            List<Api.Category> cats = Api.classes(s);
            main.post(() -> {
                if (gen != categoryGeneration) {
                    return;
                }
                if (cats.isEmpty()) {
                    status.setText(s.name + " · 没读到分类（连不上或该站不提供分类）");
                    return;
                }
                classCache.put(s.key, cats);
                showCategories(cats, s);
            });
        });
    }

    private void showCategories(List<Api.Category> cats, Sources.Source s) {
        categorySource = s;
        categoryParent = null;
        showCategoryList(topLevel(cats), s.name + " · 点一个分类");
        resolveFlatTree(cats, s);
    }

    /**
     * Collectors that publish a flat category list hide their groups; their titles only carry the
     * leaf category, so the tree has to be worked out with one probe per category. The result is
     * kept, the collector is only ever asked once.
     */
    private void resolveFlatTree(List<Api.Category> cats, Sources.Source s) {
        if (cats.size() < 2 || resolvingSources.contains(s.key)) {
            return;
        }
        for (Api.Category c : cats) {
            if (c.typePid != 0) {
                return;
            }
        }
        String cached = Prefs.categoryTree(this, s.key);
        if (cached != null) {
            Api.applyTree(cats, cached);
            showCategoryList(topLevel(cats), s.name + " · 点一个分类");
            return;
        }
        resolvingSources.add(s.key);
        status.setText("正在分析 " + s.name + " 的分类层级…");
        pool.execute(() -> {
            Api.resolveParents(s, cats);
            Prefs.saveCategoryTree(MainActivity.this, s.key, Api.encodeTree(cats));
            main.post(() -> {
                resolvingSources.remove(s.key);
                if (categorySource == s && panel == Panel.CATEGORIES) {
                    showCategoryList(topLevel(cats), s.name + " · 点一个分类");
                }
            });
        });
    }

    /** The collectors list their categories as a tree: the top level ones have no parent. */
    private List<Api.Category> topLevel(List<Api.Category> cats) {
        List<Api.Category> out = new ArrayList<>();
        for (Api.Category c : cats) {
            if (c.typePid == 0) {
                out.add(c);
            }
        }
        if (out.isEmpty()) {
            return cats;
        }
        return out;
    }

    /** The sub categories of one top level category. */
    private List<Api.Category> childrenOf(Api.Category parent) {
        List<Api.Category> out = new ArrayList<>();
        List<Api.Category> cats = categorySource == null ? null : classCache.get(categorySource.key);
        if (cats == null) {
            return out;
        }
        for (Api.Category c : cats) {
            if (c.typePid == parent.typeId) {
                out.add(c);
            }
        }
        return out;
    }

    private void showCategoryList(List<Api.Category> cats, String hint) {
        categoryAdapter.setItems(cats);
        if (cats.isEmpty()) {
            status.setText("这个分类下面没有子类");
        } else {
            status.setText(hint + " · " + cats.size() + " 项");
        }
        updateGridFocusTarget();
        focusGrid();
    }

    /** Opens one category: a parent shows its sub categories, a leaf lists its titles. */
    private void openCategory(Api.Category c) {
        if (c == null || categorySource == null) {
            status.setText("先选一个采集站再浏览它的分类");
            return;
        }
        List<Api.Category> kids = childrenOf(c);
        if (!kids.isEmpty()) {
            categoryParent = c;
            showCategoryList(kids, c.typeName + " · 子类");
            return;
        }
        panel = Panel.CATEGORY_VIDEOS;
        currentCategory = c;
        browseSource = categorySource;
        browseType = BROWSE_CATEGORY;
        browseLabel = c.typeName + " · " + (categorySource == null ? "" : categorySource.name);
        browsePage = 1;
        browsePageCount = 1;
        browseTotal = 0;
        browsed.clear();
        results.setAdapter(posterAdapter);
        grid.setSpanCount(Math.max(3, results.getWidth() / dp(180)));
        posterAdapter.setItems(new ArrayList<>());
        moreBtn.setVisibility(View.GONE);
        clearBtn.setVisibility(View.GONE);
        updateGridFocusTarget();

        final int gen = ++generation;
        status.setText("正在读取 " + c.typeName + "…");
        pool.execute(() -> {
            Api.Page p = Api.categoryPage(categorySource, c.typeId, 1);
            main.post(() -> {
                if (gen != generation) {
                    return;
                }
                applyPage(p, true);
            });
        });
    }

    /** Picks a collector: the shelf shows its contents and its categories become the default. */
    private void selectSource(Sources.Source s) {
        selected = s;
        if (s != null) {
            categorySource = s;
        }
        openShelf();
        runSearch(query);
    }

    // ---------------------------------------------------------------- shelf

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
        clearBtn.setVisibility(View.GONE);
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
        browseSource = s;
        browseType = BROWSE_SEARCH;
        browseLabel = s.name;
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
        final Sources.Source s = browseSource;
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
        status.setText("正在读取 " + browseLabel + " 第 " + next + " 页…");

        pool.execute(() -> {
            Api.Page p;
            if (browseType == BROWSE_CATEGORY) {
                p = Api.categoryPage(s, currentCategory.typeId, next);
            } else {
                p = q.isEmpty() ? Api.catalogue(s, next) : Api.searchPage(s, q, next);
            }
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
            status.setText(browseLabel + " · 连不上（网络被阻断或站点已下线）");
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

        StringBuilder sb = new StringBuilder(browseLabel);
        sb.append(" · 已显示 ").append(browsed.size()).append(" 条");
        if (browseTotal > 0) {
            sb.append(" · 共 ").append(browseTotal).append(" 条");
        }
        sb.append(" · 第 ").append(browsePage).append("/").append(browsePageCount).append(" 页");
        if (browsed.isEmpty()) {
            sb.append(browseType == BROWSE_CATEGORY ? " · 该分类没有内容" : " · 该源没有内容");
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
    public void onBackPressed() {
        if (panel == Panel.CATEGORY_VIDEOS) {
            if (categoryParent != null) {
                panel = Panel.CATEGORIES;
                Api.Category parent = categoryParent;
                showCategoryList(childrenOf(parent), parent.typeName + " · 子类");
                return;
            }
            openCategories();
            return;
        }
        if (panel == Panel.CATEGORIES) {
            if (categoryParent != null) {
                categoryParent = null;
                List<Api.Category> cats = categorySource == null
                        ? null : classCache.get(categorySource.key);
                showCategoryList(topLevel(cats == null ? new ArrayList<>() : cats),
                        categorySource == null ? "分类" : categorySource.name + " · 点一个分类");
                return;
            }
            openShelf();
            runSearch(query);
            return;
        }
        if (panel == Panel.HISTORY) {
            openShelf();
            runSearch(query);
            return;
        }
        super.onBackPressed();
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

            void onOpenHistory();

            void onOpenCategories();
        }

        private static final int TYPE_SOURCE = 0;
        private static final int TYPE_HEADER = 1;
        private static final int TYPE_ALL = 2;
        private static final int TYPE_HISTORY = 3;
        private static final int TYPE_CATEGORY = 4;
        private static final Object ALL_ROW = new Object();
        private static final Object HISTORY_ROW = new Object();
        private static final Object CATEGORY_ROW = new Object();

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
            items.add(HISTORY_ROW);
            items.add(CATEGORY_ROW);
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
            if (item == HISTORY_ROW) {
                return TYPE_HISTORY;
            }
            if (item == CATEGORY_ROW) {
                return TYPE_CATEGORY;
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
            if (item == HISTORY_ROW) {
                vh.name.setText("继续观看");
                vh.mark.setText("▶");
                vh.mark.setTextColor(host.getColor(R.color.accent));
                vh.state.setVisibility(View.GONE);
                vh.state.setOnClickListener(null);
                vh.itemView.setNextFocusRightId(View.NO_ID);
                boolean active = host.panel == Panel.HISTORY;
                vh.itemView.setBackgroundResource(active ? R.drawable.bg_source_sel : R.drawable.bg_source);
                vh.name.setTextColor(host.getColor(active ? R.color.accent : R.color.text));
                vh.itemView.setOnClickListener(v -> onSelect.onOpenHistory());
                return;
            }
            if (item == CATEGORY_ROW) {
                vh.name.setText("影视分类");
                vh.mark.setText("▦");
                vh.mark.setTextColor(host.getColor(R.color.accent));
                vh.state.setVisibility(View.GONE);
                vh.state.setOnClickListener(null);
                vh.itemView.setNextFocusRightId(View.NO_ID);
                boolean active = host.panel == Panel.CATEGORIES || host.panel == Panel.CATEGORY_VIDEOS;
                vh.itemView.setBackgroundResource(active ? R.drawable.bg_source_sel : R.drawable.bg_source);
                vh.name.setTextColor(host.getColor(active ? R.color.accent : R.color.text));
                vh.itemView.setOnClickListener(v -> onSelect.onOpenCategories());
                return;
            }
            if (item == ALL_ROW) {
                vh.name.setText("全部采集站（合并）");
                vh.mark.setText("全");
                vh.mark.setTextColor(host.getColor(R.color.accent));
                vh.state.setVisibility(View.GONE);
                vh.state.setOnClickListener(null);
                vh.itemView.setNextFocusRightId(View.NO_ID);
                boolean active = host.panel == Panel.SHELF && host.selected == null;
                vh.itemView.setBackgroundResource(
                        active ? R.drawable.bg_source_sel : R.drawable.bg_source);
                vh.name.setTextColor(host.getColor(active ? R.color.accent : R.color.text));
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

            boolean sel = host.panel == Panel.SHELF && host.selected == s;
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
        private RecyclerView grid;

        PosterAdapter(MainActivity host) {
            this.host = host;
        }

        void setItems(List<Api.Movie> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        /**
         * Redraws the cards so a freshly filed position shows up on the badge. Only the visible
         * cards are rebound: a full notifyDataSetChanged would drop the D-pad focus.
         */
        void refreshBadges() {
            if (grid == null) {
                return;
            }
            for (int i = 0; i < grid.getChildCount(); i++) {
                View child = grid.getChildAt(i);
                int pos = grid.getChildAdapterPosition(child);
                RecyclerView.ViewHolder vh = grid.getChildViewHolder(child);
                if (pos == RecyclerView.NO_POSITION || !(vh instanceof VH)) {
                    continue;
                }
                bindBadge((VH) vh, items.get(pos));
            }
        }

        private void bindBadge(VH holder, Api.Movie m) {
            History.Entry seen = host.historyIndex.get(History.keyOf(m.sourceKey, m.id));
            if (seen != null) {
                holder.badge.setVisibility(View.VISIBLE);
                holder.badge.setText(seen.finished ? "▶ 重看" : "▶ 续播");
            } else {
                holder.badge.setVisibility(View.GONE);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (parent instanceof RecyclerView) {
                grid = (RecyclerView) parent;
            }
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
            bindBadge(holder, m);
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
            final TextView badge;

            VH(View v) {
                super(v);
                poster = v.findViewById(R.id.poster);
                title = v.findViewById(R.id.title);
                subtitle = v.findViewById(R.id.subtitle);
                badge = v.findViewById(R.id.badge);
            }
        }
    }

    /** The category grid. */
    static class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.VH> {
        private final MainActivity host;
        private List<Api.Category> items = new ArrayList<>();

        CategoryAdapter(MainActivity host) {
            this.host = host;
        }

        void setItems(List<Api.Category> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_category, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Api.Category c = items.get(position);
            holder.name.setText(c.typeName);
            int kids = host.childrenOf(c).size();
            if (kids > 0) {
                holder.parent.setText(kids + " 个子类");
            } else if (c.parentName.isEmpty()) {
                holder.parent.setText("分类");
            } else {
                holder.parent.setText(c.parentName + " · 子类");
            }
            holder.itemView.setOnClickListener(v -> host.openCategory(c));
            holder.itemView.setNextFocusUpId(host.gridUpTarget());
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
            final TextView name;
            final TextView parent;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.cat_name);
                parent = v.findViewById(R.id.cat_parent);
            }
        }
    }

    /** The titles that were watched, with how far they got. */
    static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {
        private final MainActivity host;
        private List<History.Entry> items = new ArrayList<>();

        HistoryAdapter(MainActivity host) {
            this.host = host;
        }

        void setItems(List<History.Entry> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            History.Entry e = items.get(position);
            holder.title.setText(e.name);
            StringBuilder sub = new StringBuilder();
            if (!e.episodeName.isEmpty()) {
                sub.append(e.episodeName);
            }
            if (e.sourceName != null && !e.sourceName.isEmpty()) {
                if (sub.length() > 0) {
                    sub.append(" · ");
                }
                sub.append(e.sourceName);
            }
            holder.subtitle.setText(sub.toString());
            if (e.finished) {
                holder.progress.setProgress(100);
                holder.time.setText("已看完 · 重新观看");
            } else {
                holder.progress.setProgress(e.progressPercent());
                holder.time.setText(History.formatMs(e.positionMs)
                        + (e.durationMs > 0 ? " / " + History.formatMs(e.durationMs) : ""));
            }
            Sources.Source s = Sources.byKey(e.sourceKey);
            ImageLoader.load(holder.poster, e.pic, s == null ? null : s.origin() + "/");
            holder.itemView.setOnClickListener(v -> host.resume(e));
            holder.itemView.setNextFocusUpId(host.gridUpTarget());
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
            final TextView time;
            final ProgressBar progress;

            VH(View v) {
                super(v);
                poster = v.findViewById(R.id.poster);
                title = v.findViewById(R.id.title);
                subtitle = v.findViewById(R.id.subtitle);
                time = v.findViewById(R.id.time);
                progress = v.findViewById(R.id.progress);
            }
        }
    }
}
