package dev.shizulog.app;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;

public class AboutActivity extends AppCompatActivity {
    private static final String REPOSITORY_URL =
            "https://github.com/opfans432/ShizuLog";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        applySystemBarInsets();

        MaterialToolbar toolbar = findViewById(R.id.aboutToolbar);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24);
        toolbar.setNavigationOnClickListener(view -> finish());

        String version = readVersionName();

        Chip versionChip = findViewById(R.id.aboutVersionChip);
        versionChip.setText("v" + version);

        TextView versionText = findViewById(R.id.aboutVersionText);
        versionText.setText("版本：v" + version);

        findViewById(R.id.repoButton).setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL));
            startActivity(intent);
        });
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.rootAbout);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private String readVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "1.1.0" : info.versionName;
        } catch (Exception ignored) {
            return "1.1.0";
        }
    }
}
