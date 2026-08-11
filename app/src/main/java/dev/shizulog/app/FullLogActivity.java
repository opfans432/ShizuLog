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
import java.util.Locale;

public class FullLogActivity extends AppCompatActivity {

    public static final String EXTRA_FILE = "file";
    private static final long PAGE_BYTES = 256L * 1024L;

    private TextView fileMeta;
    private TextView pageText;
    private TextView pageIndicator;
    private MaterialButton firstButton;
    private MaterialButton prevButton;
    private MaterialButton nextButton;
    private MaterialButton lastButton;
    private ScrollView scroll;

    private File file;
    private int pageIndex;
    private int pageCount = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_log);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.fullLogRoot),
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

        MaterialToolbar toolbar = findViewById(R.id.fullLogToolbar);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24);
        toolbar.setNavigationOnClickListener(v -> finish());

        fileMeta = findViewById(R.id.fullLogMeta);
        pageText = findViewById(R.id.fullLogText);
        pageIndicator = findViewById(R.id.fullLogPageIndicator);
        firstButton = findViewById(R.id.fullLogFirst);
        prevButton = findViewById(R.id.fullLogPrev);
        nextButton = findViewById(R.id.fullLogNext);
        lastButton = findViewById(R.id.fullLogLast);
        scroll = findViewById(R.id.fullLogScroll);

        String path = getIntent().getStringExtra(EXTRA_FILE);
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

        pageCount = Math.max(
                1,
                (int) (
                        (file.length() + PAGE_BYTES - 1)
                                / PAGE_BYTES
                )
        );

        pageIndex = pageCount - 1;
        toolbar.setTitle(file.getName());

        firstButton.setOnClickListener(v -> loadPage(0));
        prevButton.setOnClickListener(v -> loadPage(pageIndex - 1));
        nextButton.setOnClickListener(v -> loadPage(pageIndex + 1));
        lastButton.setOnClickListener(v -> loadPage(pageCount - 1));

        loadPage(pageIndex);
    }

    private boolean isSafeLogPath(String path) {
        if (path == null || path.isEmpty()) return false;

        try {
            File externalRoot = getExternalFilesDir(null);
            if (externalRoot == null) return false;

            File requested = new File(path).getCanonicalFile();
            File root = externalRoot.getCanonicalFile();

            return requested.isFile()
                    && requested.getName()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".log")
                    && requested.getPath().startsWith(
                            root.getPath() + File.separator
                    );
        } catch (Exception e) {
            return false;
        }
    }

    private void loadPage(int requestedPage) {
        if (file == null) return;

        pageIndex = Math.max(
                0,
                Math.min(requestedPage, pageCount - 1)
        );

        try {
            String content = readPage(file, pageIndex);

            pageText.setText(
                    content.isEmpty()
                            ? "（这一页没有内容）"
                            : content
            );

            fileMeta.setText(
                    "文件大小："
                            + humanSize(file.length())
                            + " · 每页约 256 KB"
            );

            pageIndicator.setText(
                    "第 "
                            + (pageIndex + 1)
                            + " / "
                            + pageCount
                            + " 页"
            );

            firstButton.setEnabled(pageIndex > 0);
            prevButton.setEnabled(pageIndex > 0);
            nextButton.setEnabled(pageIndex < pageCount - 1);
            lastButton.setEnabled(pageIndex < pageCount - 1);

            scroll.post(() -> scroll.scrollTo(0, 0));
        } catch (Exception e) {
            pageText.setText(
                    "读取失败：" + e.getMessage()
            );
        }
    }

    private static String readPage(File file, int page)
            throws Exception {

        long nominalStart = page * PAGE_BYTES;
        long nominalEnd = Math.min(
                file.length(),
                nominalStart + PAGE_BYTES
        );

        try (RandomAccessFile raf =
                     new RandomAccessFile(file, "r")) {

            long start = nominalStart;

            if (start > 0) {
                raf.seek(start);
                int b;

                while ((b = raf.read()) != -1) {
                    start++;

                    if (b == '\n') {
                        break;
                    }
                }
            }

            long end = nominalEnd;

            if (end < file.length()) {
                raf.seek(end);
                int b;

                while ((b = raf.read()) != -1) {
                    end++;

                    if (b == '\n') {
                        break;
                    }
                }
            }

            if (end < start) {
                end = start;
            }

            int length = (int) Math.min(
                    Integer.MAX_VALUE,
                    end - start
            );

            byte[] data = new byte[length];

            raf.seek(start);
            raf.readFully(data);

            return new String(
                    data,
                    StandardCharsets.UTF_8
            );
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        double kb = bytes / 1024.0;

        if (kb < 1024) {
            return String.format(
                    Locale.US,
                    "%.1f KB",
                    kb
            );
        }

        return String.format(
                Locale.US,
                "%.2f MB",
                kb / 1024.0
        );
    }
}
