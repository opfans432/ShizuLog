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
                    "会话不存在"
            );
            finish();
            return;
        }

        title.setText(
                session.name
        );

        state.setText(
                session.active
                        ? "● 正在记录"
                        : "记录已结束"
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
                    "这个会话没有可用日志文件"
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
                "重命名会话"
        ).setView(
                input
        ).setPositiveButton(
                "保存",
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
                                "名称不能为空"
                        );
                    }

                    refresh();
                }
        ).setNegativeButton(
                "取消",
                null
        ).show();
    }

    private void confirmDelete() {
        if (session == null) {
            return;
        }

        if (session.active) {
            toast(
                    "正在记录的会话不能删除"
            );
            return;
        }

        new MaterialAlertDialogBuilder(
                this
        ).setTitle(
                "删除会话记录？"
        ).setMessage(
                "这里只删除 ShizuLog 的会话索引和名称，不会删除原始 .log 文件。"
        ).setPositiveButton(
                "删除会话",
                (dialog, which) -> {
                    if (CaptureSessionManager
                            .deleteMetadata(
                                    this,
                                    session.id
                            )) {
                        toast(
                                "会话记录已删除，日志文件仍保留"
                        );

                        finish();
                    } else {
                        toast(
                                "删除失败"
                        );
                    }
                }
        ).setNegativeButton(
                "取消",
                null
        ).show();
    }

    private static String buildDetails(
            CaptureSessionManager.Session session
    ) {
        StringBuilder out =
                new StringBuilder();

        out.append(
                "模式："
        ).append(
                CaptureSessionManager
                        .modeName(
                                session.mode
                        )
        ).append('\n');

        out.append(
                "开始："
        ).append(
                formatTime(
                        session.startedAt
                )
        ).append('\n');

        out.append(
                "结束："
        ).append(
                session.active
                        ? "仍在记录"
                        : formatTime(
                                session.endedAt
                        )
        ).append('\n');

        out.append(
                "时长："
        ).append(
                duration(
                        session.durationMs()
                )
        ).append('\n');

        out.append(
                "日志大小："
        ).append(
                CaptureSessionManager
                        .humanSize(
                                session.logBytes
                        )
        ).append('\n');

        out.append(
                "状态："
        ).append(
                session.lastStatus
                        .isEmpty()
                        ? "—"
                        : session.lastStatus
        ).append('\n');

        if (session.packages.length > 0) {
            out.append(
                    "\n目标应用：\n"
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
                    "\n日志文件：\n"
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

    private static String duration(
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
            return hours
                    + " 小时 "
                    + (minutes % 60L)
                    + " 分";
        }

        if (minutes > 0L) {
            return minutes
                    + " 分 "
                    + (seconds % 60L)
                    + " 秒";
        }

        return seconds + " 秒";
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
