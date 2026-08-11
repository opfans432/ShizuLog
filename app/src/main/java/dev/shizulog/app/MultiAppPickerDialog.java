package dev.shizulog.app;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MultiAppPickerDialog extends Dialog {

    interface Listener {
        void onAppsSelected(List<String> packageNames);
    }

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final Object CACHE_LOCK = new Object();
    private static List<AppEntry> cachedApps;
    private static long cacheLoadedAtMs;
    private static final LruCache<String, Drawable> ICON_CACHE =
            new LruCache<>(96);

    private final Context appContext;
    private final Listener listener;
    private final LinkedHashSet<String> selected =
            new LinkedHashSet<>();

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService appLoader =
            Executors.newSingleThreadExecutor();

    private final ExecutorService iconLoader =
            Executors.newFixedThreadPool(2);

    private TextInputEditText searchInput;
    private ProgressBar progressBar;
    private TextView loadingText;
    private TextView resultCountText;
    private TextView emptyText;
    private ListView appList;
    private MaterialButton doneButton;

    private AppAdapter adapter;
    private final List<AppEntry> allApps =
            new ArrayList<>();

    private Runnable pendingFilter;
    private volatile boolean closed;

    MultiAppPickerDialog(
            @NonNull Context context,
            @NonNull Set<String> initialSelection,
            @NonNull Listener listener
    ) {
        super(context);
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.selected.addAll(initialSelection);
        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_multi_app_picker);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(
                    new ColorDrawable(Color.TRANSPARENT)
            );
        }

        searchInput = findViewById(R.id.multiPickerSearch);
        progressBar = findViewById(R.id.multiPickerProgress);
        loadingText = findViewById(R.id.multiPickerLoadingText);
        resultCountText = findViewById(R.id.multiPickerResultCount);
        emptyText = findViewById(R.id.multiPickerEmpty);
        appList = findViewById(R.id.multiPickerList);
        doneButton = findViewById(R.id.multiPickerDone);
        MaterialButton closeButton =
                findViewById(R.id.multiPickerClose);

        adapter = new AppAdapter();
        appList.setAdapter(adapter);

        updateDoneButton();

        closeButton.setOnClickListener(v -> dismiss());

        doneButton.setOnClickListener(v -> {
            listener.onAppsSelected(
                    new ArrayList<>(selected)
            );
            dismiss();
        });

        appList.setOnItemClickListener(
                (parent, view, position, id) -> {
                    AppEntry item = adapter.getItem(position);
                    if (item == null) return;

                    toggleSelected(item.packageName);
                    adapter.notifyDataSetChanged();
                    updateDoneButton();
                }
        );

        searchInput.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s,
                            int start,
                            int count,
                            int after
                    ) {}

                    @Override
                    public void onTextChanged(
                            CharSequence s,
                            int start,
                            int before,
                            int count
                    ) {
                        scheduleFilter(
                                s == null
                                        ? ""
                                        : s.toString()
                        );
                    }

                    @Override
                    public void afterTextChanged(
                            Editable s
                    ) {}
                }
        );

        loadAppsFast();
    }

    @Override
    protected void onStart() {
        super.onStart();

        Window window = getWindow();
        if (window == null) return;

        DisplayMetrics metrics =
                getContext()
                        .getResources()
                        .getDisplayMetrics();

        int width = Math.min(
                (int) (metrics.widthPixels * 0.94f),
                dpToPx(620)
        );

        int height = Math.min(
                (int) (metrics.heightPixels * 0.86f),
                dpToPx(800)
        );

        window.setLayout(width, height);
        window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams
                                .SOFT_INPUT_STATE_ALWAYS_HIDDEN
        );
    }

    @Override
    public void dismiss() {
        if (!closed) {
            closed = true;

            if (pendingFilter != null) {
                mainHandler.removeCallbacks(
                        pendingFilter
                );
                pendingFilter = null;
            }

            appLoader.shutdownNow();
            iconLoader.shutdownNow();
        }

        super.dismiss();
    }

    private void toggleSelected(String packageName) {
        if (selected.contains(packageName)) {
            selected.remove(packageName);
        } else {
            selected.add(packageName);
        }
    }

    private void updateDoneButton() {
        int count = selected.size();
        doneButton.setText(
                count == 0
                        ? "完成"
                        : "完成（" + count + "）"
        );
    }

    private void loadAppsFast() {
        List<AppEntry> cache = getCachedApps();
        boolean cacheFresh =
                cache != null && isCacheFresh();

        if (cache != null && !cache.isEmpty()) {
            setApps(cache);
            showLoading(false);

            if (!cacheFresh) {
                refreshAppsInBackground(false);
            }
            return;
        }

        showLoading(true);
        refreshAppsInBackground(true);
    }

    private void refreshAppsInBackground(
            boolean showBlockingLoading
    ) {
        appLoader.execute(() -> {
            try {
                List<AppEntry> loaded =
                        queryLauncherApps();

                synchronized (CACHE_LOCK) {
                    cachedApps =
                            new ArrayList<>(loaded);
                    cacheLoadedAtMs =
                            System.currentTimeMillis();
                }

                mainHandler.post(() -> {
                    if (closed || !isShowing()) {
                        return;
                    }

                    setApps(loaded);
                    showLoading(false);
                });
            } catch (Throwable error) {
                mainHandler.post(() -> {
                    if (closed || !isShowing()) {
                        return;
                    }

                    if (showBlockingLoading
                            || allApps.isEmpty()) {

                        showLoading(false);
                        emptyText.setText(
                                "读取应用列表失败"
                        );
                        emptyText.setVisibility(
                                View.VISIBLE
                        );
                        appList.setVisibility(
                                View.GONE
                        );
                        resultCountText.setText(
                                "0 个应用"
                        );
                    }
                });
            }
        });
    }

    private List<AppEntry> queryLauncherApps() {
        PackageManager pm =
                appContext.getPackageManager();

        Intent launcherIntent =
                new Intent(Intent.ACTION_MAIN);

        launcherIntent.addCategory(
                Intent.CATEGORY_LAUNCHER
        );

        List<ResolveInfo> resolved =
                pm.queryIntentActivities(
                        launcherIntent,
                        PackageManager.MATCH_ALL
                );

        Map<String, AppEntry> unique =
                new LinkedHashMap<>();

        for (ResolveInfo info : resolved) {
            if (Thread.currentThread()
                    .isInterrupted()) {
                break;
            }

            if (info.activityInfo == null
                    || info.activityInfo
                            .applicationInfo == null) {
                continue;
            }

            ApplicationInfo ai =
                    info.activityInfo.applicationInfo;

            String packageName = ai.packageName;

            if (packageName == null
                    || packageName.isEmpty()
                    || packageName.equals(
                            appContext.getPackageName()
                    )
                    || unique.containsKey(
                            packageName
                    )) {
                continue;
            }

            CharSequence loadedLabel =
                    info.loadLabel(pm);

            String label =
                    loadedLabel == null
                            ? packageName
                            : loadedLabel
                                    .toString()
                                    .trim();

            if (label.isEmpty()) {
                label = packageName;
            }

            unique.put(
                    packageName,
                    new AppEntry(
                            label,
                            packageName
                    )
            );
        }

        List<AppEntry> result =
                new ArrayList<>(
                        unique.values()
                );

        Collator collator =
                Collator.getInstance(
                        Locale.getDefault()
                );

        result.sort((left, right) -> {
            int byLabel =
                    collator.compare(
                            left.label,
                            right.label
                    );

            if (byLabel != 0) {
                return byLabel;
            }

            return left.packageName
                    .compareToIgnoreCase(
                            right.packageName
                    );
        });

        return result;
    }

    private void scheduleFilter(String query) {
        if (pendingFilter != null) {
            mainHandler.removeCallbacks(
                    pendingFilter
            );
        }

        final String requested =
                query == null ? "" : query;

        pendingFilter = () -> {
            pendingFilter = null;
            filterNow(requested);
        };

        mainHandler.postDelayed(
                pendingFilter,
                120L
        );
    }

    private void filterNow(String rawQuery) {
        String query =
                normalize(rawQuery);

        List<AppEntry> filtered =
                new ArrayList<>();

        if (query.isEmpty()) {
            filtered.addAll(allApps);
        } else {
            for (AppEntry item : allApps) {
                if (normalize(item.label)
                        .contains(query)
                        || item.packageName
                                .toLowerCase(
                                        Locale.ROOT
                                )
                                .contains(query)) {
                    filtered.add(item);
                }
            }
        }

        adapter.replace(filtered);

        resultCountText.setText(
                filtered.size()
                        + " 个应用 · 已选 "
                        + selected.size()
        );

        boolean empty =
                filtered.isEmpty()
                        && !allApps.isEmpty();

        emptyText.setText(
                "未找到匹配的应用"
        );

        emptyText.setVisibility(
                empty
                        ? View.VISIBLE
                        : View.GONE
        );

        appList.setVisibility(
                empty
                        ? View.GONE
                        : View.VISIBLE
        );
    }

    private static String normalize(
            String value
    ) {
        return value == null
                ? ""
                : value
                        .trim()
                        .toLowerCase(
                                Locale.ROOT
                        );
    }

    private void setApps(List<AppEntry> apps) {
        allApps.clear();
        allApps.addAll(apps);

        String query =
                searchInput.getText() == null
                        ? ""
                        : searchInput
                                .getText()
                                .toString();

        filterNow(query);
    }

    private void showLoading(boolean loading) {
        progressBar.setVisibility(
                loading
                        ? View.VISIBLE
                        : View.GONE
        );

        loadingText.setVisibility(
                loading
                        ? View.VISIBLE
                        : View.GONE
        );

        if (loading && allApps.isEmpty()) {
            appList.setVisibility(View.GONE);
            emptyText.setVisibility(View.GONE);
            resultCountText.setText(
                    "正在读取…"
            );
        } else if (!allApps.isEmpty()) {
            appList.setVisibility(
                    View.VISIBLE
            );
        }
    }

    private List<AppEntry> getCachedApps() {
        synchronized (CACHE_LOCK) {
            return cachedApps == null
                    ? null
                    : new ArrayList<>(
                            cachedApps
                    );
        }
    }

    private boolean isCacheFresh() {
        synchronized (CACHE_LOCK) {
            return cachedApps != null
                    && System.currentTimeMillis()
                            - cacheLoadedAtMs
                            < CACHE_TTL_MS;
        }
    }

    private int dpToPx(int dp) {
        return Math.round(
                dp * getContext()
                        .getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    private final class AppAdapter
            extends BaseAdapter {

        private final List<AppEntry>
                visibleApps =
                    new ArrayList<>();

        private final LayoutInflater inflater =
                LayoutInflater.from(
                        getContext()
                );

        void replace(List<AppEntry> items) {
            visibleApps.clear();
            visibleApps.addAll(items);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return visibleApps.size();
        }

        @Override
        public AppEntry getItem(
                int position
        ) {
            if (position < 0
                    || position
                            >= visibleApps.size()) {
                return null;
            }

            return visibleApps.get(position);
        }

        @Override
        public long getItemId(
                int position
        ) {
            AppEntry item =
                    getItem(position);

            return item == null
                    ? position
                    : item.packageName.hashCode();
        }

        @Override
        public View getView(
                int position,
                View convertView,
                ViewGroup parent
        ) {
            Holder holder;

            if (convertView == null) {
                convertView =
                        inflater.inflate(
                                R.layout
                                        .item_multi_app_picker,
                                parent,
                                false
                        );

                holder = new Holder();
                holder.icon =
                        convertView.findViewById(
                                R.id.multiPickerItemIcon
                        );
                holder.label =
                        convertView.findViewById(
                                R.id.multiPickerItemLabel
                        );
                holder.packageName =
                        convertView.findViewById(
                                R.id.multiPickerItemPackage
                        );
                holder.check =
                        convertView.findViewById(
                                R.id.multiPickerItemCheck
                        );

                convertView.setTag(holder);
            } else {
                holder =
                        (Holder)
                                convertView.getTag();
            }

            AppEntry item =
                    getItem(position);

            if (item == null) {
                return convertView;
            }

            holder.label.setText(item.label);
            holder.packageName.setText(
                    item.packageName
            );
            holder.check.setChecked(
                    selected.contains(
                            item.packageName
                    )
            );

            holder.icon.setTag(
                    item.packageName
            );

            holder.icon.setImageResource(
                    android.R.drawable
                            .sym_def_app_icon
            );

            Drawable cachedIcon;

            synchronized (ICON_CACHE) {
                cachedIcon =
                        ICON_CACHE.get(
                                item.packageName
                        );
            }

            if (cachedIcon != null) {
                holder.icon.setImageDrawable(
                        cachedIcon
                );
            } else {
                loadIconAsync(
                        holder.icon,
                        item.packageName
                );
            }

            return convertView;
        }
    }

    private void loadIconAsync(
            ImageView target,
            String packageName
    ) {
        if (iconLoader.isShutdown()) {
            return;
        }

        iconLoader.execute(() -> {
            Drawable icon;

            try {
                icon = appContext
                        .getPackageManager()
                        .getApplicationIcon(
                                packageName
                        );

                if (icon != null) {
                    synchronized (ICON_CACHE) {
                        ICON_CACHE.put(
                                packageName,
                                icon
                        );
                    }
                }
            } catch (Throwable ignored) {
                icon = null;
            }

            Drawable finalIcon = icon;

            mainHandler.post(() -> {
                if (closed || !isShowing()) {
                    return;
                }

                if (!packageName.equals(
                        target.getTag()
                )) {
                    return;
                }

                if (finalIcon != null) {
                    target.setImageDrawable(
                            finalIcon
                    );
                } else {
                    target.setImageResource(
                            android.R.drawable
                                    .sym_def_app_icon
                    );
                }
            });
        });
    }

    private static final class Holder {
        ImageView icon;
        TextView label;
        TextView packageName;
        CheckBox check;
    }

    private static final class AppEntry {
        final String label;
        final String packageName;

        AppEntry(
                String label,
                String packageName
        ) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
