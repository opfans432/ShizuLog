package dev.shizulog.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SessionListActivity
        extends AppCompatActivity {

    private LinearLayout container;
    private TextView empty;
    private TextView summary;

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_session_list
        );

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(
                        R.id.sessionListRoot
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
                        R.id.sessionListToolbar
                );

        toolbar.setNavigationIcon(
                R.drawable.ic_arrow_back_24
        );

        toolbar.setNavigationOnClickListener(
                v -> finish()
        );

        container =
                findViewById(
                        R.id.sessionListContainer
                );

        empty =
                findViewById(
                        R.id.sessionListEmpty
                );

        summary =
                findViewById(
                        R.id.sessionListSummary
                );
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        container.removeAllViews();

        List<CaptureSessionManager.Session>
                sessions =
                        CaptureSessionManager
                                .list(this);

        summary.setText(
                sessions.size()
                        + " 个记录会话"
        );

        empty.setVisibility(
                sessions.isEmpty()
                        ? View.VISIBLE
                        : View.GONE
        );

        for (CaptureSessionManager.Session session :
                sessions) {

            MaterialCardView card =
                    new MaterialCardView(
                            this
                    );

            card.setRadius(
                    dp(20)
            );

            card.setStrokeWidth(
                    dp(1)
            );

            card.setStrokeColor(
                    getColor(
                            R.color
                                    .md_theme_outlineVariant
                    )
            );

            card.setCardBackgroundColor(
                    getColor(
                            R.color.card_surface
                    )
            );

            LinearLayout.LayoutParams
                    cardParams =
                            new LinearLayout
                                    .LayoutParams(
                                            LinearLayout
                                                    .LayoutParams
                                                    .MATCH_PARENT,
                                            LinearLayout
                                                    .LayoutParams
                                                    .WRAP_CONTENT
                                    );

            cardParams.setMargins(
                    0,
                    0,
                    0,
                    dp(10)
            );

            card.setLayoutParams(
                    cardParams
            );

            LinearLayout body =
                    new LinearLayout(
                            this
                    );

            body.setOrientation(
                    LinearLayout.VERTICAL
            );

            body.setPadding(
                    dp(16),
                    dp(14),
                    dp(16),
                    dp(14)
            );

            TextView title =
                    new TextView(
                            this
                    );

            title.setText(
                    session.name
            );

            title.setTextSize(17);
            title.setTextColor(
                    getColor(
                            R.color
                                    .md_theme_onSurface
                    )
            );

            title.setTypeface(
                    title.getTypeface(),
                    android.graphics
                            .Typeface.BOLD
            );

            TextView state =
                    new TextView(
                            this
                    );

            state.setText(
                    (session.active
                            ? "● 正在记录"
                            : "已结束")
                            + " · "
                            + CaptureSessionManager
                            .modeName(
                                    session.mode
                            )
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

            state.setTextSize(13);

            TextView meta =
                    new TextView(
                            this
                    );

            meta.setText(
                    formatTime(
                            session.startedAt
                    )
                            + " · "
                            + duration(
                                    session.durationMs()
                            )
                            + " · "
                            + CaptureSessionManager
                            .humanSize(
                                    session.logBytes
                            )
                            + "\n"
                            + targetSummary(
                                    session
                            )
            );

            meta.setTextColor(
                    getColor(
                            R.color
                                    .md_theme_onSurfaceVariant
                    )
            );

            meta.setTextSize(12);

            body.addView(title);
            body.addView(state);
            body.addView(meta);

            card.addView(body);

            card.setOnClickListener(
                    v -> {
                        Intent intent =
                                new Intent(
                                        this,
                                        SessionDetailActivity.class
                                );

                        intent.putExtra(
                                SessionDetailActivity
                                        .EXTRA_SESSION_ID,
                                session.id
                        );

                        startActivity(intent);
                    }
            );

            container.addView(card);
        }
    }

    private static String targetSummary(
            CaptureSessionManager.Session session
    ) {
        if (session.mode
                == LogCaptureService
                .MODE_GLOBAL) {
            return "全局 Logcat";
        }

        if (session.labels.length == 0) {
            return session.packages.length > 0
                    ? session.packages[0]
                    : "未记录目标";
        }

        if (session.labels.length == 1) {
            return session.labels[0]
                    + (session.packages.length > 0
                    ? " · "
                    + session.packages[0]
                    : "");
        }

        return session.labels[0]
                + " 等 "
                + session.labels.length
                + " 个应用";
    }

    private static String formatTime(
            long time
    ) {
        if (time <= 0L) {
            return "未知时间";
        }

        return new SimpleDateFormat(
                "yyyy-MM-dd HH:mm",
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
                    + "h "
                    + (minutes % 60L)
                    + "m";
        }

        if (minutes > 0L) {
            return minutes
                    + "m "
                    + (seconds % 60L)
                    + "s";
        }

        return seconds + "s";
    }

    private int dp(
            int value
    ) {
        return Math.round(
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }
}
