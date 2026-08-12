package dev.shizulog.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class WorkspaceActivity
        extends AppCompatActivity {

    private TextView summary;
    private TextView latestTitle;
    private TextView latestMeta;
    private MaterialButton latestButton;

    private CaptureSessionManager.Session
            latestSession;

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_workspace
        );

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(
                        R.id.workspaceRoot
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

        summary =
                findViewById(
                        R.id.workspaceSummary
                );

        latestTitle =
                findViewById(
                        R.id.workspaceLatestTitle
                );

        latestMeta =
                findViewById(
                        R.id.workspaceLatestMeta
                );

        latestButton =
                findViewById(
                        R.id.workspaceLatestOpen
                );

        findViewById(
                R.id.workspaceCapture
        ).setOnClickListener(
                v -> open(
                        MainActivity.class
                )
        );

        findViewById(
                R.id.workspaceSessions
        ).setOnClickListener(
                v -> open(
                        SessionListActivity.class
                )
        );

        findViewById(
                R.id.workspaceHistory
        ).setOnClickListener(
                v -> open(
                        LogHistoryActivity.class
                )
        );

        findViewById(
                R.id.workspaceAnalysis
        ).setOnClickListener(
                v -> chooseLog(false)
        );

        findViewById(
                R.id.workspaceCrash
        ).setOnClickListener(
                v -> chooseLog(true)
        );

        latestButton.setOnClickListener(
                v -> {
                    if (latestSession == null) {
                        return;
                    }

                    Intent intent =
                            new Intent(
                                    this,
                                    SessionDetailActivity.class
                            );

                    intent.putExtra(
                            SessionDetailActivity
                                    .EXTRA_SESSION_ID,
                            latestSession.id
                    );

                    startActivity(intent);
                }
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<CaptureSessionManager.Session>
                sessions =
                        CaptureSessionManager
                                .list(this);

        int active = 0;
        int withLog = 0;

        for (CaptureSessionManager.Session item :
                sessions) {

            if (item.active) {
                active++;
            }

            if (item.hasLog()) {
                withLog++;
            }
        }

        summary.setText(
                sessions.size()
                        + " 个会话 · "
                        + withLog
                        + " 份可用日志"
                        + (active > 0
                        ? " · "
                        + active
                        + " 个正在记录"
                        : "")
        );

        latestSession =
                sessions.isEmpty()
                        ? null
                        : sessions.get(0);

        if (latestSession == null) {
            latestTitle.setText(
                    "还没有记录会话"
            );

            latestMeta.setText(
                    "进入“实时日志”开始第一次记录"
            );

            latestButton.setEnabled(
                    false
            );

            latestButton.setText(
                    "暂无会话"
            );

            return;
        }

        latestTitle.setText(
                latestSession.name
        );

        latestMeta.setText(
                (latestSession.active
                        ? "● 正在记录"
                        : "已结束")
                        + " · "
                        + CaptureSessionManager
                        .modeName(
                                latestSession.mode
                        )
                        + " · "
                        + formatTime(
                                latestSession
                                        .startedAt
                        )
                        + " · "
                        + CaptureSessionManager
                        .humanSize(
                                latestSession
                                        .logBytes
                        )
        );

        latestButton.setEnabled(true);
        latestButton.setText(
                "打开最近会话"
        );
    }

    private void chooseLog(
            boolean crash
    ) {
        List<CaptureSessionManager.Session>
                available =
                        new ArrayList<>();

        for (CaptureSessionManager.Session item :
                CaptureSessionManager
                        .list(this)) {
            if (item.hasLog()) {
                available.add(item);
            }

            if (available.size()
                    >= 20) {
                break;
            }
        }

        if (available.isEmpty()) {
            Toast.makeText(
                    this,
                    "还没有可分析的会话日志",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        if (available.size() == 1) {
            openTool(
                    available.get(0),
                    crash
            );
            return;
        }

        String[] items =
                new String[
                        available.size()
                ];

        for (int i = 0;
             i < items.length;
             i++) {

            CaptureSessionManager.Session
                    session =
                            available.get(i);

            items[i] =
                    session.name
                            + "\n"
                            + formatTime(
                                    session.startedAt
                            )
                            + " · "
                            + CaptureSessionManager
                            .humanSize(
                                    session.logBytes
                            );
        }

        new MaterialAlertDialogBuilder(
                this
        ).setTitle(
                crash
                        ? "选择崩溃分析会话"
                        : "选择日志分析会话"
        ).setItems(
                items,
                (dialog, which) ->
                        openTool(
                                available.get(
                                        which
                                ),
                                crash
                        )
        ).setNegativeButton(
                "取消",
                null
        ).show();
    }

    private void openTool(
            CaptureSessionManager.Session session,
            boolean crash
    ) {
        Intent intent =
                new Intent(
                        this,
                        crash
                                ? CrashAnalysisActivity.class
                                : FullLogActivity.class
                );

        intent.putExtra(
                crash
                        ? CrashAnalysisActivity
                        .EXTRA_FILE
                        : FullLogActivity
                        .EXTRA_FILE,
                session.logPath
        );

        startActivity(intent);
    }

    private void open(
            Class<?> activity
    ) {
        startActivity(
                new Intent(
                        this,
                        activity
                )
        );
    }

    private static String formatTime(
            long time
    ) {
        if (time <= 0L) {
            return "未知时间";
        }

        return new SimpleDateFormat(
                "MM-dd HH:mm",
                Locale.getDefault()
        ).format(
                new Date(time)
        );
    }
}
