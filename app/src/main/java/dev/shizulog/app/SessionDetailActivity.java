package dev.shizulog.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SessionDetailActivity
        extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID =
            "session_id";

    private String sessionId;
    private CaptureSessionManager.Session
            session;

    private TextView title;
    private TextView state;
    private TextView details;

    private MaterialButton openLog;
    private MaterialButton analyze;
    private MaterialButton diagnostic;
    private MaterialButton rename;
    private MaterialButton delete;

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_session_detail
        );

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(
                        R.id.sessionDetailRoot
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
                        R.id.sessionDetailToolbar
                );

        toolbar.setNavigationIcon(
                R.drawable.ic_arrow_back_24
        );

        toolbar.setNavigationOnClickListener(
                v -> finish()
        );

        title =
                findViewById(
                        R.id.sessionDetailTitle
                );

        state =
                findViewById(
                        R.id.sessionDetailState
                );

        details =
                findViewById(
                        R.id.sessionDetailInfo
                );

        openLog =
                findViewById(
                        R.id.sessionDetailOpenLog
                );

        analyze =
                findViewById(
                        R.id.sessionDetailAnalyze
                );

        diagnostic =
                findViewById(
                        R.id.sessionDetailDiagnostic
                );

        rename =
                findViewById(
                        R.id.sessionDetailRename
                );

        delete =
                findViewById(
                        R.id.sessionDetailDelete
                );

        sessionId =
                getIntent()
                        .getStringExtra(
                                EXTRA_SESSION_ID
                        );

        openLog.setOnClickListener(
                v -> openLog()
        );

        analyze.setOnClickListener(
                v -> analyze()
        );

        diagnostic.setOnClickListener(
                v -> diagnostic()
        );

        rename.setOnClickListener(
                v -> rename()
        );

        delete.setOnClickListener(
                v -> confirmDelete()
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        session =
                CaptureSessionManager
                        .get(
                                this,
                                sessionId
                        );

        if (session == null) {
            toast(
                    getString(R.string.session_not_found)
            );
            finish();
            return;
        }

        title.setText(
                session.name.isEmpty()
                        ? getString(R.string.unnamed_session)
                        : session.name
        );

        state.setText(
                session.active
                        ? getString(R.string.recording_indicator)
                        : getString(R.string.status_ended)
        );

        state.setTextColor(
                getColor(
                        session.active
                                ? R.color
                                .md_theme_primary
                                : R.color
                                .md_theme_onSurfaceVariant
                )
        );

        details.setText(
                buildDetails(session)
        );

        boolean hasLog =
                session.hasLog();

        openLog.setEnabled(hasLog);
        analyze.setEnabled(hasLog);
        diagnostic.setEnabled(hasLog);

        delete.setEnabled(
                !session.active
        );
    }

    private void openLog() {
        if (!ensureLog()) {
            return;
        }

        Intent intent =
                new Intent(
                        this,
                        FullLogActivity.class
                );

        intent.putExtra(
                FullLogActivity.EXTRA_FILE,
                session.logPath
        );

        startActivity(intent);
    }

    private void analyze() {
        if (!ensureLog()) {
            return;
        }

        Intent intent =
                new Intent(
                        this,
                        CrashAnalysisActivity.class
                );

        intent.putExtra(
                CrashAnalysisActivity
                        .EXTRA_FILE,
                session.logPath
        );

        startActivity(intent);
    }

    private void diagnostic() {
        if (!ensureLog()) {
            return;
        }

        Intent intent =
                new Intent(
                        this,
                        DiagnosticPackActivity.class
                );

        intent.putExtra(
                DiagnosticPackActivity
                        .EXTRA_FILE,
                session.logPath
        );

        startActivity(intent);
    }

    private boolean ensureLog() {
        if (session == null
                || !session.hasLog()) {
            toast(
                    getString(R.string.session_no_log)
            );

            return false;
        }

        return true;
    }

    private void rename() {
        if (session == null) {
            return;
        }

        EditText input =
                new EditText(this);

        input.setSingleLine(true);
        input.setText(
                session.name
        );

        input.setSelection(
                input.getText()
                        .length()
        );

        new MaterialAlertDialogBuilder(
                this
        ).setTitle(
                getString(R.string.rename_session)
        ).setView(
                input
        ).setPositiveButton(
                getString(R.string.save),
                (dialog, which) -> {
                    String name =
                            input.getText()
                                    .toString()
                                    .trim();

                    if (!CaptureSessionManager
                            .rename(
                                    this,
                                    session.id,
                                    name
                            )) {
                        toast(
                                getString(R.string.session_name_required)
                        );
                    }

                    refresh();
                }
        ).setNegativeButton(
                getString(R.string.cancel),
                null
        ).show();
    }

    private void confirmDelete() {
        if (session == null) {
            return;
        }

        if (session.active) {
            toast(
                    getString(R.string.active_session_cannot_delete)
            );
            return;
        }

        new MaterialAlertDialogBuilder(
                this
        ).setTitle(
                getString(R.string.delete_session_title)
        ).setMessage(
                getString(R.string.delete_session_message)
        ).setPositiveButton(
                getString(R.string.delete_session),
                (dialog, which) -> {
                    if (CaptureSessionManager
                            .deleteMetadata(
                                    this,
                                    session.id
                            )) {
                        toast(
                                getString(R.string.session_deleted_keep_log)
                        );

                        finish();
                    } else {
                        toast(
                                getString(R.string.delete_failed)
                        );
                    }
                }
        ).setNegativeButton(
                getString(R.string.cancel),
                null
        ).show();
    }

    private String buildDetails(
            CaptureSessionManager.Session session
    ) {
        StringBuilder out =
                new StringBuilder();

        out.append(
                getString(R.string.session_mode)
        ).append(
                CaptureSessionManager.modeName(this, session.mode)
        ).append('\n');

        out.append(
                getString(R.string.session_start)
        ).append(
                formatTime(
                        session.startedAt
                )
        ).append('\n');

        out.append(
                getString(R.string.session_end)
        ).append(
                session.active
                        ? getString(R.string.session_active)
                        : formatTime(
                                session.endedAt
                        )
        ).append('\n');

        out.append(
                getString(R.string.session_duration)
        ).append(
                duration(
                        session.durationMs()
                )
        ).append('\n');

        out.append(
                getString(R.string.session_log_size)
        ).append(
                CaptureSessionManager
                        .humanSize(
                                session.logBytes
                        )
        ).append('\n');

        out.append(
                getString(R.string.session_status)
        ).append(
                session.lastStatus
                        .isEmpty()
                        ? "—"
                        : session.lastStatus
        ).append('\n');

        if (session.packages.length > 0) {
            out.append(
                    getString(R.string.session_target_apps)
            );

            for (int i = 0;
                 i < session.packages.length;
                 i++) {

                String label =
                        i < session.labels.length
                                ? session.labels[i]
                                : "";

                int uid =
                        i < session.uids.length
                                ? session.uids[i]
                                : 0;

                out.append(
                        "• "
                );

                if (!label.isEmpty()) {
                    out.append(label)
                            .append(" · ");
                }

                out.append(
                        session.packages[i]
                );

                if (uid > 0) {
                    out.append(
                            " · UID "
                    ).append(uid);
                }

                out.append('\n');
            }
        }

        if (!session.logPath.isEmpty()) {
            out.append(
                    getString(R.string.session_log_file)
            ).append(
                    session.logPath
            );
        }

        return out.toString();
    }

    private static String formatTime(
            long time
    ) {
        if (time <= 0L) {
            return "—";
        }

        return new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
        ).format(
                new Date(time)
        );
    }

    private String duration(
            long ms
    ) {
        long seconds =
                Math.max(
                        0L,
                        ms / 1000L
                );

        long minutes =
                seconds / 60L;

        long hours =
                minutes / 60L;

        if (hours > 0L) {
            return getString(
                    R.string.session_duration_hours,
                    hours,
                    minutes % 60L
            );
        }

        if (minutes > 0L) {
            return getString(
                    R.string.session_duration_minutes,
                    minutes,
                    seconds % 60L
            );
        }

        return getString(R.string.seconds, seconds);
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
}
