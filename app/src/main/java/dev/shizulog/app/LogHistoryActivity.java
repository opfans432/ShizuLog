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

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogHistoryActivity extends AppCompatActivity {

    private LinearLayout listContainer;
    private TextView emptyText;
    private TextView countText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_history);

        View root = findViewById(R.id.historyRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.historyToolbar);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24);
        toolbar.setNavigationOnClickListener(v -> finish());

        listContainer = findViewById(R.id.historyListContainer);
        emptyText = findViewById(R.id.historyEmpty);
        countText = findViewById(R.id.historyCount);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
    }

    private void loadHistory() {
        listContainer.removeAllViews();

        List<File> logs = new ArrayList<>();
        collectLogs(getExternalFilesDir(null), logs);
        logs.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        countText.setText("共 " + logs.size() + " 份日志");
        emptyText.setVisibility(logs.isEmpty() ? View.VISIBLE : View.GONE);

        for (File file : logs) {
            listContainer.addView(buildLogCard(file));
        }
    }

    private void collectLogs(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;

        File[] children = dir.listFiles();
        if (children == null) return;

        Arrays.sort(children, (a, b) ->
                a.getName().compareToIgnoreCase(b.getName()));

        for (File child : children) {
            if (child.isDirectory()) {
                collectLogs(child, out);
            } else if (child.isFile()
                    && child.getName().toLowerCase(Locale.ROOT).endsWith(".log")) {
                out.add(child);
            }
        }
    }

    private View buildLogCard(File file) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(20));
        card.setCardElevation(dp(1));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(getColor(R.color.md_theme_outlineVariant));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView name = new TextView(this);
        name.setText(file.getName());
        name.setTextSize(15);
        name.setTextColor(getColor(R.color.md_theme_onSurface));
        name.setTypeface(
                name.getTypeface(),
                android.graphics.Typeface.BOLD
        );

        String date = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT
        ).format(new Date(file.lastModified()));

        TextView meta = new TextView(this);
        meta.setText(date + " · " + humanSize(file.length()));
        meta.setTextSize(12);
        meta.setTextColor(getColor(R.color.md_theme_onSurfaceVariant));
        meta.setPadding(0, dp(5), 0, 0);

        TextView hint = new TextView(this);
        hint.setText("点按查看完整日志");
        hint.setTextSize(12);
        hint.setTextColor(getColor(R.color.md_theme_secondary));
        hint.setPadding(0, dp(8), 0, 0);

        content.addView(name);
        content.addView(meta);
        content.addView(hint);
        card.addView(content);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);

        card.setOnClickListener(v -> {
            Intent intent = new Intent(this, FullLogActivity.class);
            intent.putExtra(
                    FullLogActivity.EXTRA_FILE,
                    file.getAbsolutePath()
            );
            startActivity(intent);
        });

        return card;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";

        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }

        return String.format(Locale.US, "%.2f MB", kb / 1024.0);
    }
}
