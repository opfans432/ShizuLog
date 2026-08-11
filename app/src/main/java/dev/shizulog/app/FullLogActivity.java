package dev.shizulog.app;

import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public class FullLogActivity extends AppCompatActivity {

    public static final String EXTRA_FILE = "file";
    private static final long PAGE_BYTES =
            256L * 1024L;

    private TextView fileMeta;
    private TextView pageText;
    private TextView pageIndicator;

    private MaterialButton firstButton;
    private MaterialButton prevButton;
    private MaterialButton nextButton;
    private MaterialButton lastButton;
    private MaterialButton refreshButton;

    private ScrollView scroll;

    private File file;
    private int pageIndex;
    private int pageCount = 1;

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_log);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.fullLogRoot),
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
        scroll =
                findViewById(
                        R.id.fullLogScroll
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
                v -> {
                    recalculatePageCount();

                    loadPage(
                            pageIndex + 1
                    );
                }
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
            Toast.makeText(
                    this,
                    "发现新的日志页",
                    Toast.LENGTH_SHORT
            ).show();
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

        try {
            String content =
                    readPage(
                            file,
                            pageIndex
                    );

            pageText.setText(
                    content.isEmpty()
                            ? "（这一页没有内容）"
                            : content
            );

            updateMeta();

            pageIndicator.setText(
                    "第 "
                            + (pageIndex + 1)
                            + " / "
                            + pageCount
                            + " 页"
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

            scroll.post(
                    () -> scroll.scrollTo(
                            0,
                            0
                    )
            );
        } catch (Exception e) {
            pageText.setText(
                    "读取失败："
                            + e.getMessage()
            );
        }
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

    private static String readPage(
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

            return new String(
                    data,
                    StandardCharsets.UTF_8
            );
        }
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
}
