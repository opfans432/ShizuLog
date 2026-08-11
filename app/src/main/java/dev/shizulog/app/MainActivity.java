package dev.shizulog.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CREATE_DOCUMENT = 2001;
    private static final int REQ_SHIZUKU_PERMISSION = 2002;
    private static final int MAX_SCREEN_CHARS = 120_000;

    private static final String PREFS = "shizulog_state";
    private static final String KEY_TARGET_PACKAGE = "target_package";
    private static final String KEY_TARGET_LABEL = "target_label";
    private static final String KEY_CURRENT_LOG_PATH = "current_log_path";
    private static final String KEY_LAST_STATUS = "last_status";
    private static final String KEY_RECORDING = "recording";
    private static final String KEY_TARGET_LAUNCHED = "target_launched";
    private static final String KEY_CAPTURE_MODE = "capture_mode";
    private static final String KEY_MULTI_PACKAGES = "multi_packages";

    private static final int CAPTURE_SINGLE = LogCaptureService.MODE_SINGLE;
    private static final int CAPTURE_MULTI = LogCaptureService.MODE_MULTI;
    private static final int CAPTURE_GLOBAL = LogCaptureService.MODE_GLOBAL;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_WARN = 1;
    private static final int FILTER_ERROR = 2;

    private static final Pattern THREADTIME_PRIORITY = Pattern.compile(
            "^\\s*\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+"
    );

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder screenBuffer = new StringBuilder();

    private TextInputEditText packageInput;
    private TextInputEditText logSearchInput;
    private TextView selectedLabel;
    private TextView permissionState;
    private TextView backendText;
    private TextView statusText;
    private TextView logPathText;
    private TextView logSizeText;
    private TextView logText;
    private TextView logEmptyTitle;
    private TextView logEmptyMessage;
    private TextView logFilterSummary;
    private ScrollView logScroll;
    private ScrollView mainScroll;
    private ImageView targetAppIcon;
    private Chip heroShizukuChip;
    private Chip recordingStateChip;
    private Chip logFilterAll;
    private Chip logFilterWarn;
    private Chip logFilterError;
    private Chip captureModeSingle;
    private Chip captureModeMulti;
    private Chip captureModeGlobal;
    private LinearLayout manualPackageContainer;
    private LinearLayout logEmptyState;
    private LinearLayout singleTargetContainer;
    private LinearLayout multiTargetContainer;
    private LinearLayout globalTargetContainer;
    private MaterialButton manualPackageToggle;
    private MaterialButton startButton;
    private MaterialButton openTargetButton;
    private MaterialButton stopButton;
    private MaterialButton exportButton;
    private MaterialButton snapshotButton;
    private MaterialButton viewFullLogButton;
    private MaterialButton historyLogButton;
    private MaterialButton chooseTargetButton;
    private MaterialButton chooseMultiTargetButton;
    private TextView multiSelectedSummary;
    private AppPickerDialog appPickerDialog;
    private MultiAppPickerDialog multiAppPickerDialog;

    private String selectedPackage = "";
    private String selectedAppLabel = "";
    private String currentLogPath;
    private int captureMode = CAPTURE_SINGLE;
    private final LinkedHashSet<String> selectedMultiPackages =
            new LinkedHashSet<>();
    private boolean pendingStartAfterPermission;
    private boolean manualPackageExpanded;
    private int logFilterMode = FILTER_ALL;
    private int appendedLineCounter;
    private Runnable pendingLogRender;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::refreshShizukuState;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::refreshShizukuState;

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> {
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
            String action = intent.getAction();

            if (LogCaptureService.ACTION_LINE.equals(action)) {
                appendLog(intent.getStringExtra(LogCaptureService.EXTRA_LINE));
            } else if (LogCaptureService.ACTION_STATUS.equals(action)) {
                String status = intent.getStringExtra(LogCaptureService.EXTRA_STATUS);
                String path = intent.getStringExtra(LogCaptureService.EXTRA_FILE);

                if (status != null) {
                    setStatus(status);
                    prefs().edit().putString(KEY_LAST_STATUS, status).apply();
                }

                if (path != null) {
                    currentLogPath = path;
                    prefs().edit().putString(KEY_CURRENT_LOG_PATH, path).apply();
                    updateLogMeta();
                }

                refreshActionState();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        applySystemBarInsets();
        setupActions();

        restoreUiState();
        registerStatusReceiver();

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        refreshShizukuState();
        refreshActionState();
        requestNotificationPermissionIfNeeded();
    }

    private void bindViews() {
        packageInput = findViewById(R.id.packageInput);
        logSearchInput = findViewById(R.id.logSearchInput);
        selectedLabel = findViewById(R.id.selectedLabel);
        permissionState = findViewById(R.id.permissionState);
        backendText = findViewById(R.id.backendText);
        statusText = findViewById(R.id.statusText);
        logPathText = findViewById(R.id.logPathText);
        logSizeText = findViewById(R.id.logSizeText);
        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        mainScroll = findViewById(R.id.mainScroll);
        targetAppIcon = findViewById(R.id.targetAppIcon);
        heroShizukuChip = findViewById(R.id.heroShizukuChip);
        recordingStateChip = findViewById(R.id.recordingStateChip);
        logFilterAll = findViewById(R.id.logFilterAll);
        logFilterWarn = findViewById(R.id.logFilterWarn);
        logFilterError = findViewById(R.id.logFilterError);
        captureModeSingle = findViewById(R.id.captureModeSingle);
        captureModeMulti = findViewById(R.id.captureModeMulti);
        captureModeGlobal = findViewById(R.id.captureModeGlobal);
        logFilterSummary = findViewById(R.id.logFilterSummary);
        manualPackageContainer = findViewById(R.id.manualPackageContainer);
        manualPackageToggle = findViewById(R.id.manualPackageToggle);
        logEmptyState = findViewById(R.id.logEmptyState);
        logEmptyTitle = findViewById(R.id.logEmptyTitle);
        logEmptyMessage = findViewById(R.id.logEmptyMessage);
        singleTargetContainer = findViewById(R.id.singleTargetContainer);
        multiTargetContainer = findViewById(R.id.multiTargetContainer);
        globalTargetContainer = findViewById(R.id.globalTargetContainer);
        multiSelectedSummary = findViewById(R.id.multiSelectedSummary);

        startButton = findViewById(R.id.startButton);
        openTargetButton = findViewById(R.id.openTargetButton);
        stopButton = findViewById(R.id.stopButton);
        exportButton = findViewById(R.id.exportButton);
        snapshotButton = findViewById(R.id.snapshotButton);
        viewFullLogButton = findViewById(R.id.viewFullLogButton);
        historyLogButton = findViewById(R.id.historyLogButton);
        chooseTargetButton = findViewById(R.id.chooseTargetButton);
        chooseMultiTargetButton = findViewById(R.id.chooseMultiTargetButton);
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.rootMain);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private void setupActions() {
        MaterialToolbar toolbar = findViewById(R.id.topAppBar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_about) {
                startActivity(new Intent(this, AboutActivity.class));
                return true;
            }
            return false;
        });

        findViewById(R.id.grantButton).setOnClickListener(v -> requestShizukuPermission(false));
        findViewById(R.id.openShizukuButton).setOnClickListener(v -> openShizukuManager());
        findViewById(R.id.refreshButton).setOnClickListener(v -> refreshShizukuState());

        chooseTargetButton.setOnClickListener(v -> showAppPicker());
        chooseMultiTargetButton.setOnClickListener(v -> showMultiAppPicker());
        findViewById(R.id.usePackageButton).setOnClickListener(v -> selectTypedPackage());
        manualPackageToggle.setOnClickListener(v -> toggleManualPackageInput());

        startButton.setOnClickListener(v -> startCapture());
        openTargetButton.setOnClickListener(v -> launchTarget());
        stopButton.setOnClickListener(v -> stopCapture());

        exportButton.setOnClickListener(v -> exportLog());
        snapshotButton.setOnClickListener(v -> {
            requestCrashSnapshot();
            setStatus("已请求补抓崩溃快照");
        });

        viewFullLogButton.setOnClickListener(
                v -> openCurrentFullLog()
        );

        historyLogButton.setOnClickListener(
                v -> startActivity(
                        new Intent(
                                this,
                                LogHistoryActivity.class
                        )
                )
        );

        findViewById(R.id.clearButton).setOnClickListener(v -> {
            screenBuffer.setLength(0);
            scheduleLogRender();
            showEmptyLogState(
                    "预览已清空",
                    "日志文件不会被删除；新日志到来后会继续显示"
            );
        });

        logSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                scheduleLogRenderPreservePage();
            }

            @Override public void afterTextChanged(Editable s) {}
        });

        logFilterAll.setOnClickListener(v -> {
            logFilterMode = FILTER_ALL;
            scheduleLogRenderPreservePage();
        });

        logFilterWarn.setOnClickListener(v -> {
            logFilterMode = FILTER_WARN;
            scheduleLogRenderPreservePage();
        });

        logFilterError.setOnClickListener(v -> {
            logFilterMode = FILTER_ERROR;
            scheduleLogRenderPreservePage();
        });


        captureModeSingle.setOnClickListener(
                v -> setCaptureMode(CAPTURE_SINGLE, true)
        );

        captureModeMulti.setOnClickListener(
                v -> setCaptureMode(CAPTURE_MULTI, true)
        );

        captureModeGlobal.setOnClickListener(
                v -> setCaptureMode(CAPTURE_GLOBAL, true)
        );
    }


    private void setCaptureMode(
            int mode,
            boolean persist
    ) {
        captureMode = mode;

        singleTargetContainer.setVisibility(
                mode == CAPTURE_SINGLE
                        ? View.VISIBLE
                        : View.GONE
        );

        multiTargetContainer.setVisibility(
                mode == CAPTURE_MULTI
                        ? View.VISIBLE
                        : View.GONE
        );

        globalTargetContainer.setVisibility(
                mode == CAPTURE_GLOBAL
                        ? View.VISIBLE
                        : View.GONE
        );

        captureModeSingle.setChecked(
                mode == CAPTURE_SINGLE
        );

        captureModeMulti.setChecked(
                mode == CAPTURE_MULTI
        );

        captureModeGlobal.setChecked(
                mode == CAPTURE_GLOBAL
        );

        if (mode == CAPTURE_MULTI) {
            openTargetButton.setText("打开所选应用");
        } else {
            openTargetButton.setText(
                    getString(R.string.open_target)
            );
        }

        if (persist) {
            prefs().edit()
                    .putInt(
                            KEY_CAPTURE_MODE,
                            mode
                    )
                    .apply();
        }

        updateMultiSummary();
        refreshActionState();
    }

    private void showMultiAppPicker() {
        if (multiAppPickerDialog != null
                || isFinishing()
                || isDestroyed()) {
            return;
        }

        try {
            multiAppPickerDialog =
                    new MultiAppPickerDialog(
                            this,
                            selectedMultiPackages,
                            this::applyMultiSelection
                    );

            multiAppPickerDialog
                    .setOnDismissListener(
                            dialog ->
                                    multiAppPickerDialog =
                                            null
                    );

            multiAppPickerDialog.show();
        } catch (Throwable error) {
            multiAppPickerDialog = null;
            toast(
                    "打开多应用选择器失败："
                            + safeMessage(error)
            );
        }
    }

    private void applyMultiSelection(
            List<String> packages
    ) {
        selectedMultiPackages.clear();
        selectedMultiPackages.addAll(packages);

        prefs().edit()
                .putString(
                        KEY_MULTI_PACKAGES,
                        joinPackages(
                                selectedMultiPackages
                        )
                )
                .apply();

        updateMultiSummary();
        refreshActionState();
    }

    private void updateMultiSummary() {
        if (selectedMultiPackages.isEmpty()) {
            multiSelectedSummary.setText(
                    "尚未选择应用"
            );
            return;
        }

        StringBuilder text =
                new StringBuilder();

        int shown = 0;

        for (String pkg :
                selectedMultiPackages) {

            if (shown >= 4) {
                break;
            }

            if (shown > 0) {
                text.append("、");
            }

            text.append(
                    getAppLabelOrPackage(pkg)
            );

            shown++;
        }

        if (selectedMultiPackages.size()
                > shown) {
            text.append(" 等");
        }

        multiSelectedSummary.setText(
                "已选择 "
                        + selectedMultiPackages.size()
                        + " 个应用\n"
                        + text
        );
    }

    private String getAppLabelOrPackage(
            String pkg
    ) {
        try {
            ApplicationInfo ai =
                    getPackageManager()
                            .getApplicationInfo(
                                    pkg,
                                    0
                            );

            return String.valueOf(
                    getPackageManager()
                            .getApplicationLabel(ai)
            );
        } catch (Exception ignored) {
            return pkg;
        }
    }

    private static String joinPackages(
            Set<String> packages
    ) {
        StringBuilder out =
                new StringBuilder();

        for (String pkg : packages) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(pkg);
        }

        return out.toString();
    }

    private void restoreMultiPackages(
            String stored
    ) {
        selectedMultiPackages.clear();

        if (stored == null
                || stored.isEmpty()) {
            return;
        }

        String[] packages =
                stored.split("\\n");

        for (String pkg : packages) {
            if (!pkg.trim().isEmpty()) {
                selectedMultiPackages.add(
                        pkg.trim()
                );
            }
        }
    }

    private void toggleManualPackageInput() {
        manualPackageExpanded = !manualPackageExpanded;
        manualPackageContainer.setVisibility(
                manualPackageExpanded ? View.VISIBLE : View.GONE
        );
        manualPackageToggle.setText(
                manualPackageExpanded ? "▾ 收起手动包名" : "▸ 手动输入包名"
        );

        if (manualPackageExpanded) packageInput.requestFocus();
    }

    private void refreshShizukuState() {
        runOnUiThread(() -> {
            try {
                if (!Shizuku.pingBinder()) {
                    updateShizukuUi("未运行 / 未连接", "—", false, true);
                    return;
                }

                if (Shizuku.isPreV11()) {
                    updateShizukuUi("版本过旧", "—", false, true);
                    return;
                }

                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    int uid = Shizuku.getUid();
                    String mode = uid == 0 ? "Root" : "ADB Shell";
                    updateShizukuUi("已授权", mode, true, false);
                } else {
                    updateShizukuUi("等待授权", "—", false, false);
                }
            } catch (Throwable e) {
                updateShizukuUi("状态读取失败", "—", false, true);
            }

            refreshActionState();
        });
    }

    private void updateShizukuUi(String state, String backend, boolean success, boolean error) {
        permissionState.setText(state);
        backendText.setText(backend);

        int textColor;
        int chipBg;
        String chipText;

        if (success) {
            textColor = getColor(R.color.status_success);
            chipBg = getColor(R.color.status_success_container);
            chipText = "✓ Shizuku 已连接";
        } else if (error) {
            textColor = getColor(R.color.status_error);
            chipBg = getColor(R.color.status_error_container);
            chipText = "Shizuku 未连接";
        } else {
            textColor = getColor(R.color.status_warning);
            chipBg = getColor(R.color.status_warning_container);
            chipText = "Shizuku 待授权";
        }

        permissionState.setTextColor(textColor);
        heroShizukuChip.setText(chipText);
        heroShizukuChip.setTextColor(textColor);
        heroShizukuChip.setChipBackgroundColor(
                android.content.res.ColorStateList.valueOf(chipBg)
        );
    }

    private void refreshActionState() {
        boolean hasSingleTarget =
                selectedPackage != null
                        && !selectedPackage.isEmpty();

        boolean hasMultiTargets =
                !selectedMultiPackages.isEmpty();

        boolean hasRequiredTarget =
                captureMode == CAPTURE_GLOBAL
                        || (captureMode
                                    == CAPTURE_SINGLE
                                && hasSingleTarget)
                        || (captureMode
                                    == CAPTURE_MULTI
                                && hasMultiTargets);

        boolean recording =
                prefs().getBoolean(
                        KEY_RECORDING,
                        false
                );

        boolean hasLogFile =
                currentLogPath != null
                        && new File(
                                currentLogPath
                        ).isFile();

        startButton.setEnabled(
                hasRequiredTarget
                        && !recording
        );

        openTargetButton.setEnabled(
                (captureMode == CAPTURE_SINGLE
                        && hasSingleTarget)
                        || (captureMode == CAPTURE_MULTI
                        && hasMultiTargets)
        );

        stopButton.setEnabled(recording);
        exportButton.setEnabled(hasLogFile);

        snapshotButton.setEnabled(
                captureMode == CAPTURE_GLOBAL
                        || (captureMode
                                    == CAPTURE_SINGLE
                                && hasSingleTarget)
                        || (captureMode
                                    == CAPTURE_MULTI
                                && hasMultiTargets)
        );

        viewFullLogButton.setEnabled(
                hasLogFile
        );

        historyLogButton.setEnabled(true);

        chooseTargetButton.setEnabled(
                !recording
        );

        chooseMultiTargetButton.setEnabled(
                !recording
        );

        manualPackageToggle.setEnabled(
                !recording
        );

        captureModeSingle.setEnabled(
                !recording
        );

        captureModeMulti.setEnabled(
                !recording
        );

        captureModeGlobal.setEnabled(
                !recording
        );

        startButton.setText(
                recording
                        ? "正在记录"
                        : getString(
                                R.string.start_recording
                        )
        );

        if (recording) {
            recordingStateChip.setText(
                    "● 正在记录"
            );

            recordingStateChip.setTextColor(
                    getColor(
                            R.color.status_success
                    )
            );

            recordingStateChip
                    .setChipBackgroundColor(
                            android.content.res
                                    .ColorStateList
                                    .valueOf(
                                            getColor(
                                                    R.color
                                                            .status_success_container
                                            )
                                    )
                    );
        } else {
            recordingStateChip.setText(
                    "已停止"
            );

            recordingStateChip.setTextColor(
                    getColor(
                            R.color
                                    .md_theme_onSurfaceVariant
                    )
            );

            recordingStateChip
                    .setChipBackgroundColor(
                            android.content.res
                                    .ColorStateList
                                    .valueOf(
                                            getColor(
                                                    R.color
                                                            .md_theme_surfaceContainer
                                            )
                                    )
                    );
        }
    }

    private boolean requestShizukuPermission(boolean startAfterGrant) {
        pendingStartAfterPermission = startAfterGrant;

        try {
            if (!Shizuku.pingBinder()) {
                pendingStartAfterPermission = false;

                new MaterialAlertDialogBuilder(this)
                        .setTitle("Shizuku 尚未运行")
                        .setMessage("请先打开 Shizuku，并通过无线调试 / ADB 或 Root 启动服务。")
                        .setPositiveButton("打开 Shizuku", (dialog, which) -> openShizukuManager())
                        .setNegativeButton("取消", null)
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
                new MaterialAlertDialogBuilder(this)
                        .setTitle("需要 Shizuku 授权")
                        .setMessage("ShizuLog 仅使用 Shizuku 权限读取你主动选择目标应用的 Logcat 日志。")
                        .setPositiveButton(
                                "继续授权",
                                (dialog, which) -> Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION)
                        )
                        .setNegativeButton(
                                "取消",
                                (dialog, which) -> pendingStartAfterPermission = false
                        )
                        .show();
            } else {
                Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION);
            }
            return false;
        } catch (Throwable e) {
            pendingStartAfterPermission = false;
            refreshShizukuState();
            toast("请求 Shizuku 授权失败：" + safeMessage(e));
            return false;
        }
    }

    private void openShizukuManager() {
        Intent launch = getPackageManager()
                .getLaunchIntentForPackage("moe.shizuku.privileged.api");

        if (launch == null) {
            toast("未找到 Shizuku，请先安装 Shizuku");
            return;
        }
        startActivity(launch);
    }

    private void showAppPicker() {
        if (appPickerDialog != null || isFinishing() || isDestroyed()) return;

        try {
            appPickerDialog = new AppPickerDialog(
                    this,
                    (label, packageName) -> applyTarget(label, packageName)
            );
            appPickerDialog.setOnDismissListener(dialog -> appPickerDialog = null);
            appPickerDialog.show();
        } catch (Throwable error) {
            appPickerDialog = null;
            toast("打开应用选择器失败：" + safeMessage(error));
        }
    }

    private void selectTypedPackage() {
        String pkg = textOf(packageInput).trim();

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
            selectedLabel.setText(label + "\n" + pkg + "\nUID " + ai.uid);

            Drawable icon = getPackageManager().getApplicationIcon(ai);
            targetAppIcon.setImageDrawable(icon);
        } catch (Exception e) {
            selectedLabel.setText(label + "\n" + pkg);
            targetAppIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        prefs().edit()
                .putString(KEY_TARGET_PACKAGE, pkg)
                .putString(KEY_TARGET_LABEL, label)
                .apply();

        refreshActionState();
    }

    private void startCapture() {
        if (captureMode == CAPTURE_SINGLE) {
            selectTypedPackageSilently();

            if (selectedPackage.isEmpty()) {
                toast("先选择目标应用");
                return;
            }
        } else if (captureMode == CAPTURE_MULTI) {
            if (selectedMultiPackages.isEmpty()) {
                toast("先选择至少一个应用");
                return;
            }
        }

        if (captureMode == CAPTURE_GLOBAL) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("开始全局 Logcat？")
                    .setMessage(
                            "全局模式不会按应用 UID 过滤，会记录 Shizuku 当前权限可读取的 main / system / crash 日志。日志量可能快速增长，并可能包含其他应用、账号、路径、网络请求等敏感信息。"
                    )
                    .setPositiveButton(
                            "开始全局记录",
                            (dialog, which) ->
                                    continueStartCapture()
                    )
                    .setNegativeButton(
                            "取消",
                            null
                    )
                    .show();
            return;
        }

        continueStartCapture();
    }

    private void continueStartCapture() {
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
        try {
            ArrayList<String> packages =
                    new ArrayList<>();

            ArrayList<String> labels =
                    new ArrayList<>();

            ArrayList<Integer> uids =
                    new ArrayList<>();

            String status;

            if (captureMode == CAPTURE_SINGLE) {
                selectTypedPackageSilently();

                if (selectedPackage.isEmpty()) {
                    return;
                }

                ApplicationInfo ai =
                        getPackageManager()
                                .getApplicationInfo(
                                        selectedPackage,
                                        0
                                );

                packages.add(
                        selectedPackage
                );

                labels.add(
                        selectedAppLabel == null
                                        || selectedAppLabel
                                                .isEmpty()
                                ? getAppLabelOrPackage(
                                        selectedPackage
                                )
                                : selectedAppLabel
                );

                uids.add(ai.uid);

                status =
                        "已启动单应用日志采集，UID="
                                + ai.uid;
            } else if (captureMode
                    == CAPTURE_MULTI) {

                for (String pkg :
                        selectedMultiPackages) {
                    try {
                        ApplicationInfo ai =
                                getPackageManager()
                                        .getApplicationInfo(
                                                pkg,
                                                0
                                        );

                        packages.add(pkg);
                        labels.add(
                                getAppLabelOrPackage(
                                        pkg
                                )
                        );
                        uids.add(ai.uid);
                    } catch (Exception ignored) {}
                }

                if (packages.isEmpty()) {
                    toast(
                            "所选应用当前都不可用"
                    );
                    return;
                }

                status =
                        "已启动多应用日志采集："
                                + packages.size()
                                + " 个应用";
            } else {
                status =
                        "已启动全局 Logcat 记录";
            }

            String[] packageArray =
                    packages.toArray(
                            new String[0]
                    );

            String[] labelArray =
                    labels.toArray(
                            new String[0]
                    );

            int[] uidArray =
                    new int[uids.size()];

            for (int i = 0;
                 i < uids.size();
                 i++) {
                uidArray[i] = uids.get(i);
            }

            prefs().edit()
                    .putBoolean(
                            KEY_RECORDING,
                            true
                    )
                    .putInt(
                            KEY_CAPTURE_MODE,
                            captureMode
                    )
                    .apply();

            refreshActionState();

            Intent service =
                    new Intent(
                            this,
                            LogCaptureService.class
                    )
                            .setAction(
                                    LogCaptureService
                                            .ACTION_START
                            )
                            .putExtra(
                                    LogCaptureService
                                            .EXTRA_MODE,
                                    captureMode
                            )
                            .putExtra(
                                    LogCaptureService
                                            .EXTRA_PACKAGES,
                                    packageArray
                            )
                            .putExtra(
                                    LogCaptureService
                                            .EXTRA_LABELS,
                                    labelArray
                            )
                            .putExtra(
                                    LogCaptureService
                                            .EXTRA_UIDS,
                                    uidArray
                            );

            if (captureMode
                    == CAPTURE_SINGLE
                    && packageArray.length > 0) {

                service.putExtra(
                        LogCaptureService
                                .EXTRA_PACKAGE,
                        packageArray[0]
                );

                service.putExtra(
                        LogCaptureService
                                .EXTRA_LABEL,
                        labelArray[0]
                );

                service.putExtra(
                        LogCaptureService
                                .EXTRA_UID,
                        uidArray[0]
                );
            }

            startForegroundService(service);

            screenBuffer.setLength(0);

            showEmptyLogState(
                    "等待日志",
                    captureMode == CAPTURE_GLOBAL
                            ? "全局记录已经开始，正在等待 Logcat 输出"
                            : "记录已经开始，正在等待目标应用输出日志"
            );

            setStatus(status);
        } catch (Exception e) {
            prefs().edit()
                    .putBoolean(
                            KEY_RECORDING,
                            false
                    )
                    .apply();

            refreshActionState();

            toast(
                    "启动记录失败："
                            + safeMessage(e)
            );
        }
    }

    private void selectTypedPackageSilently() {
        String typed = textOf(packageInput).trim();

        if (!typed.isEmpty() && !typed.equals(selectedPackage)) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(typed, 0);
                String label = String.valueOf(
                        getPackageManager().getApplicationLabel(ai)
                );
                applyTarget(label, typed);
            } catch (Exception ignored) {}
        }
    }

    private void launchTarget() {
        if (captureMode == CAPTURE_GLOBAL) {
            toast("全局模式没有单独的目标应用");
            return;
        }

        if (captureMode == CAPTURE_MULTI) {
            launchOneOfMultiTargets();
            return;
        }

        selectTypedPackageSilently();

        if (selectedPackage.isEmpty()) {
            toast("先选择目标应用");
            return;
        }

        launchPackage(selectedPackage);
    }

    private void launchOneOfMultiTargets() {
        if (selectedMultiPackages.isEmpty()) {
            toast("先选择至少一个应用");
            return;
        }

        List<String> packages =
                new ArrayList<>(selectedMultiPackages);

        String[] labels = new String[packages.size()];

        for (int i = 0; i < packages.size(); i++) {
            labels[i] = getAppLabelOrPackage(packages.get(i));
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("打开哪个应用？")
                .setItems(
                        labels,
                        (dialog, which) ->
                                launchPackage(packages.get(which))
                )
                .setNegativeButton("取消", null)
                .show();
    }

    private void launchPackage(String packageName) {
        Intent launch = getPackageManager()
                .getLaunchIntentForPackage(packageName);

        if (launch == null) {
            toast("这个应用没有可启动的主界面");
            return;
        }

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        prefs().edit()
                .putBoolean(KEY_TARGET_LAUNCHED, true)
                .apply();

        startActivity(launch);
    }

    private void stopCapture() {
        prefs().edit().putBoolean(KEY_RECORDING, false).apply();
        refreshActionState();

        Intent service = new Intent(this, LogCaptureService.class)
                .setAction(LogCaptureService.ACTION_STOP);
        startService(service);
        setStatus("已请求停止记录");
    }

    private void exportLog() {
        if (currentLogPath == null || !new File(currentLogPath).isFile()) {
            toast("还没有可导出的日志文件");
            return;
        }

        String name = new File(currentLogPath).getName();

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, name);

        startActivityForResult(intent, REQ_CREATE_DOCUMENT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQ_CREATE_DOCUMENT
                || resultCode != RESULT_OK
                || data == null) {
            return;
        }

        Uri uri = data.getData();
        if (uri == null || currentLogPath == null) return;

        try (FileInputStream in = new FileInputStream(currentLogPath);
             OutputStream out = getContentResolver().openOutputStream(uri, "w")) {

            if (out == null) throw new IllegalStateException("无法打开导出位置");

            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }

            out.flush();
            toast("日志已导出");
        } catch (Exception e) {
            toast("导出失败：" + safeMessage(e));
        }
    }

    private void appendLog(String line) {
        if (line == null) return;

        boolean followTail = isLogNearBottom();
        screenBuffer.append(line);
        if (!line.endsWith("\\n")) {
            screenBuffer.append('\n');
        }

        if (screenBuffer.length() > MAX_SCREEN_CHARS) {
            int cut = screenBuffer.length() - MAX_SCREEN_CHARS;
            int newline = screenBuffer.indexOf("\n", cut);
            screenBuffer.delete(0, newline >= 0 ? newline + 1 : cut);
        }

        scheduleLogRender();

        if (followTail) {
            uiHandler.postDelayed(
                    this::scrollLogToBottomWithoutFocus,
                    110L
            );
        }

        appendedLineCounter++;
        if (appendedLineCounter >= 25) {
            appendedLineCounter = 0;
            updateLogMeta();
        }
    }


    private boolean isLogNearBottom() {
        if (logScroll == null
                || logText == null
                || logScroll.getVisibility() != View.VISIBLE) {
            return true;
        }

        int remaining =
                logText.getHeight()
                        - logScroll.getScrollY()
                        - logScroll.getHeight();

        return remaining <= dpToPx(72);
    }

    private void scrollLogToBottomWithoutFocus() {
        if (logScroll == null
                || logText == null
                || logScroll.getVisibility() != View.VISIBLE) {
            return;
        }

        int y = Math.max(
                0,
                logText.getHeight()
                        - logScroll.getHeight()
        );

        logScroll.scrollTo(0, y);
    }

    private int dpToPx(int dp) {
        return Math.round(
                dp * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    private void scheduleLogRender() {
        if (pendingLogRender != null) return;

        pendingLogRender = () -> {
            pendingLogRender = null;
            renderFilteredLog();
        };

        uiHandler.postDelayed(pendingLogRender, 80L);
    }

    private void renderFilteredLog() {
        String raw = screenBuffer.toString();

        if (raw.isEmpty()) {
            showEmptyLogState(
                    "暂无日志",
                    "开始记录后，目标应用的日志会显示在这里"
            );
            updateFilterSummary(0, 0);
            return;
        }

        String query = textOf(logSearchInput).trim().toLowerCase(Locale.ROOT);
        String[] lines = raw.split("\n", -1);
        StringBuilder filtered = new StringBuilder();
        int matched = 0;
        int total = 0;

        for (String line : lines) {
            if (line.isEmpty()) continue;
            total++;

            if (!matchesSeverity(line)) continue;

            if (!query.isEmpty()
                    && !line.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }

            filtered.append(line).append('\n');
            matched++;
        }

        updateFilterSummary(matched, total);

        if (filtered.length() == 0) {
            showEmptyLogState(
                    "没有匹配日志",
                    "调整搜索关键词或日志级别筛选"
            );
            return;
        }

        showLogConsole();
        logText.setText(filtered);
    }

    private boolean matchesSeverity(String line) {
        if (logFilterMode == FILTER_ALL) return true;

        String priority = readPriority(line);
        boolean errorKeyword = containsErrorKeyword(line);

        if (logFilterMode == FILTER_ERROR) {
            return "E".equals(priority)
                    || "F".equals(priority)
                    || errorKeyword;
        }

        return "W".equals(priority)
                || "E".equals(priority)
                || "F".equals(priority)
                || errorKeyword;
    }

    private static String readPriority(String line) {
        Matcher matcher = THREADTIME_PRIORITY.matcher(line);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean containsErrorKeyword(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("fatal exception")
                || lower.contains("caused by:")
                || lower.contains("androidruntime")
                || lower.contains("exception:")
                || lower.contains(" error")
                || lower.contains("native crash")
                || lower.contains("signal ");
    }

    private void updateFilterSummary(int matched, int total) {
        String mode;
        if (logFilterMode == FILTER_ERROR) mode = "ERROR";
        else if (logFilterMode == FILTER_WARN) mode = "WARN+";
        else mode = "全部";

        String query = textOf(logSearchInput).trim();

        if (total == 0) {
            logFilterSummary.setText("暂无可筛选日志");
        } else if (query.isEmpty() && logFilterMode == FILTER_ALL) {
            logFilterSummary.setText("显示全部 " + total + " 行");
        } else {
            String suffix = query.isEmpty() ? "" : " · 搜索“" + query + "”";
            logFilterSummary.setText(
                    mode + " · 匹配 " + matched + " / " + total + " 行" + suffix
            );
        }
    }

    private void showLogConsole() {
        logEmptyState.setVisibility(View.GONE);
        logScroll.setVisibility(View.VISIBLE);
    }

    private void showEmptyLogState(String title, String message) {
        logScroll.setVisibility(View.GONE);
        logEmptyState.setVisibility(View.VISIBLE);
        logEmptyTitle.setText(title);
        logEmptyMessage.setText(message);
    }


    private void scheduleLogRenderPreservePage() {
        final int oldPageY =
                mainScroll == null
                        ? 0
                        : mainScroll.getScrollY();

        final int oldLogY =
                logScroll == null
                        ? 0
                        : logScroll.getScrollY();

        if (pendingLogRender != null) {
            uiHandler.removeCallbacks(pendingLogRender);
            pendingLogRender = null;
        }

        pendingLogRender = () -> {
            pendingLogRender = null;
            renderFilteredLog();

            if (mainScroll != null) {
                mainScroll.post(
                        () -> mainScroll.scrollTo(
                                0,
                                oldPageY
                        )
                );
            }

            if (logScroll != null
                    && logScroll.getVisibility()
                            == View.VISIBLE) {
                logScroll.post(
                        () -> logScroll.scrollTo(
                                0,
                                oldLogY
                        )
                );
            }
        };

        uiHandler.postDelayed(
                pendingLogRender,
                60L
        );
    }

    private void openCurrentFullLog() {
        if (currentLogPath == null
                || !new File(currentLogPath).isFile()) {
            toast("还没有可查看的完整日志");
            return;
        }

        Intent intent =
                new Intent(
                        this,
                        FullLogActivity.class
                );

        intent.putExtra(
                FullLogActivity.EXTRA_FILE,
                currentLogPath
        );

        startActivity(intent);
    }

    private void registerStatusReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(LogCaptureService.ACTION_LINE);
        filter.addAction(LogCaptureService.ACTION_STATUS);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    99
            );
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreUiState();
        refreshShizukuState();

        SharedPreferences preferences = prefs();

        if (preferences.getBoolean(KEY_TARGET_LAUNCHED, false)) {
            preferences.edit()
                    .putBoolean(KEY_TARGET_LAUNCHED, false)
                    .apply();
            requestCrashSnapshot();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void restoreUiState() {
        if (packageInput == null
                || selectedLabel == null
                || statusText == null
                || logText == null) {
            return;
        }

        SharedPreferences preferences = prefs();

        captureMode = preferences.getInt(
                KEY_CAPTURE_MODE,
                CAPTURE_SINGLE
        );

        restoreMultiPackages(
                preferences.getString(
                        KEY_MULTI_PACKAGES,
                        ""
                )
        );

        String pkg = preferences.getString(KEY_TARGET_PACKAGE, "");
        String label = preferences.getString(KEY_TARGET_LABEL, "");

        if (pkg != null && !pkg.isEmpty()) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);

                if (label == null || label.isEmpty()) {
                    label = String.valueOf(
                            getPackageManager().getApplicationLabel(ai)
                    );
                }

                selectedPackage = pkg;
                selectedAppLabel = label;
                packageInput.setText(pkg);
                selectedLabel.setText(label + "\n" + pkg + "\nUID " + ai.uid);
                targetAppIcon.setImageDrawable(
                        getPackageManager().getApplicationIcon(ai)
                );
            } catch (Exception e) {
                selectedPackage = pkg;
                selectedAppLabel = label == null ? "" : label;
                packageInput.setText(pkg);

                selectedLabel.setText(
                        "上次目标：" + selectedAppLabel
                                + "\n" + pkg
                                + "\n当前未找到安装包"
                );

                targetAppIcon.setImageResource(
                        android.R.drawable.sym_def_app_icon
                );
            }
        } else {
            selectedLabel.setText("尚未选择目标应用");
            targetAppIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        setCaptureMode(
                captureMode,
                false
        );

        updateMultiSummary();

        String path = preferences.getString(KEY_CURRENT_LOG_PATH, null);

        if (path != null && new File(path).isFile()) {
            currentLogPath = path;
            loadLogTail(path);
        } else if (screenBuffer.length() == 0) {
            showEmptyLogState(
                    "暂无日志",
                    "开始记录后，目标应用的日志会显示在这里"
            );
        }

        String lastStatus = preferences.getString(KEY_LAST_STATUS, "");
        boolean recording = preferences.getBoolean(KEY_RECORDING, false);

        if (lastStatus != null && !lastStatus.isEmpty()) {
            setStatus((recording ? "● 正在记录\n" : "") + lastStatus);
        } else {
            setStatus(recording ? "● 正在记录" : "等待开始记录");
        }

        updateLogMeta();
        refreshActionState();
    }

    private void loadLogTail(String path) {
        File file = new File(path);
        if (!file.isFile()) return;

        long maxBytes = MAX_SCREEN_CHARS * 2L;
        long start = Math.max(0L, file.length() - maxBytes);

        try (FileInputStream in = new FileInputStream(file)) {
            long remainingSkip = start;

            while (remainingSkip > 0) {
                long skipped = in.skip(remainingSkip);
                if (skipped <= 0) break;
                remainingSkip -= skipped;
            }

            int capacity = (int) Math.min(
                    maxBytes,
                    Math.max(0L, file.length() - start)
            );

            byte[] data = new byte[Math.max(capacity, 1)];
            int total = 0;
            int count;

            while (total < data.length
                    && (count = in.read(data, total, data.length - total)) > 0) {
                total += count;
            }

            String text = new String(data, 0, total, StandardCharsets.UTF_8);

            if (text.length() > MAX_SCREEN_CHARS) {
                text = text.substring(text.length() - MAX_SCREEN_CHARS);
            }

            screenBuffer.setLength(0);
            screenBuffer.append(text);
            renderFilteredLog();
        } catch (Exception ignored) {}
    }

    private void requestCrashSnapshot() {
        Intent service = new Intent(this, LogCaptureService.class)
                .setAction(LogCaptureService.ACTION_SNAPSHOT);

        try {
            startService(service);
        } catch (Exception ignored) {}
    }

    private void setStatus(String text) {
        statusText.setText(text);
    }

    private void updateLogMeta() {
        if (currentLogPath == null || currentLogPath.isEmpty()) {
            logPathText.setText("日志路径：—");
            logSizeText.setText("日志大小：0 B");
            refreshActionState();
            return;
        }

        File file = new File(currentLogPath);
        logPathText.setText("日志路径：" + currentLogPath);
        logSizeText.setText(
                "日志大小：" + humanSize(file.isFile() ? file.length() : 0)
        );
        refreshActionState();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";

        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);

        return String.format(Locale.US, "%.2f MB", kb / 1024.0);
    }

    private static String textOf(TextInputEditText editText) {
        return editText.getText() == null
                ? ""
                : editText.getText().toString();
    }

    @Override
    protected void onDestroy() {
        if (pendingLogRender != null) {
            uiHandler.removeCallbacks(pendingLogRender);
            pendingLogRender = null;
        }

        if (appPickerDialog != null) {
            try {
                appPickerDialog.dismiss();
            } catch (Throwable ignored) {}
            appPickerDialog = null;
        }

        if (multiAppPickerDialog != null) {
            try {
                multiAppPickerDialog.dismiss();
            } catch (Throwable ignored) {}
            multiAppPickerDialog = null;
        }

        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {}

        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);

        super.onDestroy();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null
                ? error.getClass().getSimpleName()
                : message;
    }
}
