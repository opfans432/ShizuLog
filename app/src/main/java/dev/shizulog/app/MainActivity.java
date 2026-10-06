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
    private static final int FILTER_DEBUG = 1;
    private static final int FILTER_INFO = 2;
    private static final int FILTER_WARN = 3;
    private static final int FILTER_ERROR = 4;

    private static final Pattern THREADTIME_PRIORITY = Pattern.compile(
            "^\\s*\\d{2}-\\d{2}\\s+"
                    + "\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+"
                    + "(?:\\S+\\s+)?"
                    + "\\d+\\s+\\d+\\s+"
                    + "([VDIWEF])\\s+"
    );

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder screenBuffer = new StringBuilder();

    private TextInputEditText packageInput;
    private TextInputEditText logSearchInput;
    private TextInputEditText realtimeTagInput;
    private TextInputEditText realtimePidInput;
    private TextInputEditText realtimeProcessInput;
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
    private TextView recordStatsText;
    private TextView realtimeLevelStats;
    private TextView realtimeTopTags;
    private TextView realtimeProcessStats;
    private ScrollView logScroll;
    private ScrollView mainScroll;
    private ImageView targetAppIcon;
    private Chip heroShizukuChip;
    private Chip recordingStateChip;
    private Chip logFilterAll;
    private Chip logFilterDebug;
    private Chip logFilterInfo;
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
    private MaterialButton analyzeCrashButton;
    private MaterialButton diagnosticPackButton;
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
    private RealTimeLogAnalyzer realtimeAnalyzer;
    private long lastRealtimeUiUpdateMs;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::refreshShizukuState;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::refreshShizukuState;

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQ_SHIZUKU_PERMISSION) return;
                refreshShizukuState();

                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    toast(getString(R.string.shizuku_granted));
                    if (pendingStartAfterPermission) {
                        pendingStartAfterPermission = false;
                        startCaptureInternal();
                    }
                } else {
                    pendingStartAfterPermission = false;
                    toast(getString(R.string.shizuku_denied));
                }
            };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (LogCaptureService.ACTION_LINE.equals(action)) {
                appendLog(intent.getStringExtra(LogCaptureService.EXTRA_LINE));
            } else if (LogCaptureService.ACTION_STATS.equals(action)) {
                updateRecordStats(intent);
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

                CaptureSessionManager.updateActive(
                        MainActivity.this,
                        status,
                        path
                );

                if (status != null
                        && (status.contains(getString(R.string.status_stop_marker))
                        || status.contains(getString(R.string.status_record_end_marker))
                        || status.contains(getString(R.string.status_capture_end_marker)))) {
                    CaptureSessionManager.finishActive(
                            MainActivity.this,
                            status
                    );
                }

                refreshActionState();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        realtimeAnalyzer = new RealTimeLogAnalyzer(this);
        setContentView(R.layout.activity_main);

        bindViews();
        applySystemBarInsets();
        setupActions();

        restoreUiState();
        CaptureSessionManager.recoverStaleActive(
                this,
                prefs().getBoolean(KEY_RECORDING, false)
        );
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
        realtimeTagInput = findViewById(R.id.realtimeTagInput);
        realtimePidInput = findViewById(R.id.realtimePidInput);
        realtimeProcessInput = findViewById(R.id.realtimeProcessInput);
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
        logFilterDebug = findViewById(R.id.logFilterDebug);
        logFilterInfo = findViewById(R.id.logFilterInfo);
        logFilterWarn = findViewById(R.id.logFilterWarn);
        logFilterError = findViewById(R.id.logFilterError);
        captureModeSingle = findViewById(R.id.captureModeSingle);
        captureModeMulti = findViewById(R.id.captureModeMulti);
        captureModeGlobal = findViewById(R.id.captureModeGlobal);
        logFilterSummary = findViewById(R.id.logFilterSummary);
        recordStatsText = findViewById(R.id.recordStatsText);
        realtimeLevelStats = findViewById(R.id.realtimeLevelStats);
        realtimeTopTags = findViewById(R.id.realtimeTopTags);
        realtimeProcessStats = findViewById(R.id.realtimeProcessStats);
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
        analyzeCrashButton = findViewById(R.id.analyzeCrashButton);
        diagnosticPackButton = findViewById(R.id.diagnosticPackButton);
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
            setStatus(getString(R.string.snapshot_requested));
        });

        viewFullLogButton.setOnClickListener(
                v -> openCurrentFullLog()
        );

        analyzeCrashButton.setOnClickListener(
                v -> analyzeCurrentCrash()
        );

        diagnosticPackButton.setOnClickListener(
                v -> openDiagnosticPack()
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
                    getString(R.string.preview_cleared),
                    getString(R.string.preview_clear_desc)
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

        addRealtimeFilterWatcher(realtimeTagInput);
        addRealtimeFilterWatcher(realtimePidInput);
        addRealtimeFilterWatcher(realtimeProcessInput);

        logFilterAll.setOnClickListener(v -> {
            logFilterMode = FILTER_ALL;
            scheduleLogRenderPreservePage();
        });

        logFilterDebug.setOnClickListener(v -> {
            logFilterMode = FILTER_DEBUG;
            scheduleLogRenderPreservePage();
        });

        logFilterInfo.setOnClickListener(v -> {
            logFilterMode = FILTER_INFO;
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


    private void addRealtimeFilterWatcher(TextInputEditText input) {
        if (input == null) return;

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                    CharSequence s,
                    int start,
                    int count,
                    int after
            ) {}

            @Override
            public void onTextChanged(
                    CharSequence s,
                    int start,
                    int before,
                    int count
            ) {
                scheduleLogRenderPreservePage();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
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
            openTargetButton.setText(getString(R.string.open_selected_app));
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
            toast(getString(R.string.multi_picker_open_failed, safeMessage(error)));
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
                    getString(R.string.no_app_selected)
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
            text.append(getString(R.string.selected_apps_more));
        }

        multiSelectedSummary.setText(
                getString(
                        R.string.selected_apps_summary,
                        selectedMultiPackages.size(),
                        text
                )
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
                manualPackageExpanded
                        ? getString(R.string.manual_package_expanded)
                        : getString(R.string.manual_package_collapsed)
        );

        if (manualPackageExpanded) packageInput.requestFocus();
    }

    private void refreshShizukuState() {
        runOnUiThread(() -> {
            try {
                if (!Shizuku.pingBinder()) {
                    updateShizukuUi(getString(R.string.shizuku_not_running), getString(R.string.unavailable), false, true);
                    return;
                }

                if (Shizuku.isPreV11()) {
                    updateShizukuUi(getString(R.string.shizuku_old_version), getString(R.string.unavailable), false, true);
                    return;
                }

                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    int uid = Shizuku.getUid();
                    String mode = uid == 0 ? getString(R.string.shizuku_root) : getString(R.string.shizuku_adb_shell);
                    updateShizukuUi(getString(R.string.shizuku_authorized), mode, true, false);
                } else {
                    updateShizukuUi(getString(R.string.shizuku_waiting_authorization), getString(R.string.unavailable), false, false);
                }
            } catch (Throwable e) {
                updateShizukuUi(getString(R.string.shizuku_read_failed), getString(R.string.unavailable), false, true);
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
            chipText = getString(R.string.shizuku_chip_connected);
        } else if (error) {
            textColor = getColor(R.color.status_error);
            chipBg = getColor(R.color.status_error_container);
            chipText = getString(R.string.shizuku_chip_disconnected);
        } else {
            textColor = getColor(R.color.status_warning);
            chipBg = getColor(R.color.status_warning_container);
            chipText = getString(R.string.shizuku_chip_pending);
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

        analyzeCrashButton.setEnabled(
                hasLogFile
        );

        diagnosticPackButton.setEnabled(
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
                        ? getString(R.string.recording)
                        : getString(R.string.start_recording)
        );

        if (recording) {
            recordingStateChip.setText(
                    getString(R.string.recording_indicator)
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
                    getString(R.string.stopped)
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
                        .setTitle(getString(R.string.shizuku_not_running_title))
                        .setMessage(getString(R.string.shizuku_not_running_message))
                        .setPositiveButton(getString(R.string.open_shizuku), (dialog, which) -> openShizukuManager())
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show();
                return false;
            }

            if (Shizuku.isPreV11()) {
                pendingStartAfterPermission = false;
                toast(getString(R.string.shizuku_old_version_update));
                return false;
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                pendingStartAfterPermission = false;
                refreshShizukuState();
                return true;
            }

            if (Shizuku.shouldShowRequestPermissionRationale()) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.shizuku_permission_title))
                        .setMessage(getString(R.string.shizuku_permission_message))
                        .setPositiveButton(
                                getString(R.string.continue_authorization),
                                (dialog, which) -> Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION)
                        )
                        .setNegativeButton(
                                getString(R.string.cancel),
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
            toast(getString(R.string.permission_request_failed, safeMessage(e)));
            return false;
        }
    }

    private void openShizukuManager() {
        Intent launch = getPackageManager()
                .getLaunchIntentForPackage("moe.shizuku.privileged.api");

        if (launch == null) {
            toast(getString(R.string.shizuku_not_found));
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
            toast(getString(R.string.app_picker_open_failed, safeMessage(error)));
        }
    }

    private void selectTypedPackage() {
        String pkg = textOf(packageInput).trim();

        if (pkg.isEmpty()) {
            toast(getString(R.string.package_required));
            return;
        }

        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            String label = String.valueOf(getPackageManager().getApplicationLabel(ai));
            applyTarget(label, pkg);
        } catch (PackageManager.NameNotFoundException e) {
            toast(getString(R.string.package_not_found));
        }
    }

    private void applyTarget(String label, String pkg) {
        selectedPackage = pkg;
        selectedAppLabel = label;
        packageInput.setText(pkg);

        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            selectedLabel.setText(getString(R.string.selected_app_details_uid, label, pkg, ai.uid));

            Drawable icon = getPackageManager().getApplicationIcon(ai);
            targetAppIcon.setImageDrawable(icon);
        } catch (Exception e) {
            selectedLabel.setText(getString(R.string.selected_app_details, label, pkg));
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
                toast(getString(R.string.target_required));
                return;
            }
        } else if (captureMode == CAPTURE_MULTI) {
            if (selectedMultiPackages.isEmpty()) {
                toast(getString(R.string.multi_target_required));
                return;
            }
        }

        if (captureMode == CAPTURE_GLOBAL) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.global_logcat_confirm_title))
                    .setMessage(
                            getString(R.string.global_logcat_confirm_message)
                    )
                    .setPositiveButton(
                            getString(R.string.start_global_recording),
                            (dialog, which) ->
                                    continueStartCapture()
                    )
                    .setNegativeButton(
                            getString(R.string.cancel),
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
                        getString(R.string.single_recording_started, ai.uid);
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
                    toast(getString(R.string.selected_apps_unavailable));
                    return;
                }

                status =
                        getString(R.string.multi_recording_started, packages.size());
            } else {
                status = getString(R.string.global_recording_started);
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

            CaptureSessionManager.begin(
                    this,
                    captureMode,
                    packageArray,
                    labelArray,
                    uidArray
            );

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
            realtimeAnalyzer.reset();
            lastRealtimeUiUpdateMs = 0L;
            renderRealtimeAnalysis();

            if (recordStatsText != null) {
                recordStatsText.setText(
                        getString(R.string.stats_waiting)
                );
            }

            showEmptyLogState(
                    getString(R.string.open_status_waiting),
                    captureMode == CAPTURE_GLOBAL
                            ? getString(R.string.global_record_waiting)
                            : getString(R.string.record_waiting)
            );

            setStatus(status);
        } catch (Exception e) {
            CaptureSessionManager.finishActive(
                    this,
                    getString(R.string.start_failure, safeMessage(e))
            );

            prefs().edit()
                    .putBoolean(
                            KEY_RECORDING,
                            false
                    )
                    .apply();

            refreshActionState();

            toast(getString(R.string.record_start_failure, safeMessage(e)));
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
            toast(getString(R.string.global_mode_no_target));
            return;
        }

        if (captureMode == CAPTURE_MULTI) {
            launchOneOfMultiTargets();
            return;
        }

        selectTypedPackageSilently();

        if (selectedPackage.isEmpty()) {
            toast(getString(R.string.target_required));
            return;
        }

        launchPackage(selectedPackage);
    }

    private void launchOneOfMultiTargets() {
        if (selectedMultiPackages.isEmpty()) {
            toast(getString(R.string.multi_target_required));
            return;
        }

        List<String> packages =
                new ArrayList<>(selectedMultiPackages);

        String[] labels = new String[packages.size()];

        for (int i = 0; i < packages.size(); i++) {
            labels[i] = getAppLabelOrPackage(packages.get(i));
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.app_picker_title))
                .setItems(
                        labels,
                        (dialog, which) ->
                                launchPackage(packages.get(which))
                )
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void launchPackage(String packageName) {
        Intent launch = getPackageManager()
                .getLaunchIntentForPackage(packageName);

        if (launch == null) {
            toast(getString(R.string.no_launchable_activity));
            return;
        }

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        prefs().edit()
                .putBoolean(KEY_TARGET_LAUNCHED, true)
                .apply();

        startActivity(launch);
    }

    private void stopCapture() {
        CaptureSessionManager.finishActive(
                this,
                getString(R.string.user_stopped_recording)
        );

        prefs().edit().putBoolean(KEY_RECORDING, false).apply();
        refreshActionState();

        Intent service = new Intent(this, LogCaptureService.class)
                .setAction(LogCaptureService.ACTION_STOP);
        startService(service);
        setStatus(getString(R.string.stop_requested));
    }

    private void exportLog() {
        if (currentLogPath == null || !new File(currentLogPath).isFile()) {
            toast(getString(R.string.export_no_log));
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

            if (out == null) throw new IllegalStateException(getString(R.string.cannot_open_export_location));

            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }

            out.flush();
            toast(getString(R.string.log_exported));
        } catch (Exception e) {
            toast(getString(R.string.export_failed, safeMessage(e)));
        }
    }

    private void appendLog(String line) {
        if (line == null) return;

        realtimeAnalyzer.onLine(line);
        maybeRenderRealtimeAnalysis();

        boolean followTail = isLogNearBottom();
        screenBuffer.append(line);
        if (!line.endsWith("\n")) {
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
                    getString(R.string.no_logs),
                    getString(R.string.no_logs_desc)
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
            if (!matchesRealtimeFields(line)) continue;

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
                    getString(R.string.no_matching_logs),
                    getString(R.string.adjust_realtime_filter)
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
        int rank = priorityRank(priority);

        if (logFilterMode == FILTER_ERROR) {
            return rank >= 5 || errorKeyword;
        }

        if (logFilterMode == FILTER_WARN) {
            return rank >= 4 || errorKeyword;
        }

        if (logFilterMode == FILTER_INFO) {
            return rank >= 3 || errorKeyword;
        }

        return rank >= 2 || errorKeyword;
    }

    private static int priorityRank(String priority) {
        if (priority == null || priority.isEmpty()) return 0;

        switch (priority.charAt(0)) {
            case 'V': return 1;
            case 'D': return 2;
            case 'I': return 3;
            case 'W': return 4;
            case 'E': return 5;
            case 'F': return 6;
            default: return 0;
        }
    }

    private boolean matchesRealtimeFields(String line) {
        String lower = line.toLowerCase(Locale.ROOT);

        String tag = textOf(realtimeTagInput).trim();
        if (!tag.isEmpty()) {
            String lineTag = readTag(line);
            if (!lineTag.toLowerCase(Locale.ROOT)
                    .contains(tag.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }

        String pid = textOf(realtimePidInput).trim();
        if (!pid.isEmpty()) {
            String linePid = readPid(line);
            if (!pid.equals(linePid)) {
                return false;
            }
        }

        String process = textOf(realtimeProcessInput).trim();
        if (!process.isEmpty()
                && !lower.contains(process.toLowerCase(Locale.ROOT))) {
            return false;
        }

        return true;
    }

    private static String readPid(String line) {
        Matcher matcher = Pattern.compile(
                "^\\\\s*\\\\d{2}-\\\\d{2}\\\\s+"
                        + "\\\\d{2}:\\\\d{2}:\\\\d{2}\\\\.\\\\d+\\\\s+"
                        + "\\\\S+\\\\s+"
                        + "(\\\\d+)\\\\s+"
        ).matcher(line);

        return matcher.find() ? matcher.group(1) : "";
    }

    private static String readTag(String line) {
        Matcher matcher = Pattern.compile(
                "^\\\\s*\\\\d{2}-\\\\d{2}\\\\s+"
                        + "\\\\d{2}:\\\\d{2}:\\\\d{2}\\\\.\\\\d+\\\\s+"
                        + "\\\\S+\\\\s+\\\\d+\\\\s+\\\\d+\\\\s+"
                        + "[VDIWEF]\\\\s+([^:]+):"
        ).matcher(line);

        return matcher.find() ? matcher.group(1).trim() : "";
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
        if (logFilterMode == FILTER_ERROR) mode = "E+";
        else if (logFilterMode == FILTER_WARN) mode = "W+";
        else if (logFilterMode == FILTER_INFO) mode = "I+";
        else if (logFilterMode == FILTER_DEBUG) mode = "D+";
        else mode = getString(R.string.log_all);

        String query = textOf(logSearchInput).trim();
        String tag = textOf(realtimeTagInput).trim();
        String pid = textOf(realtimePidInput).trim();
        String process = textOf(realtimeProcessInput).trim();

        boolean noExtra = query.isEmpty()
                && tag.isEmpty()
                && pid.isEmpty()
                && process.isEmpty()
                && logFilterMode == FILTER_ALL;

        if (total == 0) {
            logFilterSummary.setText(getString(R.string.no_filterable_logs));
            return;
        }

        if (noExtra) {
            logFilterSummary.setText(getString(R.string.realtime_preview_summary, total));
            return;
        }

        StringBuilder suffix = new StringBuilder();
        if (!tag.isEmpty()) suffix.append(" · Tag=").append(tag);
        if (!pid.isEmpty()) suffix.append(" · PID=").append(pid);
        if (!process.isEmpty()) suffix.append(getString(R.string.process_suffix, process));
        if (!query.isEmpty()) suffix.append(getString(R.string.search_suffix, query));

        logFilterSummary.setText(
                getString(R.string.filter_summary, mode, matched, total, suffix.toString())
        );
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


    private void maybeRenderRealtimeAnalysis() {
        long now = System.currentTimeMillis();
        if (now - lastRealtimeUiUpdateMs < 500L) {
            return;
        }

        lastRealtimeUiUpdateMs = now;
        uiHandler.post(this::renderRealtimeAnalysis);
    }

    private void renderRealtimeAnalysis() {
        if (realtimeLevelStats == null
                || realtimeTopTags == null
                || realtimeProcessStats == null) {
            return;
        }

        RealTimeLogAnalyzer.Snapshot snapshot =
                realtimeAnalyzer.snapshot();

        realtimeLevelStats.setText(
                getString(
                        R.string.realtime_stats,
                        snapshot.verbose,
                        snapshot.debug,
                        snapshot.info,
                        snapshot.warn,
                        snapshot.error,
                        snapshot.fatal,
                        snapshot.linesPerSecond,
                        snapshot.errorsPerMinute
                )
        );

        realtimeTopTags.setText(
                getString(R.string.top_tag, snapshot.topTagsText())
        );

        realtimeProcessStats.setText(
                getString(
                        R.string.pid_stats,
                        snapshot.activePidCount,
                        snapshot.pidChanges,
                        snapshot.latestProcessChange
                )
        );
    }

    private void openDiagnosticPack() {
        if (currentLogPath == null || !new File(currentLogPath).isFile()) {
            toast(getString(R.string.export_no_log));
            return;
        }
        Intent intent = new Intent(this, DiagnosticPackActivity.class);
        intent.putExtra(DiagnosticPackActivity.EXTRA_FILE, currentLogPath);
        startActivity(intent);
    }

    private void analyzeCurrentCrash() {
        if (currentLogPath == null
                || !new File(currentLogPath).isFile()) {
            toast(getString(R.string.no_analyzable_log));
            return;
        }

        Intent intent =
                new Intent(
                        this,
                        CrashAnalysisActivity.class
                );

        intent.putExtra(
                CrashAnalysisActivity.EXTRA_FILE,
                currentLogPath
        );

        startActivity(intent);
    }

    private void openCurrentFullLog() {
        if (currentLogPath == null
                || !new File(currentLogPath).isFile()) {
            toast(getString(R.string.no_full_log));
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

    private void updateRecordStats(Intent intent) {
        if (recordStatsText == null) return;

        long lines = intent.getLongExtra(
                LogCaptureService.EXTRA_LINES,
                0L
        );

        long warn = intent.getLongExtra(
                LogCaptureService.EXTRA_WARN_COUNT,
                0L
        );

        long error = intent.getLongExtra(
                LogCaptureService.EXTRA_ERROR_COUNT,
                0L
        );

        long bytes = intent.getLongExtra(
                LogCaptureService.EXTRA_TOTAL_BYTES,
                0L
        );

        long rate = intent.getLongExtra(
                LogCaptureService.EXTRA_RATE,
                0L
        );

        int pidCount = intent.getIntExtra(
                LogCaptureService.EXTRA_PID_COUNT,
                0
        );

        int part = intent.getIntExtra(
                LogCaptureService.EXTRA_PART,
                1
        );

        StringBuilder text = new StringBuilder();

        text.append(getString(R.string.statistics_prefix))
                .append(lines)
                .append(getString(R.string.line_count_suffix))
                .append(" · W ")
                .append(warn)
                .append(" · E ")
                .append(error)
                .append(" · ")
                .append(rate)
                .append(getString(R.string.lines_per_second_suffix))
                .append(" · ")
                .append(humanSize(bytes));

        if (captureMode != CAPTURE_GLOBAL) {
            text.append(" · PID ")
                    .append(pidCount);
        }

        if (captureMode == CAPTURE_GLOBAL
                && part > 1) {
            text.append(getString(R.string.volume_suffix, part));
        }

        recordStatsText.setText(text.toString());
    }

    private void registerStatusReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(LogCaptureService.ACTION_LINE);
        filter.addAction(LogCaptureService.ACTION_STATUS);
        filter.addAction(LogCaptureService.ACTION_STATS);

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
                selectedLabel.setText(getString(R.string.selected_app_details_uid, label, pkg, ai.uid));
                targetAppIcon.setImageDrawable(
                        getPackageManager().getApplicationIcon(ai)
                );
            } catch (Exception e) {
                selectedPackage = pkg;
                selectedAppLabel = label == null ? "" : label;
                packageInput.setText(pkg);

                selectedLabel.setText(
                        getString(R.string.last_target, selectedAppLabel)
                                + "\n" + pkg
                                + "\n" + getString(R.string.package_not_installed)
                );

                targetAppIcon.setImageResource(
                        android.R.drawable.sym_def_app_icon
                );
            }
        } else {
            selectedLabel.setText(getString(R.string.not_selected_target));
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
                    getString(R.string.no_logs),
                    getString(R.string.no_logs_desc)
            );
        }

        String lastStatus = preferences.getString(KEY_LAST_STATUS, "");
        boolean recording = preferences.getBoolean(KEY_RECORDING, false);

        if (lastStatus != null && !lastStatus.isEmpty()) {
            setStatus((recording ? getString(R.string.recording_indicator) + "\n" : "") + lastStatus);
        } else {
            setStatus(recording ? getString(R.string.recording_indicator) : getString(R.string.waiting_to_start));
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
            logPathText.setText(getString(R.string.log_path_empty));
            logSizeText.setText(getString(R.string.log_size_empty));
            refreshActionState();
            return;
        }

        File file = new File(currentLogPath);
        logPathText.setText(getString(R.string.log_path_value, currentLogPath));
        logSizeText.setText(
                getString(R.string.log_size_value, humanSize(file.isFile() ? file.length() : 0))
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
