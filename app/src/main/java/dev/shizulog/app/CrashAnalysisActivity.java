package dev.shizulog.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CrashAnalysisActivity
        extends AppCompatActivity {

    public static final String EXTRA_FILE = "file";

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private ProgressBar progress;
    private View resultContainer;
    private TextView stateText;
    private TextView fileMeta;
    private TextView typeText;
    private TextView processText;
    private TextView threadText;
    private TextView exceptionText;
    private TextView causeText;
    private TextView keyLocationText;
    private TextView stackText;
    private MaterialButton copyButton;
    private MaterialButton fullLogButton;

    private File file;
    private CrashAnalyzer.Result result;

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);
        setContentView(
                R.layout.activity_crash_analysis
        );

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(
                        R.id.crashAnalysisRoot
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
                        R.id.crashAnalysisToolbar
                );

        toolbar.setNavigationIcon(
                R.drawable.ic_arrow_back_24
        );

        toolbar.setNavigationOnClickListener(
                v -> finish()
        );

        progress =
                findViewById(
                        R.id.crashAnalysisProgress
                );
        resultContainer =
                findViewById(
                        R.id.crashAnalysisResult
                );
        stateText =
                findViewById(
                        R.id.crashAnalysisState
                );
        fileMeta =
                findViewById(
                        R.id.crashAnalysisFileMeta
                );
        typeText =
                findViewById(
                        R.id.crashAnalysisType
                );
        processText =
                findViewById(
                        R.id.crashAnalysisProcess
                );
        threadText =
                findViewById(
                        R.id.crashAnalysisThread
                );
        exceptionText =
                findViewById(
                        R.id.crashAnalysisException
                );
        causeText =
                findViewById(
                        R.id.crashAnalysisCause
                );
        keyLocationText =
                findViewById(
                        R.id.crashAnalysisKeyLocation
                );
        stackText =
                findViewById(
                        R.id.crashAnalysisStack
                );
        copyButton =
                findViewById(
                        R.id.crashAnalysisCopy
                );
        fullLogButton =
                findViewById(
                        R.id.crashAnalysisFullLog
                );

        String path =
                getIntent()
                        .getStringExtra(
                                EXTRA_FILE
                        );

        if (!isSafeLogPath(path)) {
            toast(getString(R.string.invalid_log_file));
            finish();
            return;
        }

        file = new File(path);

        toolbar.setTitle(
                getString(R.string.crash_analysis_title)
        );

        updateFileMeta();

        copyButton.setEnabled(false);
        copyButton.setOnClickListener(
                v -> copySummary()
        );

        fullLogButton.setOnClickListener(
                v -> openFullLog()
        );

        analyzeAsync();
    }

    private void analyzeAsync() {
        progress.setVisibility(
                View.VISIBLE
        );

        resultContainer.setVisibility(
                View.GONE
        );

        stateText.setVisibility(
                View.VISIBLE
        );

        stateText.setText(
                getString(R.string.crash_context_analyzing)
        );

        executor.execute(() -> {
            CrashAnalyzer.Result parsed =
                    CrashAnalyzer.analyze(this, file);

            runOnUiThread(() -> {
                if (isFinishing()
                        || isDestroyed()) {
                    return;
                }

                result = parsed;

                progress.setVisibility(
                        View.GONE
                );

                resultContainer.setVisibility(
                        View.VISIBLE
                );

                stateText.setText(
                        parsed.detected
                                ? getString(R.string.crash_detected)
                                : getString(R.string.no_obvious_crash)
                );

                renderResult(parsed);
            });
        });
    }

    private void renderResult(
            CrashAnalyzer.Result parsed
    ) {
        typeText.setText(
                valueOrDash(parsed.type)
        );

        processText.setText(
                joinProcessPid(
                        parsed.process,
                        parsed.pid
                )
        );

        threadText.setText(
                valueOrDash(parsed.thread)
        );

        exceptionText.setText(
                valueOrDash(
                        parsed.exception
                )
        );

        causeText.setText(
                valueOrDash(parsed.cause)
        );

        keyLocationText.setText(
                valueOrDash(
                        parsed.keyLocation
                )
        );

        if (parsed.stackExcerpt == null
                || parsed.stackExcerpt
                        .trim()
                        .isEmpty()) {

            stackText.setText(
                    parsed.detected
                            ? getString(R.string.crash_stack_missing)
                            : getString(R.string.crash_no_markers)
            );
        } else {
            stackText.setText(
                    parsed.stackExcerpt
            );
        }

        copyButton.setEnabled(
                parsed.summary != null
                        && !parsed.summary
                                .isEmpty()
        );
    }

    private void updateFileMeta() {
        String date =
                DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT
                ).format(
                        new Date(
                                file.lastModified()
                        )
                );

        fileMeta.setText(
                file.getName()
                        + "\n"
                        + humanSize(
                                file.length()
                        )
                        + " · "
                        + date
        );
    }

    private void copySummary() {
        if (result == null
                || result.summary == null
                || result.summary.isEmpty()) {
            return;
        }

        ClipboardManager clipboard =
                (ClipboardManager)
                        getSystemService(
                                CLIPBOARD_SERVICE
                        );

        clipboard.setPrimaryClip(
                ClipData.newPlainText(
                        getString(R.string.crash_summary_clipboard),
                        result.summary
                )
        );

        toast(getString(R.string.crash_summary_copied));
    }

    private void openFullLog() {
        if (file == null
                || !file.isFile()) {
            toast(getString(R.string.log_file_not_found));
            return;
        }

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

    private static String joinProcessPid(
            String process,
            String pid
    ) {
        boolean hasProcess =
                process != null
                        && !process
                                .trim()
                                .isEmpty();

        boolean hasPid =
                pid != null
                        && !pid
                                .trim()
                                .isEmpty();

        if (hasProcess && hasPid) {
            return process.trim()
                    + " · PID "
                    + pid.trim();
        }

        if (hasProcess) {
            return process.trim();
        }

        if (hasPid) {
            return "PID " + pid.trim();
        }

        return "—";
    }

    private static String valueOrDash(
            String value
    ) {
        return value == null
                || value.trim().isEmpty()
                ? "—"
                : value.trim();
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

    private void toast(String text) {
        Toast.makeText(
                this,
                text,
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
