package dev.shizulog.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogHistoryActivity extends AppCompatActivity {

    private static final int REQ_EXPORT_LOG = 4101;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_SINGLE = 1;
    private static final int FILTER_MULTI = 2;
    private static final int FILTER_GLOBAL = 3;
    private static final int FILTER_CRASH = 4;

    private static final int SORT_NEWEST = 0;
    private static final int SORT_LARGEST = 1;
    private static final int SORT_OLDEST = 2;

    private static final Pattern ROTATED_PART_PATTERN =
            Pattern.compile("_part(\\d+)\\.log$", Pattern.CASE_INSENSITIVE);

    private final Handler uiHandler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final List<LogEntry> allEntries =
            new ArrayList<>();

    private final List<LogEntry> visibleEntries =
            new ArrayList<>();

    private TextInputEditText searchInput;
    private TextView summaryText;
    private TextView emptyText;
    private ProgressBar progressBar;
    private ListView historyList;
    private MaterialButton sortButton;

    private Chip filterAll;
    private Chip filterSingle;
    private Chip filterMulti;
    private Chip filterGlobal;
    private Chip filterCrash;

    private HistoryAdapter adapter;

    private int filterMode = FILTER_ALL;
    private int sortMode = SORT_NEWEST;
    private Runnable pendingFilter;
    private volatile int loadGeneration;

    private File pendingExportFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_history);

        View root = findViewById(R.id.historyRoot);
        ViewCompat.setOnApplyWindowInsetsListener(
                root,
                (view, insets) -> {
                    Insets bars = insets.getInsets(
                            WindowInsetsCompat.Type.systemBars()
                    );
                    view.setPadding(
                            bars.left,
                            bars.top,
                            bars.right,
                            bars.bottom
                    );
                    return insets;
                }
        );

        MaterialToolbar toolbar =
                findViewById(R.id.historyToolbar);
        toolbar.setNavigationIcon(
                R.drawable.ic_arrow_back_24
        );
        toolbar.setNavigationOnClickListener(
                v -> finish()
        );

        searchInput =
                findViewById(R.id.historySearch);
        summaryText =
                findViewById(R.id.historySummary);
        emptyText =
                findViewById(R.id.historyEmpty);
        progressBar =
                findViewById(R.id.historyProgress);
        historyList =
                findViewById(R.id.historyList);
        sortButton =
                findViewById(R.id.historySort);
        MaterialButton cleanupButton =
                findViewById(R.id.historyCleanup);

        filterAll =
                findViewById(R.id.historyFilterAll);
        filterSingle =
                findViewById(R.id.historyFilterSingle);
        filterMulti =
                findViewById(R.id.historyFilterMulti);
        filterGlobal =
                findViewById(R.id.historyFilterGlobal);
        filterCrash =
                findViewById(R.id.historyFilterCrash);

        adapter = new HistoryAdapter();
        historyList.setAdapter(adapter);

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
                        scheduleFilter();
                    }

                    @Override
                    public void afterTextChanged(
                            Editable s
                    ) {}
                }
        );

        filterAll.setOnClickListener(
                v -> setFilter(FILTER_ALL)
        );
        filterSingle.setOnClickListener(
                v -> setFilter(FILTER_SINGLE)
        );
        filterMulti.setOnClickListener(
                v -> setFilter(FILTER_MULTI)
        );
        filterGlobal.setOnClickListener(
                v -> setFilter(FILTER_GLOBAL)
        );
        filterCrash.setOnClickListener(
                v -> setFilter(FILTER_CRASH)
        );

        sortButton.setOnClickListener(
                v -> cycleSortMode()
        );

        cleanupButton.setOnClickListener(
                v -> showCleanupDialog()
        );

        historyList.setOnItemClickListener(
                (parent, view, position, id) -> {
                    LogEntry entry =
                            adapter.getItem(position);
                    if (entry != null) {
                        openLog(entry.file);
                    }
                }
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistoryAsync();
    }

    private void setFilter(int mode) {
        filterMode = mode;
        scheduleFilter();
    }

    private void cycleSortMode() {
        sortMode = (sortMode + 1) % 3;

        if (sortMode == SORT_LARGEST) {
            sortButton.setText("排序：最大");
        } else if (sortMode == SORT_OLDEST) {
            sortButton.setText("排序：最旧");
        } else {
            sortButton.setText("排序：最新");
        }

        applyFilter();
    }

    private void scheduleFilter() {
        if (pendingFilter != null) {
            uiHandler.removeCallbacks(
                    pendingFilter
            );
        }

        pendingFilter = () -> {
            pendingFilter = null;
            applyFilter();
        };

        uiHandler.postDelayed(
                pendingFilter,
                120L
        );
    }

    private void loadHistoryAsync() {
        final int generation =
                ++loadGeneration;

        progressBar.setVisibility(
                View.VISIBLE
        );
        emptyText.setVisibility(
                View.GONE
        );

        executor.execute(() -> {
            List<File> files =
                    new ArrayList<>();

            collectLogs(
                    getExternalFilesDir(null),
                    files
            );

            List<LogEntry> parsed =
                    new ArrayList<>();

            long totalBytes = 0L;

            for (File file : files) {
                if (Thread.currentThread()
                        .isInterrupted()) {
                    return;
                }

                LogEntry entry =
                        parseLogEntry(file);

                parsed.add(entry);
                totalBytes += file.length();
            }

            final long finalTotalBytes =
                    totalBytes;

            uiHandler.post(() -> {
                if (isFinishing()
                        || isDestroyed()
                        || generation
                                != loadGeneration) {
                    return;
                }

                allEntries.clear();
                allEntries.addAll(parsed);

                progressBar.setVisibility(
                        View.GONE
                );

                applyFilter();

                summaryText.setTag(
                        finalTotalBytes
                );
            });
        });
    }

    private void collectLogs(
            File dir,
            List<File> out
    ) {
        if (dir == null
                || !dir.isDirectory()) {
            return;
        }

        File[] children =
                dir.listFiles();

        if (children == null) {
            return;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                collectLogs(child, out);
            } else if (child.isFile()
                    && child.getName()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".log")) {
                out.add(child);
            }
        }
    }

    private LogEntry parseLogEntry(File file) {
        String header =
                readHeadText(
                        file,
                        32 * 1024
                );

        String mode =
                parseHeaderValue(
                        header,
                        "mode"
                );

        String packages =
                parseHeaderValue(
                        header,
                        "packages"
                );

        if (mode.isEmpty()) {
            mode = inferModeFromFilename(
                    file.getName()
            );
        }

        boolean hasCrash =
                detectCrash(file);

        int partNumber = -1;
        Matcher partMatcher =
                ROTATED_PART_PATTERN
                        .matcher(
                                file.getName()
                        );

        if (partMatcher.find()) {
            try {
                partNumber =
                        Integer.parseInt(
                                partMatcher.group(1)
                        );
            } catch (Exception ignored) {}
        }

        String title =
                buildTitle(
                        mode,
                        packages
                );

        String modeLabel =
                modeLabel(mode);

        String packageSummary =
                buildPackageSummary(
                        packages
                );

        StringBuilder searchable =
                new StringBuilder();

        searchable
                .append(file.getName())
                .append(' ')
                .append(title)
                .append(' ')
                .append(mode)
                .append(' ')
                .append(modeLabel)
                .append(' ')
                .append(packages);

        return new LogEntry(
                file,
                mode,
                modeLabel,
                title,
                packages,
                packageSummary,
                hasCrash,
                partNumber,
                searchable.toString()
                        .toLowerCase(Locale.ROOT)
        );
    }

    private String buildTitle(
            String mode,
            String packages
    ) {
        if ("global".equals(mode)) {
            return "全局 Logcat";
        }

        String[] packageList =
                splitPackages(packages);

        if ("multi".equals(mode)) {
            if (packageList.length > 0) {
                return "多应用 · "
                        + packageList.length
                        + " 个应用";
            }

            return "多应用记录";
        }

        if (packageList.length > 0) {
            return getAppLabelOrPackage(
                    packageList[0]
            );
        }

        return "日志记录";
    }

    private String buildPackageSummary(
            String packages
    ) {
        String[] packageList =
                splitPackages(packages);

        if (packageList.length == 0) {
            return "";
        }

        StringBuilder out =
                new StringBuilder();

        int shown = Math.min(
                3,
                packageList.length
        );

        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                out.append("、");
            }

            out.append(
                    getAppLabelOrPackage(
                            packageList[i]
                    )
            );
        }

        if (packageList.length > shown) {
            out.append(" 等");
        }

        return out.toString();
    }

    private String getAppLabelOrPackage(
            String packageName
    ) {
        try {
            return String.valueOf(
                    getPackageManager()
                            .getApplicationLabel(
                                    getPackageManager()
                                            .getApplicationInfo(
                                                    packageName,
                                                    0
                                            )
                            )
            );
        } catch (Exception ignored) {
            return packageName;
        }
    }

    private static String[] splitPackages(
            String packages
    ) {
        if (packages == null
                || packages.trim().isEmpty()) {
            return new String[0];
        }

        String[] raw =
                packages.split(",");

        List<String> clean =
                new ArrayList<>();

        for (String item : raw) {
            String value =
                    item.trim();

            if (!value.isEmpty()) {
                clean.add(value);
            }
        }

        return clean.toArray(
                new String[0]
        );
    }

    private static String parseHeaderValue(
            String header,
            String key
    ) {
        if (header == null
                || header.isEmpty()) {
            return "";
        }

        String prefix =
                "# " + key + "=";

        String[] lines =
                header.split("\\n");

        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return line.substring(
                        prefix.length()
                ).trim();
            }
        }

        return "";
    }

    private static String inferModeFromFilename(
            String fileName
    ) {
        String lower =
                fileName.toLowerCase(
                        Locale.ROOT
                );

        if (lower.startsWith("global_")) {
            return "global";
        }

        if (lower.startsWith("multi_")) {
            return "multi";
        }

        return "single";
    }

    private static String modeLabel(
            String mode
    ) {
        if ("global".equals(mode)) {
            return "全局";
        }

        if ("multi".equals(mode)) {
            return "多应用";
        }

        if ("single".equals(mode)) {
            return "单应用";
        }

        return "未知";
    }

    private static String readHeadText(
            File file,
            int maxBytes
    ) {
        if (file == null
                || !file.isFile()) {
            return "";
        }

        try (FileInputStream in =
                     new FileInputStream(file)) {

            int length =
                    (int) Math.min(
                            file.length(),
                            maxBytes
                    );

            byte[] data =
                    new byte[Math.max(
                            1,
                            length
                    )];

            int total = 0;
            int count;

            while (total < length
                    && (count = in.read(
                            data,
                            total,
                            length - total
                    )) > 0) {
                total += count;
            }

            return new String(
                    data,
                    0,
                    total,
                    StandardCharsets.UTF_8
            );
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean detectCrash(
            File file
    ) {
        if (file == null
                || !file.isFile()
                || file.length() == 0) {
            return false;
        }

        final int maxBytes =
                256 * 1024;

        try (RandomAccessFile raf =
                     new RandomAccessFile(
                             file,
                             "r"
                     )) {

            long start =
                    Math.max(
                            0L,
                            file.length()
                                    - maxBytes
                    );

            raf.seek(start);

            int length =
                    (int) Math.min(
                            maxBytes,
                            file.length()
                                    - start
                    );

            byte[] data =
                    new byte[length];

            raf.readFully(data);

            String text =
                    new String(
                            data,
                            StandardCharsets.UTF_8
                    )
                            .toLowerCase(
                                    Locale.ROOT
                            );

            return text.contains(
                    "fatal exception"
            )
                    || text.contains(
                    "auto crash snapshot"
            )
                    || text.contains(
                    "anr in "
            )
                    || text.contains(
                    "signal 6"
            )
                    || text.contains(
                    "signal 11"
            )
                    || text.contains(
                    "sigabrt"
            )
                    || text.contains(
                    "sigsegv"
            )
                    || text.contains(
                    "native crash"
            );
        } catch (Exception ignored) {
            return false;
        }
    }

    private void applyFilter() {
        visibleEntries.clear();

        String query =
                searchInput.getText() == null
                        ? ""
                        : searchInput
                                .getText()
                                .toString()
                                .trim()
                                .toLowerCase(
                                        Locale.ROOT
                                );

        for (LogEntry entry :
                allEntries) {

            if (!matchesModeFilter(entry)) {
                continue;
            }

            if (!query.isEmpty()
                    && !entry.searchableText
                            .contains(query)) {
                continue;
            }

            visibleEntries.add(entry);
        }

        Comparator<LogEntry> comparator;

        if (sortMode == SORT_LARGEST) {
            comparator =
                    (a, b) ->
                            Long.compare(
                                    b.file.length(),
                                    a.file.length()
                            );
        } else if (sortMode
                == SORT_OLDEST) {
            comparator =
                    Comparator.comparingLong(
                            a -> a.file
                                    .lastModified()
                    );
        } else {
            comparator =
                    (a, b) ->
                            Long.compare(
                                    b.file.lastModified(),
                                    a.file.lastModified()
                            );
        }

        visibleEntries.sort(comparator);
        adapter.notifyDataSetChanged();

        long totalBytes = 0L;

        for (LogEntry entry : allEntries) {
            totalBytes +=
                    entry.file.length();
        }

        summaryText.setText(
                "显示 "
                        + visibleEntries.size()
                        + " / "
                        + allEntries.size()
                        + " · 总占用 "
                        + humanSize(totalBytes)
        );

        boolean empty =
                visibleEntries.isEmpty()
                        && progressBar
                                .getVisibility()
                                != View.VISIBLE;

        emptyText.setText(
                allEntries.isEmpty()
                        ? "还没有历史日志\n开始记录后，每次日志会话都会保存在这里"
                        : "没有符合当前搜索或筛选条件的日志"
        );

        emptyText.setVisibility(
                empty
                        ? View.VISIBLE
                        : View.GONE
        );

        historyList.setVisibility(
                empty
                        ? View.GONE
                        : View.VISIBLE
        );
    }

    private boolean matchesModeFilter(
            LogEntry entry
    ) {
        if (filterMode == FILTER_CRASH) {
            return entry.hasCrash;
        }

        if (filterMode == FILTER_SINGLE) {
            return "single".equals(
                    entry.mode
            );
        }

        if (filterMode == FILTER_MULTI) {
            return "multi".equals(
                    entry.mode
            );
        }

        if (filterMode == FILTER_GLOBAL) {
            return "global".equals(
                    entry.mode
            );
        }

        return true;
    }

    private void openLog(File file) {
        Intent intent =
                new Intent(
                        this,
                        FullLogActivity.class
                );

        intent.putExtra(
                FullLogActivity.EXTRA_FILE,
                file.getAbsolutePath()
        );

        startActivity(intent);
    }

    private void requestExport(File file) {
        if (file == null
                || !file.isFile()) {
            toast("日志文件不存在");
            return;
        }

        pendingExportFile = file;

        Intent intent =
                new Intent(
                        Intent.ACTION_CREATE_DOCUMENT
                )
                        .addCategory(
                                Intent.CATEGORY_OPENABLE
                        )
                        .setType(
                                "text/plain"
                        )
                        .putExtra(
                                Intent.EXTRA_TITLE,
                                file.getName()
                        );

        startActivityForResult(
                intent,
                REQ_EXPORT_LOG
        );
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode
                != REQ_EXPORT_LOG
                || resultCode
                != RESULT_OK
                || data == null
                || pendingExportFile
                == null) {
            return;
        }

        Uri uri = data.getData();

        if (uri == null) {
            return;
        }

        try (FileInputStream in =
                     new FileInputStream(
                             pendingExportFile
                     );
             OutputStream out =
                     getContentResolver()
                             .openOutputStream(
                                     uri,
                                     "w"
                             )) {

            if (out == null) {
                throw new IllegalStateException(
                        "无法打开导出位置"
                );
            }

            byte[] buffer =
                    new byte[32 * 1024];

            int count;

            while ((count =
                            in.read(buffer))
                            > 0) {
                out.write(
                        buffer,
                        0,
                        count
                );
            }

            out.flush();
            toast("日志已导出");
        } catch (Exception e) {
            toast(
                    "导出失败："
                            + e.getMessage()
            );
        } finally {
            pendingExportFile = null;
        }
    }

    private void confirmDelete(
            LogEntry entry
    ) {
        if (isCurrentRecordingFile(
                entry.file
        )) {
            toast(
                    "当前正在记录的日志不能删除"
            );
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("删除这份日志？")
                .setMessage(
                        entry.file.getName()
                                + "\n"
                                + humanSize(
                                        entry.file.length()
                                )
                )
                .setPositiveButton(
                        "删除",
                        (dialog, which) ->
                                deleteLogAsync(
                                        entry.file
                                )
                )
                .setNegativeButton(
                        "取消",
                        null
                )
                .show();
    }

    private void deleteLogAsync(File file) {
        executor.execute(() -> {
            boolean deleted =
                    file.delete();

            uiHandler.post(() -> {
                toast(
                        deleted
                                ? "已删除"
                                : "删除失败"
                );
                loadHistoryAsync();
            });
        });
    }

    private void showCleanupDialog() {
        String[] options = {
                "删除 7 天前的日志",
                "删除 30 天前的日志",
                "删除全部历史日志"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("清理历史日志")
                .setItems(
                        options,
                        (dialog, which) -> {
                            if (which == 0) {
                                confirmCleanupDays(7);
                            } else if (which == 1) {
                                confirmCleanupDays(30);
                            } else {
                                confirmCleanupDays(0);
                            }
                        }
                )
                .setNegativeButton(
                        "取消",
                        null
                )
                .show();
    }

    private void confirmCleanupDays(
            int days
    ) {
        String message;

        if (days <= 0) {
            message =
                    "将删除所有历史日志。当前正在记录的文件会自动跳过。";
        } else {
            message =
                    "将删除 "
                            + days
                            + " 天前的日志。当前正在记录的文件会自动跳过。";
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("确认清理")
                .setMessage(message)
                .setPositiveButton(
                        "清理",
                        (dialog, which) ->
                                cleanupAsync(days)
                )
                .setNegativeButton(
                        "取消",
                        null
                )
                .show();
    }

    private void cleanupAsync(int days) {
        executor.execute(() -> {
            long now =
                    System.currentTimeMillis();

            long threshold =
                    days <= 0
                            ? Long.MAX_VALUE
                            : days
                                    * 24L
                                    * 60L
                                    * 60L
                                    * 1000L;

            int deletedCount = 0;
            long deletedBytes = 0L;

            List<File> files =
                    new ArrayList<>();

            collectLogs(
                    getExternalFilesDir(null),
                    files
            );

            for (File file : files) {
                if (isCurrentRecordingFile(
                        file
                )) {
                    continue;
                }

                boolean shouldDelete =
                        days <= 0
                                || now
                                - file.lastModified()
                                >= threshold;

                if (shouldDelete) {
                    long size =
                            file.length();

                    if (file.delete()) {
                        deletedCount++;
                        deletedBytes += size;
                    }
                }
            }

            final int finalDeletedCount =
                    deletedCount;

            final long finalDeletedBytes =
                    deletedBytes;

            uiHandler.post(() -> {
                toast(
                        "已清理 "
                                + finalDeletedCount
                                + " 份 · "
                                + humanSize(
                                        finalDeletedBytes
                                )
                );

                loadHistoryAsync();
            });
        });
    }

    private boolean isCurrentRecordingFile(
            File file
    ) {
        if (file == null) {
            return false;
        }

        try {
            String activePath =
                    getSharedPreferences(
                            "shizulog_state",
                            MODE_PRIVATE
                    ).getString(
                            "current_log_path",
                            ""
                    );

            boolean recording =
                    getSharedPreferences(
                            "shizulog_state",
                            MODE_PRIVATE
                    ).getBoolean(
                            "recording",
                            false
                    );

            if (!recording
                    || activePath == null
                    || activePath.isEmpty()) {
                return false;
            }

            return file.getCanonicalPath()
                    .equals(
                            new File(
                                    activePath
                            ).getCanonicalPath()
                    );
        } catch (Exception ignored) {
            return false;
        }
    }

    private void toast(String text) {
        Toast.makeText(
                this,
                text,
                Toast.LENGTH_SHORT
        ).show();
    }

    private static String humanSize(
            long bytes
    ) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        double kb =
                bytes / 1024.0;

        if (kb < 1024) {
            return String.format(
                    Locale.US,
                    "%.1f KB",
                    kb
            );
        }

        double mb =
                kb / 1024.0;

        if (mb < 1024) {
            return String.format(
                    Locale.US,
                    "%.2f MB",
                    mb
            );
        }

        return String.format(
                Locale.US,
                "%.2f GB",
                mb / 1024.0
        );
    }

    @Override
    protected void onDestroy() {
        if (pendingFilter != null) {
            uiHandler.removeCallbacks(
                    pendingFilter
            );
            pendingFilter = null;
        }

        executor.shutdownNow();

        super.onDestroy();
    }

    private final class HistoryAdapter
            extends BaseAdapter {

        @Override
        public int getCount() {
            return visibleEntries.size();
        }

        @Override
        public LogEntry getItem(
                int position
        ) {
            if (position < 0
                    || position
                            >= visibleEntries.size()) {
                return null;
            }

            return visibleEntries.get(
                    position
            );
        }

        @Override
        public long getItemId(
                int position
        ) {
            LogEntry entry =
                    getItem(position);

            return entry == null
                    ? position
                    : entry.file
                            .getAbsolutePath()
                            .hashCode();
        }

        @Override
        public View getView(
                int position,
                View convertView,
                android.view.ViewGroup parent
        ) {
            Holder holder;

            if (convertView == null) {
                convertView =
                        getLayoutInflater()
                                .inflate(
                                        R.layout
                                                .item_history_log,
                                        parent,
                                        false
                                );

                holder = new Holder();

                holder.title =
                        convertView.findViewById(
                                R.id.historyItemTitle
                        );
                holder.mode =
                        convertView.findViewById(
                                R.id.historyItemMode
                        );
                holder.meta =
                        convertView.findViewById(
                                R.id.historyItemMeta
                        );
                holder.packages =
                        convertView.findViewById(
                                R.id.historyItemPackages
                        );
                holder.crash =
                        convertView.findViewById(
                                R.id.historyItemCrash
                        );
                holder.export =
                        convertView.findViewById(
                                R.id.historyItemExport
                        );
                holder.delete =
                        convertView.findViewById(
                                R.id.historyItemDelete
                        );

                convertView.setTag(holder);
            } else {
                holder =
                        (Holder)
                                convertView.getTag();
            }

            LogEntry entry =
                    getItem(position);

            if (entry == null) {
                return convertView;
            }

            holder.title.setText(
                    entry.title
            );

            String modeText =
                    entry.modeLabel;

            if (entry.partNumber >= 0) {
                modeText +=
                        " · 第 "
                                + entry.partNumber
                                + " 卷";
            }

            holder.mode.setText(modeText);

            String date =
                    DateFormat.getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT
                    ).format(
                            new Date(
                                    entry.file
                                            .lastModified()
                            )
                    );

            holder.meta.setText(
                    date
                            + " · "
                            + humanSize(
                                    entry.file.length()
                            )
            );

            if (entry.packageSummary
                    .isEmpty()) {
                holder.packages.setText(
                        entry.file.getName()
                );
            } else {
                holder.packages.setText(
                        entry.packageSummary
                                + "\n"
                                + entry.file
                                        .getName()
                );
            }

            holder.crash.setVisibility(
                    entry.hasCrash
                            ? View.VISIBLE
                            : View.GONE
            );

            holder.export.setOnClickListener(
                    v -> requestExport(
                            entry.file
                    )
            );

            holder.delete.setEnabled(
                    !isCurrentRecordingFile(
                            entry.file
                    )
            );

            holder.delete.setOnClickListener(
                    v -> confirmDelete(entry)
            );

            return convertView;
        }
    }

    private static final class Holder {
        TextView title;
        TextView mode;
        TextView meta;
        TextView packages;
        TextView crash;
        MaterialButton export;
        MaterialButton delete;
    }

    private static final class LogEntry {
        final File file;
        final String mode;
        final String modeLabel;
        final String title;
        final String packages;
        final String packageSummary;
        final boolean hasCrash;
        final int partNumber;
        final String searchableText;

        LogEntry(
                File file,
                String mode,
                String modeLabel,
                String title,
                String packages,
                String packageSummary,
                boolean hasCrash,
                int partNumber,
                String searchableText
        ) {
            this.file = file;
            this.mode = mode;
            this.modeLabel = modeLabel;
            this.title = title;
            this.packages = packages;
            this.packageSummary = packageSummary;
            this.hasCrash = hasCrash;
            this.partNumber = partNumber;
            this.searchableText = searchableText;
        }
    }
}
