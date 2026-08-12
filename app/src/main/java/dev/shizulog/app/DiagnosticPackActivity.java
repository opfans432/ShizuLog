package dev.shizulog.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.radiobutton.MaterialRadioButton;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DiagnosticPackActivity
        extends AppCompatActivity {

    public static final String EXTRA_FILE =
            "file";

    private static final int REQ_SAVE_COPY =
            6201;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private ProgressBar progress;
    private TextView state;
    private TextView info;

    private MaterialRadioButton redactedMode;
    private MaterialRadioButton rawMode;

    private MaterialCheckBox includeCrash;
    private MaterialCheckBox includeDevice;
    private MaterialCheckBox includeTargets;

    private MaterialButton generate;
    private MaterialButton share;
    private MaterialButton saveCopy;
    private MaterialButton delete;

    private File logFile;
    private File generatedZip;

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_diagnostic_pack
        );

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(
                        R.id.diagnosticRoot
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
                        R.id.diagnosticToolbar
                );

        toolbar.setNavigationIcon(
                R.drawable.ic_arrow_back_24
        );

        toolbar.setNavigationOnClickListener(
                v -> finish()
        );

        progress =
                findViewById(
                        R.id.diagnosticProgress
                );

        state =
                findViewById(
                        R.id.diagnosticState
                );

        info =
                findViewById(
                        R.id.diagnosticInfo
                );

        redactedMode =
                findViewById(
                        R.id.diagnosticRedacted
                );

        rawMode =
                findViewById(
                        R.id.diagnosticRaw
                );

        includeCrash =
                findViewById(
                        R.id.diagnosticIncludeCrash
                );

        includeDevice =
                findViewById(
                        R.id.diagnosticIncludeDevice
                );

        includeTargets =
                findViewById(
                        R.id.diagnosticIncludeTargets
                );

        generate =
                findViewById(
                        R.id.diagnosticGenerate
                );

        share =
                findViewById(
                        R.id.diagnosticShare
                );

        saveCopy =
                findViewById(
                        R.id.diagnosticSaveCopy
                );

        delete =
                findViewById(
                        R.id.diagnosticDelete
                );

        String path =
                getIntent()
                        .getStringExtra(
                                EXTRA_FILE
                        );

        if (path != null) {
            File candidate =
                    new File(path);

            if (candidate.isFile()) {
                logFile = candidate;
            }
        }

        redactedMode.setChecked(true);
        includeCrash.setChecked(true);
        includeDevice.setChecked(true);
        includeTargets.setChecked(true);

        redactedMode.setOnClickListener(
                v -> {
                    redactedMode.setChecked(true);
                    rawMode.setChecked(false);
                }
        );

        rawMode.setOnClickListener(
                v -> {
                    rawMode.setChecked(true);
                    redactedMode.setChecked(false);
                }
        );

        if (logFile == null) {
            state.setText(
                    "没有可用的日志"
            );

            generate.setEnabled(false);
        } else {
            state.setText(
                    "准备生成诊断包"
            );

            info.setText(
                    "日志："
                            + logFile.getName()
                            + "\n大小："
                            + humanSize(
                                    logFile.length()
                            )
                            + "\n\n默认使用脱敏版。历史日志也会优先从日志文件头恢复当时的记录模式和目标 App。"
            );
        }

        setOutputButtonsEnabled(false);

        generate.setOnClickListener(
                v -> generatePack()
        );

        share.setOnClickListener(
                v -> sharePack()
        );

        saveCopy.setOnClickListener(
                v -> requestSaveCopy()
        );

        delete.setOnClickListener(
                v -> deletePack()
        );
    }

    private void generatePack() {
        if (logFile == null
                || !logFile.isFile()) {
            toast("日志不存在");
            return;
        }

        final boolean redact =
                !rawMode.isChecked();

        DiagnosticPackExporter.Options options =
                new DiagnosticPackExporter.Options(
                        redact,
                        includeCrash.isChecked(),
                        includeDevice.isChecked(),
                        includeTargets.isChecked()
                );

        generate.setEnabled(false);
        setOutputButtonsEnabled(false);

        progress.setVisibility(
                View.VISIBLE
        );

        executor.execute(() -> {
            DiagnosticPackExporter.Result result =
                    DiagnosticPackExporter.export(
                            this,
                            logFile,
                            options,
                            message ->
                                    runOnUiThread(
                                            () -> state
                                                    .setText(
                                                            message
                                                    )
                                    )
                    );

            runOnUiThread(() -> {
                progress.setVisibility(
                        View.GONE
                );

                generate.setEnabled(true);

                if (!result.success) {
                    state.setText(
                            "生成失败"
                    );

                    toast(
                            result.error
                    );

                    return;
                }

                generatedZip =
                        result.file;

                state.setText(
                        result.redacted
                                ? "脱敏诊断包已生成"
                                : "原始诊断包已生成"
                );

                info.setText(
                        "文件："
                                + generatedZip
                                        .getName()
                                + "\n大小："
                                + humanSize(
                                        generatedZip
                                                .length()
                                )
                                + "\n模式："
                                + (result.redacted
                                ? "脱敏版"
                                : "原始版")
                                + "\n\nZIP 内含 manifest-sha256.txt，可校验诊断包内部文件。"
                );

                setOutputButtonsEnabled(
                        true
                );
            });
        });
    }

    private void setOutputButtonsEnabled(
            boolean enabled
    ) {
        share.setEnabled(enabled);
        saveCopy.setEnabled(enabled);
        delete.setEnabled(enabled);
    }

    private void sharePack() {
        if (generatedZip == null
                || !generatedZip.isFile()) {
            toast("请先生成诊断包");
            return;
        }

        try {
            Uri uri =
                    FileProvider.getUriForFile(
                            this,
                            getPackageName()
                                    + ".fileprovider",
                            generatedZip
                    );

            Intent intent =
                    new Intent(
                            Intent.ACTION_SEND
                    );

            intent.setType(
                    "application/zip"
            );

            intent.putExtra(
                    Intent.EXTRA_STREAM,
                    uri
            );

            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );

            startActivity(
                    Intent.createChooser(
                            intent,
                            "分享 ShizuLog 诊断包"
                    )
            );
        } catch (Exception e) {
            toast(
                    "分享失败："
                            + e.getMessage()
            );
        }
    }

    private void requestSaveCopy() {
        if (generatedZip == null
                || !generatedZip.isFile()) {
            toast("请先生成诊断包");
            return;
        }

        Intent intent =
                new Intent(
                        Intent.ACTION_CREATE_DOCUMENT
                );

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType(
                "application/zip"
        );

        intent.putExtra(
                Intent.EXTRA_TITLE,
                generatedZip.getName()
        );

        startActivityForResult(
                intent,
                REQ_SAVE_COPY
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

        if (requestCode != REQ_SAVE_COPY
                || resultCode != RESULT_OK
                || data == null
                || generatedZip == null
                || !generatedZip.isFile()) {
            return;
        }

        Uri uri =
                data.getData();

        if (uri == null) {
            return;
        }

        executor.execute(() -> {
            try (FileInputStream in =
                         new FileInputStream(
                                 generatedZip
                         );
                 OutputStream out =
                         getContentResolver()
                                 .openOutputStream(
                                         uri,
                                         "w"
                                 )) {

                if (out == null) {
                    throw new IllegalStateException(
                            "无法打开保存位置"
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

                runOnUiThread(
                        () -> toast(
                                "诊断包副本已保存"
                        )
                );
            } catch (Exception e) {
                runOnUiThread(
                        () -> toast(
                                "保存失败："
                                        + e.getMessage()
                        )
                );
            }
        });
    }

    private void deletePack() {
        if (generatedZip == null
                || !generatedZip.isFile()) {
            toast("没有可删除的诊断包");
            return;
        }

        if (generatedZip.delete()) {
            generatedZip = null;

            state.setText(
                    "诊断包已删除"
            );

            setOutputButtonsEnabled(
                    false
            );

            toast("已删除");
        } else {
            toast("删除失败");
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
