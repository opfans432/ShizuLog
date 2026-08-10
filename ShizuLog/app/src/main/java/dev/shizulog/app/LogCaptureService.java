package dev.shizulog.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class LogCaptureService extends Service {
    public static final String ACTION_START = "dev.shizulog.app.START";
    public static final String ACTION_STOP = "dev.shizulog.app.STOP";
    public static final String ACTION_LINE = "dev.shizulog.app.LINE";
    public static final String ACTION_STATUS = "dev.shizulog.app.STATUS";
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_UID = "uid";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_LINE = "line";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_FILE = "file";

    private static final String CHANNEL_ID = "log_capture";
    private static final int NOTIFICATION_ID = 1001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Process logcatProcess;
    private volatile boolean running;
    private File currentFile;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCapture("已停止");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            String pkg = intent.getStringExtra(EXTRA_PACKAGE);
            String label = intent.getStringExtra(EXTRA_LABEL);
            int uid = intent.getIntExtra(EXTRA_UID, -1);
            if (pkg == null || pkg.trim().isEmpty() || uid < 0) {
                sendStatus("目标信息无效", null);
                stopSelf();
                return START_NOT_STICKY;
            }
            startForeground(NOTIFICATION_ID, buildNotification(label == null ? pkg : label));
            startCapture(pkg, label == null ? pkg : label, uid);
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void startCapture(String pkg, String label, int uid) {
        stopCapture(null);
        running = true;
        executor.execute(() -> {
            BufferedWriter writer = null;
            try {
                if (!Shizuku.pingBinder()) {
                    throw new IllegalStateException("Shizuku 未运行或 Binder 未连接");
                }
                if (Shizuku.isPreV11()) {
                    throw new IllegalStateException("Shizuku 版本过旧，需要 API 11+");
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("尚未授予 Shizuku 权限");
                }

                File dir = new File(getExternalFilesDir(null), "logs");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建日志目录: " + dir);
                }
                String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                currentFile = new File(dir, sanitize(pkg) + "_" + time + ".log");
                writer = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(currentFile, false), StandardCharsets.UTF_8));
                writer.write("# ShizuLog\n");
                writer.write("# package=" + pkg + "\n");
                writer.write("# uid=" + uid + "\n");
                writer.write("# shizuku_uid=" + Shizuku.getUid() + "\n");
                writer.write("# started=" + new Date() + "\n\n");
                writer.flush();

                sendStatus("已通过 Shizuku 开始记录 " + label + "（UID " + uid + "）", currentFile);

                // UID 为系统查询出的整数，命令其余部分均为固定文本，不拼接用户输入。
                String cmd = "exec logcat -b main -b system -b crash --uid=" + uid
                        + " -v threadtime -T 1 2>&1";
                logcatProcess = Shizuku.newProcess(
                        new String[]{"/system/bin/sh", "-c", cmd}, null, null);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(logcatProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        writer.write(line);
                        writer.newLine();
                        writer.flush();
                        sendLine(line);
                    }
                }

                if (running) {
                    int exit = logcatProcess.waitFor();
                    sendStatus("Shizuku logcat 已退出，退出码=" + exit
                            + "。若 Shizuku 被停止/重启，请重新授权后再开始。", currentFile);
                }
            } catch (Throwable e) {
                sendStatus("记录失败: " + e.getClass().getSimpleName() + ": " + safeMessage(e), currentFile);
            } finally {
                running = false;
                if (writer != null) {
                    try { writer.close(); } catch (Exception ignored) {}
                }
                Process p = logcatProcess;
                if (p != null) {
                    try { p.destroy(); } catch (Throwable ignored) {}
                }
                logcatProcess = null;
                stopForeground(STOP_FOREGROUND_REMOVE);
            }
        });
    }

    private void stopCapture(String status) {
        running = false;
        Process p = logcatProcess;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) {}
        }
        if (status != null) sendStatus(status, currentFile);
    }

    private void sendLine(String line) {
        Intent i = new Intent(ACTION_LINE).setPackage(getPackageName());
        i.putExtra(EXTRA_LINE, line);
        sendBroadcast(i);
    }

    private void sendStatus(String text, File file) {
        Intent i = new Intent(ACTION_STATUS).setPackage(getPackageName());
        i.putExtra(EXTRA_STATUS, text);
        if (file != null) i.putExtra(EXTRA_FILE, file.getAbsolutePath());
        sendBroadcast(i);
    }

    private Notification buildNotification(String label) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, LogCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("正在记录: " + label)
                .setContentText("Shizuku 日志采集中，点击返回")
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "停止", stopPi).build())
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_ID, getString(dev.shizulog.app.R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            c.setDescription("保持 Shizuku 日志采集任务运行");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String safeMessage(Throwable e) {
        String m = e.getMessage();
        return m == null ? "无详细信息" : m;
    }

    @Override
    public void onDestroy() {
        stopCapture(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
