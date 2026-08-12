package dev.shizulog.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FullLogActivity
        extends AppCompatActivity {

    public static final String EXTRA_FILE =
            "file";

    private static final long PAGE_BYTES =
            256L * 1024L;

    private static final float MIN_TEXT_SP =
            9f;

    private static final float MAX_TEXT_SP =
            22f;

    private TextView fileMeta;
    private TextView pageText;
    private TextView pageIndicator;
    private TextView searchStatus;

    private MaterialButton firstButton;
    private MaterialButton prevButton;
    private MaterialButton nextButton;
    private MaterialButton lastButton;
    private MaterialButton refreshButton;

    private MaterialButton searchButton;
    private MaterialButton previousMatchButton;
    private MaterialButton nextMatchButton;
    private MaterialButton errorButton;
    private MaterialButton copyBlockButton;
    private MaterialButton bottomButton;
    private MaterialButton smallerTextButton;
    private MaterialButton largerTextButton;
    private MaterialButton wrapButton;

    private TextInputEditText searchInput;
    private MaterialCheckBox regexCheck;

    private ScrollView verticalScroll;
    private HorizontalScrollView horizontalScroll;

    private File file;
    private int pageIndex;
    private int pageCount = 1;

    private float textSizeSp = 12f;
    private boolean wrapLines = true;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final List<LogSearchEngine.Match>
            searchMatches =
                    new ArrayList<>();

    private final List<LogSearchEngine.Match>
            errorMatches =
                    new ArrayList<>();

    private int currentMatchIndex = -1;
    private boolean errorIndexLoaded;
    private long searchGeneration;

    private long pageStartOffset;
    private long pageFirstLineNumber = 1L;
    private String rawPageContent = "";

    private Long pendingHighlightLine;
    private String pendingHighlightText;

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_full_log
        );

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(
                        R.id.fullLogRoot
                ),
                (view, insets) -> {
                    Insets bars =
                            insets.getInsets(
                                    WindowInsetsCompat
                                            .Type
                                            .systemBars()
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
                findViewById(
                        R.id.fullLogToolbar
                );

        toolbar.setNavigationIcon(
                R.drawable.ic_arrow_back_24
        );

        toolbar.setNavigationOnClickListener(
                v -> finish()
        );

        fileMeta =
                findViewById(
                        R.id.fullLogMeta
                );

        pageText =
                findViewById(
                        R.id.fullLogText
                );

        pageIndicator =
                findViewById(
                        R.id.fullLogPageIndicator
                );

        searchStatus =
                findViewById(
                        R.id.fullLogSearchStatus
                );

        firstButton =
                findViewById(
                        R.id.fullLogFirst
                );

        prevButton =
                findViewById(
                        R.id.fullLogPrev
                );

        nextButton =
                findViewById(
                        R.id.fullLogNext
                );

        lastButton =
                findViewById(
                        R.id.fullLogLast
                );

        refreshButton =
                findViewById(
                        R.id.fullLogRefresh
                );

        searchButton =
                findViewById(
                        R.id.fullLogSearchButton
                );

        previousMatchButton =
                findViewById(
                        R.id.fullLogPrevMatch
                );

        nextMatchButton =
                findViewById(
                        R.id.fullLogNextMatch
                );

        errorButton =
                findViewById(
                        R.id.fullLogNextError
                );

        copyBlockButton =
                findViewById(
                        R.id.fullLogCopyBlock
                );

        bottomButton =
                findViewById(
                        R.id.fullLogBottom
                );

        smallerTextButton =
                findViewById(
                        R.id.fullLogTextSmaller
                );

        largerTextButton =
                findViewById(
                        R.id.fullLogTextLarger
                );

        wrapButton =
                findViewById(
                        R.id.fullLogWrap
                );

        searchInput =
                findViewById(
                        R.id.fullLogSearchInput
                );

        regexCheck =
                findViewById(
                        R.id.fullLogRegex
                );

        verticalScroll =
                findViewById(
                        R.id.fullLogScroll
                );

        horizontalScroll =
                findViewById(
                        R.id.fullLogHorizontalScroll
                );

        String path =
                getIntent()
                        .getStringExtra(
                                EXTRA_FILE
                        );

        if (!isSafeLogPath(path)) {
            Toast.makeText(
                    this,
                    "日志文件无效",
                    Toast.LENGTH_SHORT
            ).show();

            finish();
            return;
        }

        file = new File(path);

        toolbar.setTitle(
                file.getName()
        );

        recalculatePageCount();
        pageIndex = pageCount - 1;

        firstButton.setOnClickListener(
                v -> loadPage(0)
        );

        prevButton.setOnClickListener(
                v -> loadPage(
                        pageIndex - 1
                )
        );

        nextButton.setOnClickListener(
                v -> loadPage(
                        pageIndex + 1
                )
        );

        lastButton.setOnClickListener(
                v -> {
                    recalculatePageCount();

                    loadPage(
                            pageCount - 1
                    );
                }
        );

        refreshButton.setOnClickListener(
                v -> refreshGrowingFile()
        );

        searchButton.setOnClickListener(
                v -> runWholeFileSearch()
        );

        previousMatchButton.setOnClickListener(
                v -> jumpRelativeMatch(-1)
        );

        nextMatchButton.setOnClickListener(
                v -> jumpRelativeMatch(1)
        );

        errorButton.setOnClickListener(
                v -> jumpNextError()
        );

        copyBlockButton.setOnClickListener(
                v -> copyCurrentMatchContext()
        );

        bottomButton.setOnClickListener(
                v -> jumpToBottom()
        );

        smallerTextButton.setOnClickListener(
                v -> changeTextSize(-1f)
        );

        largerTextButton.setOnClickListener(
                v -> changeTextSize(1f)
        );

        wrapButton.setOnClickListener(
                v -> {
                    wrapLines = !wrapLines;
                    applyWrapMode();
                }
        );

        searchInput.setOnEditorActionListener(
                (v, actionId, event) -> {
                    runWholeFileSearch();
                    return true;
                }
        );

        updateSearchButtons();
        applyTextSize();
        applyWrapMode();
        loadPage(pageIndex);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (file != null
                && file.isFile()) {

            boolean wasLast =
                    pageIndex
                            >= pageCount - 1;

            recalculatePageCount();

            if (wasLast) {
                pageIndex =
                        pageCount - 1;
            } else {
                pageIndex =
                        Math.min(
                                pageIndex,
                                pageCount - 1
                        );
            }

            loadPage(pageIndex);
        }
    }

    private void runWholeFileSearch() {
        String query =
                searchInput.getText() == null
                        ? ""
                        : searchInput
                                .getText()
                                .toString();

        if (query.trim().isEmpty()) {
            toast("请输入搜索内容");
            return;
        }

        final long generation =
                ++searchGeneration;

        searchStatus.setText(
                "正在搜索整份日志…"
        );

        searchButton.setEnabled(false);

        executor.execute(() -> {
            LogSearchEngine.SearchResult result;

            try {
                result =
                        LogSearchEngine.search(
                                file,
                                query,
                                regexCheck.isChecked()
                        );
            } catch (Exception e) {
                result =
                        LogSearchEngine.SearchResult
                                .error(
                                        e.getMessage() == null
                                                ? e.getClass()
                                                        .getSimpleName()
                                                : e.getMessage()
                                );
            }

            final LogSearchEngine.SearchResult
                    finalResult = result;

            runOnUiThread(() -> {
                if (isFinishing()
                        || isDestroyed()
                        || generation
                                != searchGeneration) {
                    return;
                }

                searchButton.setEnabled(true);

                searchMatches.clear();
                currentMatchIndex = -1;

                if (!finalResult.success) {
                    searchStatus.setText(
                            finalResult.error
                    );

                    updateSearchButtons();
                    return;
                }

                searchMatches.addAll(
                        finalResult.matches
                );

                if (searchMatches.isEmpty()) {
                    searchStatus.setText(
                            "未找到匹配项"
                    );

                    updateSearchButtons();
                    return;
                }

                searchStatus.setText(
                        searchMatches.size()
                                + " 个匹配"
                                + (finalResult.truncated
                                ? "（已达到 5000 条上限）"
                                : "")
                );

                currentMatchIndex = 0;
                jumpToMatch(
                        searchMatches.get(0)
                );
                updateSearchButtons();
            });
        });
    }

    private void jumpRelativeMatch(
            int delta
    ) {
        if (searchMatches.isEmpty()) {
            toast("请先搜索");
            return;
        }

        int size =
                searchMatches.size();

        if (currentMatchIndex < 0) {
            currentMatchIndex = 0;
        } else {
            currentMatchIndex =
                    (currentMatchIndex
                            + delta
                            + size)
                            % size;
        }

        LogSearchEngine.Match match =
                searchMatches.get(
                        currentMatchIndex
                );

        searchStatus.setText(
                "第 "
                        + (currentMatchIndex + 1)
                        + " / "
                        + size
                        + " 个匹配 · 行 "
                        + match.lineNumber
        );

        jumpToMatch(match);
        updateSearchButtons();
    }

    private void jumpNextError() {
        if (errorIndexLoaded) {
            jumpToNextErrorFromCurrentPage();
            return;
        }

        errorButton.setEnabled(false);
        searchStatus.setText(
                "正在建立 ERROR 索引…"
        );

        executor.execute(() -> {
            LogSearchEngine.SearchResult result;

            try {
                result =
                        LogSearchEngine.findErrors(
                                file
                        );
            } catch (Exception e) {
                result =
                        LogSearchEngine.SearchResult
                                .error(
                                        e.getMessage() == null
                                                ? e.getClass()
                                                        .getSimpleName()
                                                : e.getMessage()
                                );
            }

            final LogSearchEngine.SearchResult
                    finalResult = result;

            runOnUiThread(() -> {
                errorButton.setEnabled(true);

                if (!finalResult.success) {
                    searchStatus.setText(
                            finalResult.error
                    );
                    return;
                }

                errorMatches.clear();
                errorMatches.addAll(
                        finalResult.matches
                );

                errorIndexLoaded = true;

                if (errorMatches.isEmpty()) {
                    searchStatus.setText(
                            "未检测到 ERROR / 崩溃标记"
                    );
                    return;
                }

                jumpToNextErrorFromCurrentPage();
            });
        });
    }

    private void jumpToNextErrorFromCurrentPage() {
        if (errorMatches.isEmpty()) {
            toast("没有检测到 ERROR");
            return;
        }

        long currentOffset =
                pageStartOffset;

        LogSearchEngine.Match target =
                null;

        for (LogSearchEngine.Match match :
                errorMatches) {
            if (match.byteOffset
                    > currentOffset) {
                target = match;
                break;
            }
        }

        if (target == null) {
            target = errorMatches.get(0);
        }

        searchStatus.setText(
                "ERROR · 行 "
                        + target.lineNumber
        );

        jumpToMatch(target);
    }

    private void jumpToMatch(
            LogSearchEngine.Match match
    ) {
        if (match == null) {
            return;
        }

        int targetPage =
                (int) Math.min(
                        Integer.MAX_VALUE,
                        match.byteOffset
                                / PAGE_BYTES
                );

        pendingHighlightLine =
                match.lineNumber;

        pendingHighlightText =
                match.line;

        loadPage(targetPage);
    }

    private void copyCurrentMatchContext() {
        LogSearchEngine.Match match =
                getCurrentMatch();

        if (match == null) {
            toast("请先搜索并选择一个匹配项");
            return;
        }

        copyBlockButton.setEnabled(false);

        executor.execute(() -> {
            try {
                String context =
                        LogSearchEngine.readContext(
                                file,
                                match.byteOffset,
                                8,
                                24
                        );

                runOnUiThread(() -> {
                    copyBlockButton.setEnabled(true);

                    ClipboardManager clipboard =
                            (ClipboardManager)
                                    getSystemService(
                                            CLIPBOARD_SERVICE
                                    );

                    clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                    "ShizuLog 日志块",
                                    context
                            )
                    );

                    toast(
                            "已复制匹配位置附近的日志块"
                    );
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    copyBlockButton.setEnabled(true);

                    toast(
                            "复制失败："
                                    + (e.getMessage() == null
                                    ? e.getClass()
                                            .getSimpleName()
                                    : e.getMessage())
                    );
                });
            }
        });
    }

    private LogSearchEngine.Match
            getCurrentMatch() {
        if (searchMatches.isEmpty()
                || currentMatchIndex < 0
                || currentMatchIndex
                        >= searchMatches.size()) {
            return null;
        }

        return searchMatches.get(
                currentMatchIndex
        );
    }

    private void updateSearchButtons() {
        boolean hasMatch =
                !searchMatches.isEmpty();

        previousMatchButton.setEnabled(
                hasMatch
        );

        nextMatchButton.setEnabled(
                hasMatch
        );

        copyBlockButton.setEnabled(
                hasMatch
        );
    }

    private void changeTextSize(
            float delta
    ) {
        textSizeSp =
                Math.max(
                        MIN_TEXT_SP,
                        Math.min(
                                MAX_TEXT_SP,
                                textSizeSp + delta
                        )
                );

        applyTextSize();
    }

    private void applyTextSize() {
        pageText.setTextSize(
                textSizeSp
        );

        smallerTextButton.setEnabled(
                textSizeSp > MIN_TEXT_SP
        );

        largerTextButton.setEnabled(
                textSizeSp < MAX_TEXT_SP
        );
    }

    private void applyWrapMode() {
        wrapButton.setText(
                wrapLines
                        ? "自动换行：开"
                        : "自动换行：关"
        );

        horizontalScroll.setFillViewport(
                wrapLines
        );

        ViewGroup.LayoutParams params =
                pageText.getLayoutParams();

        params.width =
                wrapLines
                        ? ViewGroup.LayoutParams
                                .MATCH_PARENT
                        : ViewGroup.LayoutParams
                                .WRAP_CONTENT;

        pageText.setLayoutParams(params);

        pageText.setHorizontallyScrolling(
                !wrapLines
        );

        pageText.requestLayout();
    }

    private void jumpToBottom() {
        recalculatePageCount();

        pendingHighlightLine = null;
        pendingHighlightText = null;

        loadPage(
                pageCount - 1
        );

        verticalScroll.post(
                () -> verticalScroll
                        .fullScroll(
                                View.FOCUS_DOWN
                        )
        );
    }

    private void refreshGrowingFile() {
        if (file == null
                || !file.isFile()) {
            return;
        }

        boolean wasLast =
                pageIndex
                        >= pageCount - 1;

        int oldCount =
                pageCount;

        recalculatePageCount();

        errorIndexLoaded = false;
        errorMatches.clear();

        if (wasLast) {
            pageIndex =
                    pageCount - 1;
        } else {
            pageIndex =
                    Math.min(
                            pageIndex,
                            pageCount - 1
                    );
        }

        loadPage(pageIndex);

        if (pageCount > oldCount) {
            toast("发现新的日志页");
        }
    }

    private void recalculatePageCount() {
        if (file == null
                || !file.isFile()) {
            pageCount = 1;
            return;
        }

        pageCount =
                Math.max(
                        1,
                        (int) (
                                (file.length()
                                        + PAGE_BYTES
                                        - 1)
                                        / PAGE_BYTES
                        )
                );
    }

    private void loadPage(
            int requestedPage
    ) {
        if (file == null) {
            return;
        }

        recalculatePageCount();

        pageIndex =
                Math.max(
                        0,
                        Math.min(
                                requestedPage,
                                pageCount - 1
                        )
                );

        final int requested =
                pageIndex;

        pageText.setText(
                "正在读取第 "
                        + (requested + 1)
                        + " 页…"
        );

        executor.execute(() -> {
            try {
                PageData data =
                        readPage(
                                file,
                                requested
                        );

                runOnUiThread(() -> {
                    if (isFinishing()
                            || isDestroyed()
                            || requested
                                    != pageIndex) {
                        return;
                    }

                    pageStartOffset =
                            data.startOffset;

                    pageFirstLineNumber =
                            data.firstLineNumber;

                    rawPageContent =
                            data.content;

                    renderPage(data);

                    updateMeta();

                    pageIndicator.setText(
                            "第 "
                                    + (pageIndex + 1)
                                    + " / "
                                    + pageCount
                                    + " 页"
                                    + " · 行 "
                                    + data.firstLineNumber
                                    + "–"
                                    + data.lastLineNumber
                    );

                    firstButton.setEnabled(
                            pageIndex > 0
                    );

                    prevButton.setEnabled(
                            pageIndex > 0
                    );

                    nextButton.setEnabled(
                            pageIndex
                                    < pageCount - 1
                    );

                    lastButton.setEnabled(
                            pageIndex
                                    < pageCount - 1
                    );
                });
            } catch (Exception e) {
                runOnUiThread(
                        () -> pageText.setText(
                                "读取失败："
                                        + (e.getMessage()
                                                == null
                                        ? e.getClass()
                                                .getSimpleName()
                                        : e.getMessage())
                        )
                );
            }
        });
    }

    private void renderPage(
            PageData data
    ) {
        NumberedPage numbered =
                numberLines(
                        data.content,
                        data.firstLineNumber
                );

        SpannableString spannable =
                new SpannableString(
                        numbered.text
                );

        int highlightStart = -1;
        int highlightEnd = -1;

        if (pendingHighlightLine != null) {
            int index =
                    (int) (
                            pendingHighlightLine
                                    - data.firstLineNumber
                    );

            if (index >= 0
                    && index
                            < numbered.lineStarts
                                    .size()) {

                highlightStart =
                        numbered.lineStarts
                                .get(index);

                highlightEnd =
                        index + 1
                                < numbered.lineStarts
                                        .size()
                                ? numbered.lineStarts
                                        .get(index + 1)
                                : numbered.text
                                        .length();

                if (highlightEnd
                        > highlightStart) {
                    spannable.setSpan(
                            new BackgroundColorSpan(
                                    getColor(
                                            R.color
                                                    .md_theme_primaryContainer
                                    )
                            ),
                            highlightStart,
                            highlightEnd,
                            Spanned
                                    .SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
            }
        }

        pageText.setText(spannable);
        pageText.setTypeface(
                Typeface.MONOSPACE
        );

        if (highlightStart >= 0) {
            final int targetOffset =
                    highlightStart;

            pageText.post(() -> {
                if (pageText.getLayout()
                        == null) {
                    return;
                }

                int line =
                        pageText.getLayout()
                                .getLineForOffset(
                                        targetOffset
                                );

                int y =
                        pageText.getLayout()
                                .getLineTop(line);

                verticalScroll.scrollTo(
                        0,
                        Math.max(
                                0,
                                y - 60
                        )
                );
            });
        } else {
            verticalScroll.post(
                    () -> verticalScroll
                            .scrollTo(
                                    0,
                                    0
                            )
            );
        }
    }

    private NumberedPage numberLines(
            String content,
            long firstLine
    ) {
        String[] lines =
                content.split(
                        "\\n",
                        -1
                );

        StringBuilder out =
                new StringBuilder();

        List<Integer> starts =
                new ArrayList<>();

        long lineNumber =
                firstLine;

        for (int i = 0;
             i < lines.length;
             i++) {

            starts.add(
                    out.length()
            );

            out.append(
                    String.format(
                            Locale.US,
                            "%8d | ",
                            lineNumber++
                    )
            );

            out.append(
                    lines[i]
            );

            if (i
                    < lines.length - 1) {
                out.append('\n');
            }
        }

        return new NumberedPage(
                out.toString(),
                starts
        );
    }

    private void updateMeta() {
        String date =
                DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT
                ).format(
                        new Date(
                                file.lastModified()
                        )
                );

        boolean active =
                isCurrentRecordingFile();

        fileMeta.setText(
                "文件大小："
                        + humanSize(
                                file.length()
                        )
                        + " · 每页约 256 KB"
                        + " · 字号 "
                        + (int) textSizeSp
                        + "sp"
                        + " · 更新 "
                        + date
                        + (active
                        ? " · ● 正在写入"
                        : "")
        );
    }

    private boolean isCurrentRecordingFile() {
        try {
            String currentPath =
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
                    || currentPath == null
                    || currentPath.isEmpty()) {
                return false;
            }

            return file.getCanonicalPath()
                    .equals(
                            new File(
                                    currentPath
                            ).getCanonicalPath()
                    );
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isSafeLogPath(
            String path
    ) {
        if (path == null
                || path.isEmpty()) {
            return false;
        }

        try {
            File externalRoot =
                    getExternalFilesDir(null);

            if (externalRoot == null) {
                return false;
            }

            File requested =
                    new File(path)
                            .getCanonicalFile();

            File root =
                    externalRoot
                            .getCanonicalFile();

            return requested.isFile()
                    && requested.getName()
                            .toLowerCase(
                                    Locale.ROOT
                            )
                            .endsWith(".log")
                    && requested.getPath()
                            .startsWith(
                                    root.getPath()
                                            + File.separator
                            );
        } catch (Exception e) {
            return false;
        }
    }

    private static PageData readPage(
            File file,
            int page
    ) throws Exception {

        long nominalStart =
                page * PAGE_BYTES;

        long nominalEnd =
                Math.min(
                        file.length(),
                        nominalStart
                                + PAGE_BYTES
                );

        try (RandomAccessFile raf =
                     new RandomAccessFile(
                             file,
                             "r"
                     )) {

            long start =
                    nominalStart;

            if (start > 0) {
                raf.seek(start);

                int b;

                while ((b = raf.read())
                        != -1) {
                    start++;

                    if (b == '\n') {
                        break;
                    }
                }
            }

            long end =
                    nominalEnd;

            if (end < file.length()) {
                raf.seek(end);

                int b;

                while ((b = raf.read())
                        != -1) {
                    end++;

                    if (b == '\n') {
                        break;
                    }
                }
            }

            if (end < start) {
                end = start;
            }

            int length =
                    (int) Math.min(
                            Integer.MAX_VALUE,
                            end - start
                    );

            byte[] data =
                    new byte[length];

            raf.seek(start);
            raf.readFully(data);

            String content =
                    new String(
                            data,
                            StandardCharsets.UTF_8
                    );

            long firstLine =
                    LogSearchEngine
                            .countLinesBefore(
                                    file,
                                    start
                            )
                            + 1L;

            long lineCount =
                    countLines(content);

            long lastLine =
                    firstLine
                            + Math.max(
                                    0L,
                                    lineCount - 1L
                            );

            return new PageData(
                    start,
                    end,
                    firstLine,
                    lastLine,
                    content
            );
        }
    }

    private static long countLines(
            String content
    ) {
        if (content == null
                || content.isEmpty()) {
            return 1L;
        }

        long count = 1L;

        for (int i = 0;
             i < content.length();
             i++) {
            if (content.charAt(i)
                    == '\n') {
                count++;
            }
        }

        return count;
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

    private void toast(
            String text
    ) {
        Toast.makeText(
                this,
                text,
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    protected void onDestroy() {
        ++searchGeneration;
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class PageData {
        final long startOffset;
        final long endOffset;
        final long firstLineNumber;
        final long lastLineNumber;
        final String content;

        PageData(
                long startOffset,
                long endOffset,
                long firstLineNumber,
                long lastLineNumber,
                String content
        ) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.firstLineNumber =
                    firstLineNumber;
            this.lastLineNumber =
                    lastLineNumber;
            this.content = content;
        }
    }

    private static final class NumberedPage {
        final String text;
        final List<Integer> lineStarts;

        NumberedPage(
                String text,
                List<Integer> lineStarts
        ) {
            this.text = text;
            this.lineStarts = lineStarts;
        }
    }
}
