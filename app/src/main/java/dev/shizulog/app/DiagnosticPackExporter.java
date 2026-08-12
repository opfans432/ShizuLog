package dev.shizulog.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.system.StructUtsname;

import rikka.shizuku.Shizuku;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DiagnosticPackExporter {

    private static final int COPY_BUFFER = 32 * 1024;
    private static final int PID_SCAN_BYTES = 1024 * 1024;

    private static final Pattern THREADTIME_UID = Pattern.compile(
            "^\\s*\\d{2}-\\d{2}\\s+"
                    + "\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+"
                    + "(\\d+)\\s+"
                    + "(\\d+)\\s+"
                    + "\\d+\\s+"
                    + "[VDIWEF]\\s+"
    );

    private DiagnosticPackExporter() {}

    public interface ProgressCallback {
        void onProgress(String message);
    }

    public static Result export(Context context, File logFile, ProgressCallback callback) {
        if (context == null) return Result.error("Context 不可用");
        if (logFile == null || !logFile.isFile()) return Result.error("当前没有可导出的日志文件");

        try {
            File baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (baseDir == null) return Result.error("应用 Documents 目录不可用");
            File exportDir = new File(baseDir, "diagnostic");
            if (!exportDir.exists() && !exportDir.mkdirs()) return Result.error("无法创建诊断包目录");

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File zipFile = new File(exportDir, "ShizuLog-Diagnostic-" + stamp + ".zip");

            notify(callback, "正在分析崩溃摘要…");
            CrashAnalyzer.Result crash = CrashAnalyzer.analyze(logFile);

            SharedPreferences prefs = context.getSharedPreferences("shizulog_state", Context.MODE_PRIVATE);
            String singlePackage = prefs.getString("target_package", "");
            String multiRaw = prefs.getString("multi_packages", "");
            int captureMode = prefs.getInt("capture_mode", 0);
            List<String> packages = collectPackages(singlePackage, multiRaw);

            notify(callback, "正在整理设备与目标应用信息…");
            Map<Integer, Set<Integer>> recentPids = scanRecentPids(logFile);
            String targetInfo = buildTargetInfo(context, packages, recentPids);

            notify(callback, "正在打包原始日志…");
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
                addText(zip, "README.txt", buildReadme());
                addText(zip, "shizulog-info.txt", buildMetadata(context, logFile, captureMode, packages));
                addText(zip, "device-info.txt", buildDeviceInfo());
                addText(zip, "target-apps.txt", targetInfo);
                addText(zip, "crash-summary.txt", crash == null || crash.summary == null || crash.summary.isEmpty() ? "未检测到明显崩溃" : crash.summary);
                addFile(zip, "log/" + sanitizeName(logFile.getName()), logFile);
            }

            notify(callback, "诊断包已生成");
            return Result.success(zipFile);
        } catch (Exception e) {
            return Result.error(e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
    }

    private static List<String> collectPackages(String singlePackage, String multiRaw) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (singlePackage != null && !singlePackage.trim().isEmpty()) set.add(singlePackage.trim());
        if (multiRaw != null && !multiRaw.trim().isEmpty()) {
            for (String item : multiRaw.split(",")) {
                String pkg = item.trim();
                if (!pkg.isEmpty()) set.add(pkg);
            }
        }
        return new ArrayList<>(set);
    }

    private static Map<Integer, Set<Integer>> scanRecentPids(File logFile) {
        Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            long start = Math.max(0L, logFile.length() - PID_SCAN_BYTES);
            raf.seek(start);
            if (start > 0) raf.readLine();
            String line;
            while ((line = raf.readLine()) != null) {
                String decoded = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                Matcher matcher = THREADTIME_UID.matcher(decoded);
                if (!matcher.find()) continue;
                int uid = Integer.parseInt(matcher.group(1));
                int pid = Integer.parseInt(matcher.group(2));
                result.computeIfAbsent(uid, ignored -> new LinkedHashSet<>()).add(pid);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static String buildReadme() {
        return "ShizuLog Diagnostic Pack\n========================\n\n"
                + "该 ZIP 由 ShizuLog 在设备本地生成，用于 Android 应用排错。\n\n"
                + "包含：当前原始 Logcat、崩溃摘要、设备信息、目标 App 信息、UID/PID 与 Shizuku 状态。\n\n"
                + "不会主动包含：ShizuLog 发布签名私钥、GitHub Actions Secrets、SIGNING-RECOVERY.env、其他应用私有文件。\n\n"
                + "注意：原始 Logcat 本身可能包含应用或系统写出的敏感信息，分享前请确认接收方可信。\n";
    }

    private static String buildMetadata(Context context, File logFile, int captureMode, List<String> packages) {
        StringBuilder out = new StringBuilder();
        PackageInfo self = getPackageInfo(context, context.getPackageName());
        out.append("generated=").append(new Date()).append('\n');
        out.append("shizulog_package=").append(context.getPackageName()).append('\n');
        if (self != null) {
            out.append("shizulog_version=").append(self.versionName).append('\n');
            out.append("shizulog_version_code=").append(Build.VERSION.SDK_INT >= 28 ? self.getLongVersionCode() : self.versionCode).append('\n');
        }
        out.append("capture_mode=").append(modeName(captureMode)).append('\n');
        out.append("targets=").append(String.join(",", packages)).append('\n');
        out.append("log_file=").append(logFile.getName()).append('\n');
        out.append("log_size_bytes=").append(logFile.length()).append('\n');
        out.append("log_last_modified=").append(new Date(logFile.lastModified())).append('\n');
        try {
            int uid = Shizuku.getUid();
            out.append("shizuku_uid=").append(uid).append('\n');
            out.append("shizuku_backend=").append(uid == 0 ? "root" : uid == 2000 ? "adb_shell" : "uid_" + uid).append('\n');
        } catch (Throwable ignored) { out.append("shizuku_uid=unavailable\n"); }
        try { out.append("shizuku_binder_alive=").append(Shizuku.pingBinder()).append('\n'); }
        catch (Throwable ignored) { out.append("shizuku_binder_alive=false\n"); }
        try { out.append("shizuku_permission=").append(Shizuku.checkSelfPermission()).append('\n'); }
        catch (Throwable ignored) { out.append("shizuku_permission=unavailable\n"); }
        return out.toString();
    }

    private static String buildDeviceInfo() {
        StringBuilder out = new StringBuilder();
        out.append("android_version=").append(Build.VERSION.RELEASE).append('\n');
        out.append("sdk=").append(Build.VERSION.SDK_INT).append('\n');
        out.append("manufacturer=").append(Build.MANUFACTURER).append('\n');
        out.append("brand=").append(Build.BRAND).append('\n');
        out.append("model=").append(Build.MODEL).append('\n');
        out.append("device=").append(Build.DEVICE).append('\n');
        out.append("product=").append(Build.PRODUCT).append('\n');
        out.append("hardware=").append(Build.HARDWARE).append('\n');
        out.append("supported_abis=").append(String.join(",", Build.SUPPORTED_ABIS)).append('\n');
        out.append("fingerprint=").append(Build.FINGERPRINT).append('\n');
        out.append("security_patch=").append(Build.VERSION.SECURITY_PATCH).append('\n');
        try { StructUtsname uname = Os.uname(); out.append("kernel=").append(uname.release).append('\n'); } catch (Throwable ignored) {}
        out.append("locale=").append(Locale.getDefault()).append('\n');
        return out.toString();
    }

    private static String buildTargetInfo(Context context, List<String> packages, Map<Integer, Set<Integer>> recentPids) {
        if (packages.isEmpty()) return "未选择目标应用\n";
        StringBuilder out = new StringBuilder();
        for (String pkg : packages) {
            PackageManager pm = context.getPackageManager();
            out.append("package=").append(pkg).append('\n');
            int uid = -1;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                uid = info.uid;
                out.append("label=").append(pm.getApplicationLabel(info)).append('\n');
                out.append("uid=").append(uid).append('\n');
                Set<Integer> pids = recentPids.get(uid);
                out.append("recent_pids=").append(pids == null || pids.isEmpty() ? "" : joinInts(pids)).append('\n');
            } catch (Exception e) { out.append("application_info=unavailable\n"); }
            PackageInfo pi = getPackageInfo(context, pkg);
            if (pi != null) {
                out.append("version_name=").append(pi.versionName == null ? "" : pi.versionName).append('\n');
                out.append("version_code=").append(Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode).append('\n');
                out.append("first_install=").append(new Date(pi.firstInstallTime)).append('\n');
                out.append("last_update=").append(new Date(pi.lastUpdateTime)).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static PackageInfo getPackageInfo(Context context, String pkg) {
        try { return context.getPackageManager().getPackageInfo(pkg, 0); }
        catch (Exception ignored) { return null; }
    }

    private static String joinInts(Set<Integer> values) {
        List<String> out = new ArrayList<>();
        for (Integer value : values) out.add(String.valueOf(value));
        return String.join(",", out);
    }

    private static String modeName(int mode) {
        if (mode == 1) return "multi";
        if (mode == 2) return "global";
        return "single";
    }

    private static void addText(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        byte[] data = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        zip.write(data);
        zip.closeEntry();
    }

    private static void addFile(ZipOutputStream zip, String name, File file) throws Exception {
        ZipEntry entry = new ZipEntry(name); entry.setTime(file.lastModified()); zip.putNextEntry(entry);
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[COPY_BUFFER]; int count;
            while ((count = in.read(buffer)) > 0) zip.write(buffer, 0, count);
        }
        zip.closeEntry();
    }

    private static String sanitizeName(String name) {
        return name == null || name.isEmpty() ? "log.log" : name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void notify(ProgressCallback callback, String message) { if (callback != null) callback.onProgress(message); }
    private static String safeMessage(Throwable e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    public static final class Result {
        public final boolean success; public final File file; public final String error;
        private Result(boolean success, File file, String error) { this.success=success; this.file=file; this.error=error; }
        static Result success(File file) { return new Result(true,file,""); }
        static Result error(String error) { return new Result(false,null,error); }
    }
}
