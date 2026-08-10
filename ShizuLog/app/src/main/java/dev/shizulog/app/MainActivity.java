package dev.shizulog.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_CREATE_DOCUMENT = 2001;
    private static final int REQ_SHIZUKU_PERMISSION = 2002;
    private static final int MAX_SCREEN_CHARS = 120_000;

    private EditText packageInput;
    private TextView selectedLabel;
    private TextView permissionState;
    private TextView statusText;
    private TextView logText;
    private ScrollView logScroll;
    private String selectedPackage = "";
    private String selectedAppLabel = "";
    private String currentLogPath;
    private boolean pendingStartAfterPermission;
    private final StringBuilder screenBuffer = new StringBuilder();

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::refreshShizukuState;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::refreshShizukuState;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        if (requestCode != REQ_SHIZUKU_PERMISSION) return;
        refreshShizukuState();
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            toast("Shizuku 授权成功");
            if (pendingStartAfterPermission) {
                pendingStartAfterPermission = false;
                startCaptureInternal();
            }
        } else {
            pendingStartAfterPermission = false;
            toast("Shizuku 授权被拒绝");
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (LogCaptureService.ACTION_LINE.equals(intent.getAction())) {
                appendLog(intent.getStringExtra(LogCaptureService.EXTRA_LINE));
            } else if (LogCaptureService.ACTION_STATUS.equals(intent.getAction())) {
                String status = intent.getStringExtra(LogCaptureService.EXTRA_STATUS);
                String path = intent.getStringExtra(LogCaptureService.EXTRA_FILE);
                if (status != null) statusText.setText(status);
                if (path != null) currentLogPath = path;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        registerStatusReceiver();
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        refreshShizukuState();
        requestNotificationPermissionIfNeeded();
    }

    private View buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFFFAFAFA);

        TextView title = text("ShizuLog", 24, true);
        root.addView(title);
        TextView subtitle = text("Shizuku 授权 · 记录指定应用 logcat（多进程/崩溃缓冲区）", 13, false);
        subtitle.setTextColor(0xFF666666);
        root.addView(subtitle);

        TextView creator = text("创作者：ChatGPT", 12, false);
        creator.setTextColor(0xFF777777);
        root.addView(creator, lpMatchWrap(0, dp(10)));

        permissionState = text("Shizuku：正在检测…", 14, true);
        root.addView(permissionState, lpMatchWrap(0, dp(8)));

        LinearLayout grantRow = row();
        Button grant = button("请求 Shizuku 授权");
        grant.setOnClickListener(v -> requestShizukuPermission(false));
        Button openShizuku = button("打开 Shizuku");
        openShizuku.setOnClickListener(v -> openShizukuManager());
        Button refresh = button("刷新状态");
        refresh.setOnClickListener(v -> refreshShizukuState());
        grantRow.addView(grant, lpWeight());
        grantRow.addView(openShizuku, lpWeightWithLeft());
        grantRow.addView(refresh, lpWeightWithLeft());
        root.addView(grantRow);

        packageInput = new EditText(this);
        packageInput.setHint("目标包名，例如 com.example.app");
        packageInput.setSingleLine(true);
        packageInput.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(packageInput, lpMatchWrap(0, dp(8)));

        LinearLayout chooseRow = row();
        Button choose = button("选择已安装 App");
        choose.setOnClickListener(v -> showAppPicker());
        Button confirmPkg = button("使用此包名");
        confirmPkg.setOnClickListener(v -> selectTypedPackage());
        chooseRow.addView(choose, lpWeight());
        chooseRow.addView(confirmPkg, lpWeightWithLeft());
        root.addView(chooseRow);

        selectedLabel = text("尚未选择目标 App", 14, false);
        selectedLabel.setPadding(0, dp(8), 0, dp(6));
        root.addView(selectedLabel);

        LinearLayout control1 = row();
        Button start = button("开始记录");
        start.setOnClickListener(v -> startCapture());
        Button launch = button("打开目标 App");
        launch.setOnClickListener(v -> launchTarget());
        Button stop = button("停止");
        stop.setOnClickListener(v -> stopCapture());
        control1.addView(start, lpWeight());
        control1.addView(launch, lpWeightWithLeft());
        control1.addView(stop, lpWeightWithLeft());
        root.addView(control1, lpMatchWrap(0, dp(8)));

        LinearLayout control2 = row();
        Button clear = button("清空屏幕");
        clear.setOnClickListener(v -> { screenBuffer.setLength(0); logText.setText(""); });
        Button export = button("导出日志");
        export.setOnClickListener(v -> exportLog());
        control2.addView(clear, lpWeight());
        control2.addView(export, lpWeightWithLeft());
        root.addView(control2);

        statusText = text("提示：先启动 Shizuku 并授权本应用，再点“开始记录”，随后打开目标 App 复现问题。", 13, false);
        statusText.setTextColor(0xFF444444);
        statusText.setPadding(0, dp(10), 0, dp(8));
        root.addView(statusText);

        logText = text("", 11, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(8), dp(8), dp(8), dp(8));
        logText.setTextColor(0xFFEAEAEA);
        logText.setBackgroundColor(0xFF161616);

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.addView(logText, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private void refreshShizukuState() {
        try {
            if (!Shizuku.pingBinder()) {
                permissionState.setText("Shizuku：未运行 / 未连接");
                permissionState.setTextColor(0xFFB3261E);
                return;
            }
            if (Shizuku.isPreV11()) {
                permissionState.setText("Shizuku：版本过旧（需要 API 11+）");
                permissionState.setTextColor(0xFFB3261E);
                return;
            }
            int state = Shizuku.checkSelfPermission();
            if (state == PackageManager.PERMISSION_GRANTED) {
                int uid = Shizuku.getUid();
                String mode = uid == 0 ? "Root" : "ADB Shell";
                permissionState.setText("Shizuku：已连接并授权 ✓（" + mode + "）");
                permissionState.setTextColor(0xFF137333);
            } else {
                permissionState.setText("Shizuku：已连接，等待授权");
                permissionState.setTextColor(0xFFB06000);
            }
        } catch (Throwable e) {
            permissionState.setText("Shizuku：状态读取失败");
            permissionState.setTextColor(0xFFB3261E);
        }
    }

    private boolean requestShizukuPermission(boolean startAfterGrant) {
        pendingStartAfterPermission = startAfterGrant;
        try {
            if (!Shizuku.pingBinder()) {
                pendingStartAfterPermission = false;
                new AlertDialog.Builder(this)
                        .setTitle("Shizuku 尚未运行")
                        .setMessage("请先打开 Shizuku，并通过无线调试/ADB 或 Root 启动 Shizuku 服务。启动后返回这里点“刷新状态”。")
                        .setPositiveButton("打开 Shizuku", (d, w) -> openShizukuManager())
                        .setNegativeButton("关闭", null)
                        .show();
                return false;
            }
            if (Shizuku.isPreV11()) {
                pendingStartAfterPermission = false;
                toast("Shizuku 版本过旧，请更新");
                return false;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                pendingStartAfterPermission = false;
                refreshShizukuState();
                return true;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                new AlertDialog.Builder(this)
                        .setTitle("需要 Shizuku 授权")
                        .setMessage("本应用仅使用 Shizuku 的 shell 权限读取你主动选择的目标 App 的 logcat 日志。")
                        .setPositiveButton("继续授权", (d, w) -> Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION))
                        .setNegativeButton("取消", (d, w) -> pendingStartAfterPermission = false)
                        .show();
            } else {
                Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION);
            }
            return false;
        } catch (Throwable e) {
            pendingStartAfterPermission = false;
            refreshShizukuState();
            toast("请求 Shizuku 授权失败: " + safeMessage(e));
            return false;
        }
    }

    private void openShizukuManager() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (launch == null) {
            toast("未找到 Shizuku，请先安装 Shizuku");
            return;
        }
        startActivity(launch);
    }

    private void showAppPicker() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        List<AppItem> items = new ArrayList<>();
        for (ApplicationInfo ai : installed) {
            if (ai.packageName.equals(getPackageName())) continue;
            Intent launch = pm.getLaunchIntentForPackage(ai.packageName);
            if (launch == null) continue;
            String label = String.valueOf(pm.getApplicationLabel(ai));
            items.add(new AppItem(label, ai.packageName, ai.uid));
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        items.sort((a, b) -> collator.compare(a.label, b.label));
        String[] display = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            AppItem x = items.get(i);
            display[i] = x.label + "\n" + x.pkg;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择目标 App")
                .setItems(display, (d, which) -> {
                    AppItem x = items.get(which);
                    applyTarget(x.label, x.pkg);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void selectTypedPackage() {
        String pkg = packageInput.getText().toString().trim();
        if (pkg.isEmpty()) {
            toast("请输入包名");
            return;
        }
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            String label = String.valueOf(getPackageManager().getApplicationLabel(ai));
            applyTarget(label, pkg);
        } catch (PackageManager.NameNotFoundException e) {
            toast("没有找到此包名");
        }
    }

    private void applyTarget(String label, String pkg) {
        selectedPackage = pkg;
        selectedAppLabel = label;
        packageInput.setText(pkg);
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            selectedLabel.setText("目标：" + label + "\n包名：" + pkg + "\nUID：" + ai.uid);
        } catch (Exception e) {
            selectedLabel.setText("目标：" + label + "\n包名：" + pkg);
        }
    }

    private void startCapture() {
        selectTypedPackageSilently();
        if (selectedPackage.isEmpty()) {
            toast("先选择目标 App");
            return;
        }
        if (!isShizukuReady()) {
            requestShizukuPermission(true);
            return;
        }
        startCaptureInternal();
    }

    private boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    private void startCaptureInternal() {
        selectTypedPackageSilently();
        if (selectedPackage.isEmpty()) return;
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(selectedPackage, 0);
            Intent s = new Intent(this, LogCaptureService.class)
                    .setAction(LogCaptureService.ACTION_START)
                    .putExtra(LogCaptureService.EXTRA_PACKAGE, selectedPackage)
                    .putExtra(LogCaptureService.EXTRA_UID, ai.uid)
                    .putExtra(LogCaptureService.EXTRA_LABEL, selectedAppLabel);
            startForegroundService(s);
            screenBuffer.setLength(0);
            logText.setText("");
            statusText.setText("已通过 Shizuku 启动日志采集，目标 UID=" + ai.uid + "。现在可以打开目标 App 复现问题。");
        } catch (Exception e) {
            toast("启动记录失败: " + e.getMessage());
        }
    }

    private void selectTypedPackageSilently() {
        String typed = packageInput.getText().toString().trim();
        if (!typed.isEmpty() && !typed.equals(selectedPackage)) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(typed, 0);
                applyTarget(String.valueOf(getPackageManager().getApplicationLabel(ai)), typed);
            } catch (Exception ignored) {}
        }
    }

    private void launchTarget() {
        selectTypedPackageSilently();
        if (selectedPackage.isEmpty()) {
            toast("先选择目标 App");
            return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(selectedPackage);
        if (launch == null) {
            toast("这个包没有可启动的主界面");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void stopCapture() {
        Intent s = new Intent(this, LogCaptureService.class).setAction(LogCaptureService.ACTION_STOP);
        startService(s);
        statusText.setText("已请求停止记录");
    }

    private void exportLog() {
        if (currentLogPath == null || !new File(currentLogPath).isFile()) {
            toast("还没有可导出的日志文件");
            return;
        }
        String name = new File(currentLogPath).getName();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, REQ_CREATE_DOCUMENT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CREATE_DOCUMENT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null || currentLogPath == null) return;
        try (FileInputStream in = new FileInputStream(currentLogPath);
             OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IllegalStateException("无法打开导出位置");
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            toast("日志已导出");
        } catch (Exception e) {
            toast("导出失败: " + e.getMessage());
        }
    }

    private void appendLog(String line) {
        if (line == null) return;
        screenBuffer.append(line).append('\n');
        if (screenBuffer.length() > MAX_SCREEN_CHARS) {
            int cut = screenBuffer.length() - MAX_SCREEN_CHARS;
            int nl = screenBuffer.indexOf("\n", cut);
            screenBuffer.delete(0, nl >= 0 ? nl + 1 : cut);
        }
        logText.setText(screenBuffer);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void registerStatusReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(LogCaptureService.ACTION_LINE);
        f.addAction(LogCaptureService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 99);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShizukuState();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        super.onDestroy();
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFF202124);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams lpWeight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams lpWeightWithLeft() {
        LinearLayout.LayoutParams p = lpWeight();
        p.leftMargin = dp(6);
        return p;
    }

    private LinearLayout.LayoutParams lpMatchWrap(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        p.bottomMargin = bottom;
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String safeMessage(Throwable e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private static class AppItem {
        final String label;
        final String pkg;
        final int uid;
        AppItem(String label, String pkg, int uid) {
            this.label = label;
            this.pkg = pkg;
            this.uid = uid;
        }
    }
}
