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
                    getString(R.string.no_available_log)
            );

            generate.setEnabled(false);
        } else {
            state.setText(
                    getString(R.string.diagnostic_pack_prepare)
            );

            info.setText(
                    getString(
                            R.string.diagnostic_log_info,
                            logFile.getName(),
                            humanSize(logFile.length()),
                            getString(R.string.diagnostic_default_privacy_info)
                    )
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
            toast(getString(R.string.log_file_not_found));
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
                            getString(R.string.diagnostic_generation_failed)
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
                                ? getString(R.string.sanitized_pack_generated)
                                : getString(R.string.raw_pack_generated)
                );

                info.setText(
                        getString(
                                R.string.diagnostic_generated_info,
                                generatedZip.getName(),
                                humanSize(generatedZip.length()),
                                result.redacted
                                        ? getString(R.string.sanitized_mode_recommended)
                                        : getString(R.string.raw_mode),
                                getString(R.string.diagnostic_zip_manifest_info)
                        )
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
            toast(getString(R.string.pack_not_generated));
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
                            getString(R.string.share_diagnostic_pack)
                    )
            );
        } catch (Exception e) {
            toast(getString(R.string.share_failed, e.getMessage()));
        }
    }

    private void requestSaveCopy() {
        if (generatedZip == null
                || !generatedZip.isFile()) {
            toast(getString(R.string.pack_not_generated));
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
                            getString(R.string.cannot_open_save_location)
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
                        () -> toast(getString(R.string.diagnostic_copy_saved))
                );
            } catch (Exception e) {
                runOnUiThread(
                        () -> toast(getString(R.string.save_failed, e.getMessage()))
                );
            }
        });
    }

    private void deletePack() {
        if (generatedZip == null
                || !generatedZip.isFile()) {
            toast(getString(R.string.no_pack_to_delete));
            return;
        }

        if (generatedZip.delete()) {
            generatedZip = null;

            state.setText(
                    getString(R.string.diagnostic_pack_deleted)
            );

            setOutputButtonsEnabled(
                    false
            );

            toast(getString(R.string.deleted));
        } else {
            toast(getString(R.string.delete_failed));
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
