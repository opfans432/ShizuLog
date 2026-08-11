package dev.shizulog.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class LogCaptureService extends Service {

    public static final int MODE_SINGLE = 0;
    public static final int MODE_MULTI = 1;
    public static final int MODE_GLOBAL = 2;

    public static final String ACTION_START =
            "dev.shizulog.app.START";
    public static final String ACTION_STOP =
            "dev.shizulog.app.STOP";
    public static final String ACTION_SNAPSHOT =
            "dev.shizulog.app.SNAPSHOT";
    public static final String ACTION_LINE =
            "dev.shizulog.app.LINE";
    public static final String ACTION_STATUS =
            "dev.shizulog.app.STATUS";

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_PACKAGES = "packages";
    public static final String EXTRA_UIDS = "uids";
    public static final String EXTRA_LABELS = "labels";

    // Backward compatibility with v1.x single-target calls.
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_UID = "uid";
    public static final String EXTRA_LABEL = "label";

    public static final String EXTRA_LINE = "line";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_FILE = "file";

    private static final String CHANNEL_ID = "log_capture";
    private static final int NOTIFICATION_ID = 1001;

    private static final String PREFS = "shizulog_state";
    private static final String KEY_TARGET_PACKAGE = "target_package";
    private static final String KEY_TARGET_LABEL = "target_label";
    private static final String KEY_TARGET_UID = "target_uid";
    private static final String KEY_CURRENT_LOG_PATH = "current_log_path";
    private static final String KEY_LAST_STATUS = "last_status";
    private static final String KEY_RECORDING = "recording";
    private static final String KEY_CAPTURE_MODE = "capture_mode";
    private static final String KEY_SERVICE_PACKAGES = "service_packages";
    private static final String KEY_SERVICE_UIDS = "service_uids";

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final ExecutorService snapshotExecutor =
            Executors.newSingleThreadExecutor();

    private final Object fileWriteLock = new Object();

    private volatile Process logcatProcess;
    private volatile boolean running;

    private File currentFile;
    private volatile int currentMode = MODE_SINGLE;
    private volatile String[] currentPackages = new String[0];
    private volatile int[] currentUids = new int[0];

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        SharedPreferences p = prefs();

        if (intent == null) {
            if (!p.getBoolean(KEY_RECORDING, false)) {
                return START_NOT_STICKY;
            }

            int mode = p.getInt(
                    KEY_CAPTURE_MODE,
                    MODE_SINGLE
            );

            String[] packages =
                    splitLines(
                            p.getString(
                                    KEY_SERVICE_PACKAGES,
                                    ""
                            )
                    );

            int[] uids =
                    parseUidCsv(
                            p.getString(
                                    KEY_SERVICE_UIDS,
                                    ""
                            )
                    );

            if (mode != MODE_GLOBAL
                    && uids.length == 0) {
                return START_NOT_STICKY;
            }

            String display =
                    buildDisplayLabel(
                            mode,
                            packages,
                            null
                    );

            startForeground(
                    NOTIFICATION_ID,
                    buildNotification(display)
            );

            startCapture(
                    mode,
                    packages,
                    null,
                    uids
            );

            return START_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_SNAPSHOT.equals(action)) {
            captureCrashSnapshot();

            return running
                    ? START_STICKY
                    : START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            p.edit()
                    .putBoolean(
                            KEY_RECORDING,
                            false
                    )
                    .apply();

            stopCapture("已停止");
            stopSelf();

            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(action)) {
            return running
                    ? START_STICKY
                    : START_NOT_STICKY;
        }

        int mode = intent.getIntExtra(
                EXTRA_MODE,
                MODE_SINGLE
        );

        String[] packages =
                intent.getStringArrayExtra(
                        EXTRA_PACKAGES
                );

        String[] labels =
                intent.getStringArrayExtra(
                        EXTRA_LABELS
                );

        int[] uids =
                intent.getIntArrayExtra(
                        EXTRA_UIDS
                );

        if (packages == null) {
            packages = new String[0];
        }

        if (labels == null) {
            labels = new String[0];
        }

        if (uids == null) {
            uids = new int[0];
        }

        // Backward compatible single-app extras.
        if (mode == MODE_SINGLE
                && packages.length == 0) {

            String pkg =
                    intent.getStringExtra(
                            EXTRA_PACKAGE
                    );

            String label =
                    intent.getStringExtra(
                            EXTRA_LABEL
                    );

            int uid =
                    intent.getIntExtra(
                            EXTRA_UID,
                            -1
                    );

            if (pkg != null
                    && !pkg.trim().isEmpty()
                    && uid >= 0) {

                packages =
                        new String[]{pkg};

                labels =
                        new String[]{
                                label == null
                                        ? pkg
                                        : label
                        };

                uids =
                        new int[]{uid};
            }
        }

        if (mode != MODE_GLOBAL
                && uids.length == 0) {

            sendStatus(
                    "目标信息无效",
                    null
            );

            stopSelf();

            return START_NOT_STICKY;
        }

        currentMode = mode;
        currentPackages = packages.clone();
        currentUids = dedupeUids(uids);

        String display =
                buildDisplayLabel(
                        mode,
                        packages,
                        labels
                );

        SharedPreferences.Editor editor =
                p.edit()
                        .putInt(
                                KEY_CAPTURE_MODE,
                                mode
                        )
                        .putString(
                                KEY_SERVICE_PACKAGES,
                                joinLines(packages)
                        )
                        .putString(
                                KEY_SERVICE_UIDS,
                                joinUids(currentUids)
                        )
                        .putBoolean(
                                KEY_RECORDING,
                                true
                        );

        if (mode == MODE_SINGLE
                && packages.length > 0
                && currentUids.length > 0) {

            editor.putString(
                    KEY_TARGET_PACKAGE,
                    packages[0]
            );

            editor.putString(
                    KEY_TARGET_LABEL,
                    display
            );

            editor.putInt(
                    KEY_TARGET_UID,
                    currentUids[0]
            );
        }

        editor.apply();

        startForeground(
                NOTIFICATION_ID,
                buildNotification(display)
        );

        startCapture(
                mode,
                packages,
                labels,
                currentUids
        );

        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void startCapture(
            int mode,
            String[] packages,
            String[] labels,
            int[] uids
    ) {
        stopCapture(null);

        currentMode = mode;
        currentPackages =
                packages == null
                        ? new String[0]
                        : packages.clone();

        currentUids =
                dedupeUids(
                        uids == null
                                ? new int[0]
                                : uids
                );

        prefs().edit()
                .putInt(
                        KEY_CAPTURE_MODE,
                        mode
                )
                .putString(
                        KEY_SERVICE_PACKAGES,
                        joinLines(
                                currentPackages
                        )
                )
                .putString(
                        KEY_SERVICE_UIDS,
                        joinUids(
                                currentUids
                        )
                )
                .putBoolean(
                        KEY_RECORDING,
                        true
                )
                .apply();

        running = true;

        executor.execute(() -> {
            BufferedWriter writer = null;

            StringBuilder broadcastBuffer =
                    new StringBuilder();

            int linesSinceFlush = 0;
            long lastFileFlush =
                    System.currentTimeMillis();

            long lastBroadcast =
                    System.currentTimeMillis();

            try {
                ensureShizukuReady();

                File dir = new File(
                        getExternalFilesDir(null),
                        "logs"
                );

                if (!dir.exists()
                        && !dir.mkdirs()) {

                    throw new IllegalStateException(
                            "无法创建日志目录: "
                                    + dir
                    );
                }

                String time =
                        new SimpleDateFormat(
                                "yyyyMMdd_HHmmss",
                                Locale.US
                        ).format(new Date());

                String filePrefix;

                if (mode == MODE_GLOBAL) {
                    filePrefix = "global";
                } else if (mode == MODE_MULTI) {
                    filePrefix =
                            "multi_"
                                    + Math.max(
                                            1,
                                            packages.length
                                    )
                                    + "apps";
                } else {
                    filePrefix =
                            packages.length > 0
                                    ? sanitize(
                                            packages[0]
                                    )
                                    : "single";
                }

                currentFile = new File(
                        dir,
                        filePrefix
                                + "_"
                                + time
                                + ".log"
                );

                prefs().edit()
                        .putString(
                                KEY_CURRENT_LOG_PATH,
                                currentFile
                                        .getAbsolutePath()
                        )
                        .apply();

                writer =
                        new BufferedWriter(
                                new OutputStreamWriter(
                                        new FileOutputStream(
                                                currentFile,
                                                false
                                        ),
                                        StandardCharsets.UTF_8
                                )
                        );

                writer.write("# ShizuLog\n");
                writer.write(
                        "# mode="
                                + modeName(mode)
                                + "\n"
                );

                writer.write(
                        "# packages="
                                + joinComma(packages)
                                + "\n"
                );

                writer.write(
                        "# uids="
                                + joinUids(currentUids)
                                + "\n"
                );

                writer.write(
                        "# shizuku_uid="
                                + Shizuku.getUid()
                                + "\n"
                );

                writer.write(
                        "# started="
                                + new Date()
                                + "\n\n"
                );

                writer.flush();

                String display =
                        buildDisplayLabel(
                                mode,
                                packages,
                                labels
                        );

                sendStatus(
                        mode == MODE_GLOBAL
                                ? "已通过 Shizuku 开始全局 Logcat 记录"
                                : "已通过 Shizuku 开始记录 " + display,
                        currentFile
                );

                String cmd =
                        buildLogcatCommand(
                                mode,
                                currentUids
                        );

                logcatProcess =
                        Shizuku.newProcess(
                                new String[]{
                                        "/system/bin/sh",
                                        "-c",
                                        cmd
                                },
                                null,
                                null
                        );

                try (BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             logcatProcess
                                                     .getInputStream(),
                                             StandardCharsets.UTF_8
                                     )
                             )) {

                    String line;

                    while (running
                            && (line =
                                    reader.readLine())
                                    != null) {

                        synchronized (fileWriteLock) {
                            writer.write(line);
                            writer.newLine();

                            linesSinceFlush++;

                            long now =
                                    System.currentTimeMillis();

                            if (linesSinceFlush >= 20
                                    || now - lastFileFlush
                                            >= 250L) {

                                writer.flush();

                                linesSinceFlush = 0;
                                lastFileFlush = now;
                            }
                        }

                        broadcastBuffer
                                .append(line)
                                .append('\n');

                        long now =
                                System.currentTimeMillis();

                        if (broadcastBuffer.length()
                                    >= 16 * 1024
                                || now - lastBroadcast
                                    >= 80L) {

                            sendLine(
                                    broadcastBuffer
                                            .toString()
                            );

                            broadcastBuffer.setLength(0);
                            lastBroadcast = now;
                        }
                    }
                }

                synchronized (fileWriteLock) {
                    writer.flush();
                }

                if (broadcastBuffer.length() > 0) {
                    sendLine(
                            broadcastBuffer.toString()
                    );
                }

                if (running) {
                    int exit =
                            logcatProcess.waitFor();

                    sendStatus(
                            "Shizuku logcat 已退出，退出码="
                                    + exit
                                    + "。若 Shizuku 被停止/重启，请重新授权后再开始。",
                            currentFile
                    );
                }
            } catch (Throwable e) {
                sendStatus(
                        "记录失败: "
                                + e.getClass()
                                        .getSimpleName()
                                + ": "
                                + safeMessage(e),
                        currentFile
                );
            } finally {
                running = false;

                prefs().edit()
                        .putBoolean(
                                KEY_RECORDING,
                                false
                        )
                        .apply();

                if (writer != null) {
                    try {
                        writer.close();
                    } catch (Exception ignored) {}
                }

                Process p = logcatProcess;

                if (p != null) {
                    try {
                        p.destroy();
                    } catch (Throwable ignored) {}
                }

                logcatProcess = null;

                stopForeground(
                        STOP_FOREGROUND_REMOVE
                );
            }
        });
    }

    private void ensureShizukuReady() {
        if (!Shizuku.pingBinder()) {
            throw new IllegalStateException(
                    "Shizuku 未运行或 Binder 未连接"
            );
        }

        if (Shizuku.isPreV11()) {
            throw new IllegalStateException(
                    "Shizuku 版本过旧，需要 API 11+"
            );
        }

        if (Shizuku.checkSelfPermission()
                != PackageManager
                        .PERMISSION_GRANTED) {

            throw new SecurityException(
                    "尚未授予 Shizuku 权限"
            );
        }
    }

    private static String buildLogcatCommand(
            int mode,
            int[] uids
    ) {
        StringBuilder cmd =
                new StringBuilder(
                        "exec logcat"
                                + " -b main"
                                + " -b system"
                                + " -b crash"
                );

        if (mode != MODE_GLOBAL
                && uids.length > 0) {

            cmd.append(" --uid=")
                    .append(
                            joinUids(uids)
                    );
        }

        cmd.append(
                " -v threadtime"
                        + " -T 1"
                        + " 2>&1"
        );

        return cmd.toString();
    }

    private void stopCapture(String status) {
        running = false;

        Process p = logcatProcess;

        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {}
        }

        if (status != null) {
            sendStatus(
                    status,
                    currentFile
            );
        }
    }

    private void sendLine(String chunk) {
        Intent i =
                new Intent(ACTION_LINE)
                        .setPackage(
                                getPackageName()
                        );

        i.putExtra(
                EXTRA_LINE,
                chunk
        );

        sendBroadcast(i);
    }

    private void sendStatus(
            String text,
            File file
    ) {
        SharedPreferences.Editor e =
                prefs().edit()
                        .putString(
                                KEY_LAST_STATUS,
                                text == null
                                        ? ""
                                        : text
                        );

        if (file != null) {
            e.putString(
                    KEY_CURRENT_LOG_PATH,
                    file.getAbsolutePath()
            );
        }

        e.apply();

        Intent i =
                new Intent(ACTION_STATUS)
                        .setPackage(
                                getPackageName()
                        );

        i.putExtra(
                EXTRA_STATUS,
                text
        );

        if (file != null) {
            i.putExtra(
                    EXTRA_FILE,
                    file.getAbsolutePath()
            );
        }

        sendBroadcast(i);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(
                PREFS,
                MODE_PRIVATE
        );
    }

    private void captureCrashSnapshot() {
        snapshotExecutor.execute(() -> {
            File file = currentFile;

            if (file == null) {
                String path =
                        prefs().getString(
                                KEY_CURRENT_LOG_PATH,
                                null
                        );

                if (path != null) {
                    file = new File(path);
                }
            }

            if (file == null
                    || !file.isFile()) {
                return;
            }

            final File targetFile = file;

            try {
                ensureShizukuReady();

                int mode = currentMode;

                String[] packages =
                        currentPackages;

                int[] uids =
                        currentUids;

                if (!running) {
                    mode =
                            prefs().getInt(
                                    KEY_CAPTURE_MODE,
                                    MODE_SINGLE
                            );

                    packages =
                            splitLines(
                                    prefs().getString(
                                            KEY_SERVICE_PACKAGES,
                                            ""
                                    )
                            );

                    uids =
                            parseUidCsv(
                                    prefs().getString(
                                            KEY_SERVICE_UIDS,
                                            ""
                                    )
                            );
                }

                String cmd =
                        buildSnapshotCommand(
                                mode,
                                packages,
                                uids
                        );

                Process snapshotProcess =
                        Shizuku.newProcess(
                                new String[]{
                                        "/system/bin/sh",
                                        "-c",
                                        cmd
                                },
                                null,
                                null
                        );

                StringBuilder out =
                        new StringBuilder();

                try (BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             snapshotProcess
                                                     .getInputStream(),
                                             StandardCharsets.UTF_8
                                     )
                             )) {

                    String line;

                    while ((line =
                                    reader.readLine())
                                    != null) {

                        out.append(line)
                                .append('\n');
                    }
                }

                snapshotProcess.waitFor();

                if (out.length() == 0) {
                    return;
                }

                synchronized (fileWriteLock) {
                    try (BufferedWriter append =
                                 new BufferedWriter(
                                         new OutputStreamWriter(
                                                 new FileOutputStream(
                                                         targetFile,
                                                         true
                                                 ),
                                                 StandardCharsets.UTF_8
                                         )
                                 )) {

                        append.write(
                                "\n\n# ===== AUTO CRASH SNAPSHOT =====\n"
                        );

                        append.write(
                                "# mode="
                                        + modeName(mode)
                                        + "\n"
                        );

                        append.write(
                                "# targets="
                                        + joinComma(packages)
                                        + "\n"
                        );

                        append.write(
                                "# captured="
                                        + new Date()
                                        + "\n"
                        );

                        append.write(
                                out.toString()
                        );

                        append.write(
                                "# ===== END CRASH SNAPSHOT =====\n"
                        );
                    }
                }

                sendStatus(
                        "已补抓崩溃快照并追加到当前日志",
                        targetFile
                );
            } catch (Throwable ignored) {}
        });
    }

    private static String buildSnapshotCommand(
            int mode,
            String[] packages,
            int[] uids
    ) {
        if (mode == MODE_GLOBAL) {
            return "{ "
                    + "logcat -d -b crash"
                    + " -v threadtime -t 2200; "
                    + "logcat -d -b main -b system"
                    + " -v threadtime -t 3200"
                    + " AndroidRuntime:E"
                    + " ActivityManager:I"
                    + " ActivityTaskManager:I"
                    + " DEBUG:F"
                    + " libc:F"
                    + " '*:S'; "
                    + "} 2>&1 | tail -n 2600";
        }

        String uidCsv =
                joinUids(
                        dedupeUids(uids)
                );

        StringBuilder cmd =
                new StringBuilder();

        cmd.append("{ ");

        if (!uidCsv.isEmpty()) {
            cmd.append(
                    "logcat -d"
                            + " -b crash"
                            + " -b main"
                            + " -b system"
                            + " --uid="
            ).append(uidCsv)
                    .append(
                            " -v threadtime"
                                    + " -t 2400; "
                    );
        }

        String grepPattern =
                buildPackageGrepPattern(
                        packages
                );

        if (!grepPattern.isEmpty()) {
            cmd.append(
                    "{ logcat -d"
                            + " -b main"
                            + " -b system"
                            + " -v threadtime"
                            + " -t 2800"
                            + " AndroidRuntime:E"
                            + " ActivityManager:I"
                            + " ActivityTaskManager:I"
                            + " DEBUG:F"
                            + " libc:F"
                            + " '*:S'; }"
            );

            cmd.append(
                    " 2>&1 | grep -E"
                            + " -B 30"
                            + " -A 180 '"
            ).append(grepPattern)
                    .append("'; ");
        }

        cmd.append(
                "} 2>&1 | tail -n 2600"
        );

        return cmd.toString();
    }

    private static String buildPackageGrepPattern(
            String[] packages
    ) {
        if (packages == null
                || packages.length == 0) {
            return "";
        }

        StringBuilder out =
                new StringBuilder();

        for (String pkg : packages) {
            if (pkg == null
                    || pkg.isEmpty()) {
                continue;
            }

            String safe =
                    pkg.replaceAll(
                            "[^a-zA-Z0-9._]",
                            ""
                    );

            if (safe.isEmpty()) {
                continue;
            }

            if (out.length() > 0) {
                out.append("|");
            }

            out.append(
                    safe.replace(
                            ".",
                            "\\."
                    )
            );
        }

        return out.toString();
    }

    private Notification buildNotification(
            String label
    ) {
        Intent open =
                new Intent(
                        this,
                        MainActivity.class
                )
                        .addFlags(
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        );

        PendingIntent content =
                PendingIntent.getActivity(
                        this,
                        0,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        Intent stop =
                new Intent(
                        this,
                        LogCaptureService.class
                )
                        .setAction(
                                ACTION_STOP
                        );

        PendingIntent stopPi =
                PendingIntent.getService(
                        this,
                        1,
                        stop,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        return new Notification.Builder(
                this,
                CHANNEL_ID
        )
                .setSmallIcon(
                        android.R.drawable
                                .ic_menu_info_details
                )
                .setContentTitle(
                        "正在记录: " + label
                )
                .setContentText(
                        "Shizuku 日志采集中，点击返回"
                )
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(
                        new Notification.Action.Builder(
                                android.R.drawable
                                        .ic_media_pause,
                                "停止",
                                stopPi
                        ).build()
                )
                .build();
    }

    private static String buildDisplayLabel(
            int mode,
            String[] packages,
            String[] labels
    ) {
        if (mode == MODE_GLOBAL) {
            return "全局 Logcat";
        }

        if (mode == MODE_MULTI) {
            int count =
                    packages == null
                            ? 0
                            : packages.length;

            return count + " 个应用";
        }

        if (labels != null
                && labels.length > 0
                && labels[0] != null
                && !labels[0].isEmpty()) {
            return labels[0];
        }

        if (packages != null
                && packages.length > 0) {
            return packages[0];
        }

        return "目标应用";
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O) {

            NotificationChannel c =
                    new NotificationChannel(
                            CHANNEL_ID,
                            getString(
                                    R.string.notification_channel
                            ),
                            NotificationManager
                                    .IMPORTANCE_LOW
                    );

            c.setDescription(
                    "保持 Shizuku 日志采集任务运行"
            );

            getSystemService(
                    NotificationManager.class
            ).createNotificationChannel(c);
        }
    }

    private static int[] dedupeUids(
            int[] source
    ) {
        Set<Integer> unique =
                new LinkedHashSet<>();

        if (source != null) {
            for (int uid : source) {
                if (uid >= 0) {
                    unique.add(uid);
                }
            }
        }

        int[] out =
                new int[unique.size()];

        int i = 0;

        for (Integer uid : unique) {
            out[i++] = uid;
        }

        return out;
    }

    private static String joinUids(
            int[] uids
    ) {
        StringBuilder out =
                new StringBuilder();

        if (uids != null) {
            for (int uid : uids) {
                if (uid < 0) {
                    continue;
                }

                if (out.length() > 0) {
                    out.append(",");
                }

                out.append(uid);
            }
        }

        return out.toString();
    }

    private static int[] parseUidCsv(
            String csv
    ) {
        if (csv == null
                || csv.trim().isEmpty()) {
            return new int[0];
        }

        String[] parts =
                csv.split(",");

        List<Integer> values =
                new ArrayList<>();

        for (String part : parts) {
            try {
                int uid =
                        Integer.parseInt(
                                part.trim()
                        );

                if (uid >= 0) {
                    values.add(uid);
                }
            } catch (Exception ignored) {}
        }

        int[] out =
                new int[values.size()];

        for (int i = 0;
             i < values.size();
             i++) {
            out[i] = values.get(i);
        }

        return dedupeUids(out);
    }

    private static String joinLines(
            String[] values
    ) {
        if (values == null
                || values.length == 0) {
            return "";
        }

        StringBuilder out =
                new StringBuilder();

        for (String value : values) {
            if (value == null
                    || value.isEmpty()) {
                continue;
            }

            if (out.length() > 0) {
                out.append('\n');
            }

            out.append(value);
        }

        return out.toString();
    }

    private static String[] splitLines(
            String text
    ) {
        if (text == null
                || text.isEmpty()) {
            return new String[0];
        }

        List<String> out =
                new ArrayList<>();

        for (String line :
                text.split("\\n")) {

            if (!line.trim().isEmpty()) {
                out.add(line.trim());
            }
        }

        return out.toArray(
                new String[0]
        );
    }

    private static String joinComma(
            String[] values
    ) {
        if (values == null
                || values.length == 0) {
            return "";
        }

        StringBuilder out =
                new StringBuilder();

        for (String value : values) {
            if (value == null
                    || value.isEmpty()) {
                continue;
            }

            if (out.length() > 0) {
                out.append(", ");
            }

            out.append(value);
        }

        return out.toString();
    }

    private static String modeName(int mode) {
        if (mode == MODE_GLOBAL) {
            return "global";
        }

        if (mode == MODE_MULTI) {
            return "multi";
        }

        return "single";
    }

    private static String sanitize(String s) {
        return s.replaceAll(
                "[^a-zA-Z0-9._-]",
                "_"
        );
    }

    private static String safeMessage(Throwable e) {
        String m = e.getMessage();

        return m == null
                ? "无详细信息"
                : m;
    }

    @Override
    public void onDestroy() {
        stopCapture(null);
        executor.shutdownNow();
        snapshotExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
