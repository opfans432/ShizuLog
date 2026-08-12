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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    private static final int COPY_BUFFER =
            32 * 1024;

    private static final int PID_SCAN_BYTES =
            1024 * 1024;

    private static final int HEADER_SCAN_BYTES =
            64 * 1024;

    private static final Pattern THREADTIME_UID =
            Pattern.compile(
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

    public static final class Options {
        public final boolean redactLog;
        public final boolean includeCrashSummary;
        public final boolean includeDeviceInfo;
        public final boolean includeTargetInfo;

        public Options(
                boolean redactLog,
                boolean includeCrashSummary,
                boolean includeDeviceInfo,
                boolean includeTargetInfo
        ) {
            this.redactLog = redactLog;
            this.includeCrashSummary =
                    includeCrashSummary;
            this.includeDeviceInfo =
                    includeDeviceInfo;
            this.includeTargetInfo =
                    includeTargetInfo;
        }
    }

    public static Result export(
            Context context,
            File logFile,
            Options options,
            ProgressCallback callback
    ) {
        if (context == null) {
            return Result.error(
                    "Context 不可用"
            );
        }

        if (logFile == null
                || !logFile.isFile()) {
            return Result.error(
                    "当前没有可导出的日志文件"
            );
        }

        if (options == null) {
            options =
                    new Options(
                            true,
                            true,
                            true,
                            true
                    );
        }

        try {
            File baseDir =
                    context.getExternalFilesDir(
                            Environment
                                    .DIRECTORY_DOCUMENTS
                    );

            if (baseDir == null) {
                return Result.error(
                        "应用 Documents 目录不可用"
                );
            }

            File exportDir =
                    new File(
                            baseDir,
                            "diagnostic"
                    );

            if (!exportDir.exists()
                    && !exportDir.mkdirs()) {
                return Result.error(
                        "无法创建诊断包目录"
                );
            }

            String stamp =
                    new SimpleDateFormat(
                            "yyyyMMdd_HHmmss",
                            Locale.US
                    ).format(new Date());

            String privacySuffix =
                    options.redactLog
                            ? "-redacted"
                            : "-raw";

            File zipFile =
                    new File(
                            exportDir,
                            "ShizuLog-Diagnostic-"
                                    + stamp
                                    + privacySuffix
                                    + ".zip"
                    );

            notify(
                    callback,
                    "正在读取日志信息…"
            );

            HeaderInfo header =
                    readHeaderInfo(logFile);

            SharedPreferences prefs =
                    context.getSharedPreferences(
                            "shizulog_state",
                            Context.MODE_PRIVATE
                    );

            String prefSingle =
                    prefs.getString(
                            "target_package",
                            ""
                    );

            String prefMulti =
                    prefs.getString(
                            "multi_packages",
                            ""
                    );

            int prefMode =
                    prefs.getInt(
                            "capture_mode",
                            0
                    );

            String mode =
                    !header.mode.isEmpty()
                            ? header.mode
                            : modeName(prefMode);

            List<String> packages =
                    !header.packages.isEmpty()
                            ? splitPackages(
                                    header.packages
                            )
                            : collectPackages(
                                    prefSingle,
                                    prefMulti
                            );

            CrashAnalyzer.Result crash =
                    null;

            if (options.includeCrashSummary) {
                notify(
                        callback,
                        "正在分析崩溃摘要…"
                );

                crash =
                        CrashAnalyzer.analyze(
                                logFile
                        );
            }

            Map<Integer, Set<Integer>> recentPids =
                    new LinkedHashMap<>();

            if (options.includeTargetInfo) {
                notify(
                        callback,
                        "正在整理目标应用信息…"
                );

                recentPids =
                        scanRecentPids(
                                logFile
                        );
            }

            Map<String, String> hashes =
                    new LinkedHashMap<>();

            notify(
                    callback,
                    options.redactLog
                            ? "正在脱敏并打包日志…"
                            : "正在打包原始日志…"
            );

            try (ZipOutputStream zip =
                         new ZipOutputStream(
                                 new BufferedOutputStream(
                                         new FileOutputStream(
                                                 zipFile
                                         )
                                 )
                         )) {

                addTextWithHash(
                        zip,
                        hashes,
                        "README.txt",
                        buildReadme(
                                options
                        )
                );

                addTextWithHash(
                        zip,
                        hashes,
                        "shizulog-info.txt",
                        buildMetadata(
                                context,
                                logFile,
                                mode,
                                packages,
                                options
                        )
                );

                if (options.includeDeviceInfo) {
                    addTextWithHash(
                            zip,
                            hashes,
                            "device-info.txt",
                            buildDeviceInfo()
                    );
                }

                if (options.includeTargetInfo) {
                    addTextWithHash(
                            zip,
                            hashes,
                            "target-apps.txt",
                            buildTargetInfo(
                                    context,
                                    packages,
                                    recentPids,
                                    header.uids
                            )
                    );
                }

                if (options.includeCrashSummary) {
                    String summary =
                            crash == null
                                    || crash.summary == null
                                    || crash.summary
                                            .isEmpty()
                                    ? "未检测到明显崩溃"
                                    : crash.summary;

                    if (options.redactLog) {
                        summary =
                                redactText(
                                        summary
                                );
                    }

                    addTextWithHash(
                            zip,
                            hashes,
                            "crash-summary.txt",
                            summary
                    );
                }

                String logEntry =
                        "log/"
                                + sanitizeName(
                                        logFile
                                                .getName()
                                );

                if (options.redactLog) {
                    addRedactedFileWithHash(
                            zip,
                            hashes,
                            logEntry,
                            logFile
                    );
                } else {
                    addFileWithHash(
                            zip,
                            hashes,
                            logEntry,
                            logFile
                    );
                }

                addTextNoManifestHash(
                        zip,
                        "manifest-sha256.txt",
                        buildHashManifest(
                                hashes
                        )
                );
            }

            notify(
                    callback,
                    "诊断包已生成"
            );

            return Result.success(
                    zipFile,
                    options.redactLog
            );
        } catch (Exception e) {
            return Result.error(
                    e.getClass()
                            .getSimpleName()
                            + ": "
                            + safeMessage(e)
            );
        }
    }

    private static HeaderInfo readHeaderInfo(
            File logFile
    ) {
        HeaderInfo info =
                new HeaderInfo();

        try (FileInputStream in =
                     new FileInputStream(
                             logFile
                     )) {

            int length =
                    (int) Math.min(
                            logFile.length(),
                            HEADER_SCAN_BYTES
                    );

            byte[] data =
                    new byte[length];

            int total = 0;
            int count;

            while (total < length
                    && (count = in.read(
                            data,
                            total,
                            length - total
                    )) > 0) {
                total += count;
            }

            String text =
                    new String(
                            data,
                            0,
                            total,
                            StandardCharsets.UTF_8
                    );

            for (String line :
                    text.split("\\n")) {

                if (!line.startsWith("# ")) {
                    continue;
                }

                int equal =
                        line.indexOf('=');

                if (equal <= 2) {
                    continue;
                }

                String key =
                        line.substring(
                                2,
                                equal
                        ).trim();

                String value =
                        line.substring(
                                equal + 1
                        ).trim();

                if ("mode".equals(key)) {
                    info.mode = value;
                } else if ("packages".equals(key)) {
                    info.packages = value;
                } else if ("uids".equals(key)) {
                    info.uids = value;
                }
            }
        } catch (Exception ignored) {}

        return info;
    }

    private static List<String> collectPackages(
            String singlePackage,
            String multiRaw
    ) {
        LinkedHashSet<String> set =
                new LinkedHashSet<>();

        if (singlePackage != null
                && !singlePackage
                        .trim()
                        .isEmpty()) {
            set.add(
                    singlePackage.trim()
            );
        }

        if (multiRaw != null
                && !multiRaw
                        .trim()
                        .isEmpty()) {

            for (String item :
                    multiRaw.split(",")) {

                String pkg =
                        item.trim();

                if (!pkg.isEmpty()) {
                    set.add(pkg);
                }
            }
        }

        return new ArrayList<>(set);
    }

    private static List<String> splitPackages(
            String raw
    ) {
        return collectPackages(
                "",
                raw
        );
    }

    private static Map<Integer, Set<Integer>>
            scanRecentPids(
                    File logFile
            ) {

        Map<Integer, Set<Integer>> result =
                new LinkedHashMap<>();

        try (RandomAccessFile raf =
                     new RandomAccessFile(
                             logFile,
                             "r"
                     )) {

            long start =
                    Math.max(
                            0L,
                            logFile.length()
                                    - PID_SCAN_BYTES
                    );

            raf.seek(start);

            if (start > 0) {
                raf.readLine();
            }

            String line;

            while ((line =
                            raf.readLine())
                            != null) {

                String decoded =
                        new String(
                                line.getBytes(
                                        StandardCharsets
                                                .ISO_8859_1
                                ),
                                StandardCharsets
                                        .UTF_8
                        );

                Matcher matcher =
                        THREADTIME_UID.matcher(
                                decoded
                        );

                if (!matcher.find()) {
                    continue;
                }

                int uid =
                        Integer.parseInt(
                                matcher.group(1)
                        );

                int pid =
                        Integer.parseInt(
                                matcher.group(2)
                        );

                result.computeIfAbsent(
                        uid,
                        ignored ->
                                new LinkedHashSet<>()
                ).add(pid);
            }
        } catch (Exception ignored) {}

        return result;
    }

    private static String buildReadme(
            Options options
    ) {
        return ""
                + "ShizuLog Diagnostic Pack\n"
                + "========================\n\n"
                + "privacy_mode="
                + (options.redactLog
                        ? "redacted"
                        : "raw")
                + "\n\n"
                + "该 ZIP 由 ShizuLog 在设备本地生成，用于 Android 应用排错。\n\n"
                + "日志模式："
                + (options.redactLog
                        ? "脱敏版。常见 Token、Authorization、Cookie、密码字段会尝试替换为 <REDACTED>。"
                        : "原始版。日志内容不会被 ShizuLog 主动修改。")
                + "\n\n"
                + "不会主动包含 ShizuLog 发布签名私钥、GitHub Actions Secrets、"
                + "SIGNING-RECOVERY.env 或其他应用私有文件。\n\n"
                + "注意：自动脱敏只能覆盖常见格式，不能保证识别所有敏感信息；分享前仍建议确认接收方可信。\n";
    }

    private static String buildMetadata(
            Context context,
            File logFile,
            String mode,
            List<String> packages,
            Options options
    ) {
        StringBuilder out =
                new StringBuilder();

        PackageInfo self =
                getPackageInfo(
                        context,
                        context.getPackageName()
                );

        out.append(
                "generated="
        ).append(
                new Date()
        ).append('\n');

        out.append(
                "privacy_mode="
        ).append(
                options.redactLog
                        ? "redacted"
                        : "raw"
        ).append('\n');

        out.append(
                "shizulog_package="
        ).append(
                context.getPackageName()
        ).append('\n');

        if (self != null) {
            out.append(
                    "shizulog_version="
            ).append(
                    self.versionName
            ).append('\n');

            out.append(
                    "shizulog_version_code="
            ).append(
                    Build.VERSION.SDK_INT >= 28
                            ? self.getLongVersionCode()
                            : self.versionCode
            ).append('\n');
        }

        out.append(
                "capture_mode="
        ).append(
                mode
        ).append('\n');

        out.append(
                "targets="
        ).append(
                String.join(
                        ",",
                        packages
                )
        ).append('\n');

        out.append(
                "log_file="
        ).append(
                logFile.getName()
        ).append('\n');

        out.append(
                "log_size_bytes="
        ).append(
                logFile.length()
        ).append('\n');

        out.append(
                "log_last_modified="
        ).append(
                new Date(
                        logFile
                                .lastModified()
                )
        ).append('\n');

        try {
            int uid =
                    Shizuku.getUid();

            out.append(
                    "shizuku_uid="
            ).append(uid)
                    .append('\n');

            out.append(
                    "shizuku_backend="
            ).append(
                    uid == 0
                            ? "root"
                            : uid == 2000
                            ? "adb_shell"
                            : "uid_" + uid
            ).append('\n');
        } catch (Throwable ignored) {
            out.append(
                    "shizuku_uid=unavailable\n"
            );
        }

        try {
            out.append(
                    "shizuku_binder_alive="
            ).append(
                    Shizuku.pingBinder()
            ).append('\n');
        } catch (Throwable ignored) {
            out.append(
                    "shizuku_binder_alive=false\n"
            );
        }

        try {
            out.append(
                    "shizuku_permission="
            ).append(
                    Shizuku.checkSelfPermission()
            ).append('\n');
        } catch (Throwable ignored) {
            out.append(
                    "shizuku_permission=unavailable\n"
            );
        }

        return out.toString();
    }

    private static String buildDeviceInfo() {
        StringBuilder out =
                new StringBuilder();

        out.append(
                "android_version="
        ).append(
                Build.VERSION.RELEASE
        ).append('\n');

        out.append(
                "sdk="
        ).append(
                Build.VERSION.SDK_INT
        ).append('\n');

        out.append(
                "manufacturer="
        ).append(
                Build.MANUFACTURER
        ).append('\n');

        out.append(
                "brand="
        ).append(
                Build.BRAND
        ).append('\n');

        out.append(
                "model="
        ).append(
                Build.MODEL
        ).append('\n');

        out.append(
                "device="
        ).append(
                Build.DEVICE
        ).append('\n');

        out.append(
                "product="
        ).append(
                Build.PRODUCT
        ).append('\n');

        out.append(
                "hardware="
        ).append(
                Build.HARDWARE
        ).append('\n');

        out.append(
                "supported_abis="
        ).append(
                String.join(
                        ",",
                        Build.SUPPORTED_ABIS
                )
        ).append('\n');

        out.append(
                "fingerprint="
        ).append(
                Build.FINGERPRINT
        ).append('\n');

        out.append(
                "security_patch="
        ).append(
                Build.VERSION.SECURITY_PATCH
        ).append('\n');

        try {
            StructUtsname uname =
                    Os.uname();

            out.append(
                    "kernel="
            ).append(
                    uname.release
            ).append('\n');
        } catch (Throwable ignored) {}

        out.append(
                "locale="
        ).append(
                Locale.getDefault()
        ).append('\n');

        return out.toString();
    }

    private static String buildTargetInfo(
            Context context,
            List<String> packages,
            Map<Integer, Set<Integer>> recentPids,
            String headerUids
    ) {
        if (packages.isEmpty()) {
            return "未选择目标应用\n"
                    + "header_uids="
                    + headerUids
                    + "\n";
        }

        StringBuilder out =
                new StringBuilder();

        if (headerUids != null
                && !headerUids.isEmpty()) {
            out.append(
                    "header_uids="
            ).append(
                    headerUids
            ).append(
                    "\n\n"
            );
        }

        for (String pkg : packages) {
            PackageManager pm =
                    context.getPackageManager();

            out.append(
                    "package="
            ).append(pkg)
                    .append('\n');

            int uid = -1;

            try {
                ApplicationInfo info =
                        pm.getApplicationInfo(
                                pkg,
                                0
                        );

                uid = info.uid;

                out.append(
                        "label="
                ).append(
                        pm.getApplicationLabel(
                                info
                        )
                ).append('\n');

                out.append(
                        "uid="
                ).append(uid)
                        .append('\n');

                Set<Integer> pids =
                        recentPids.get(uid);

                out.append(
                        "recent_pids="
                ).append(
                        pids == null
                                || pids.isEmpty()
                                ? ""
                                : joinInts(pids)
                ).append('\n');
            } catch (Exception e) {
                out.append(
                        "application_info=unavailable\n"
                );
            }

            PackageInfo pi =
                    getPackageInfo(
                            context,
                            pkg
                    );

            if (pi != null) {
                out.append(
                        "version_name="
                ).append(
                        pi.versionName == null
                                ? ""
                                : pi.versionName
                ).append('\n');

                out.append(
                        "version_code="
                ).append(
                        Build.VERSION.SDK_INT >= 28
                                ? pi.getLongVersionCode()
                                : pi.versionCode
                ).append('\n');

                out.append(
                        "first_install="
                ).append(
                        new Date(
                                pi.firstInstallTime
                        )
                ).append('\n');

                out.append(
                        "last_update="
                ).append(
                        new Date(
                                pi.lastUpdateTime
                        )
                ).append('\n');
            }

            out.append('\n');
        }

        return out.toString();
    }

    private static PackageInfo getPackageInfo(
            Context context,
            String pkg
    ) {
        try {
            return context
                    .getPackageManager()
                    .getPackageInfo(
                            pkg,
                            0
                    );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String joinInts(
            Set<Integer> values
    ) {
        List<String> out =
                new ArrayList<>();

        for (Integer value : values) {
            out.add(
                    String.valueOf(value)
            );
        }

        return String.join(
                ",",
                out
        );
    }

    private static String modeName(
            int mode
    ) {
        if (mode == 1) {
            return "multi";
        }

        if (mode == 2) {
            return "global";
        }

        return "single";
    }

    private static String redactText(
            String text
    ) {
        if (text == null
                || text.isEmpty()) {
            return "";
        }

        StringBuilder out =
                new StringBuilder();

        String[] lines =
                text.split(
                        "\\n",
                        -1
                );

        for (int i = 0;
             i < lines.length;
             i++) {

            if (i > 0) {
                out.append('\n');
            }

            out.append(
                    LogRedactor.redactLine(
                            lines[i]
                    )
            );
        }

        return out.toString();
    }

    private static void addTextWithHash(
            ZipOutputStream zip,
            Map<String, String> hashes,
            String name,
            String text
    ) throws Exception {
        byte[] data =
                (text == null
                        ? ""
                        : text)
                        .getBytes(
                                StandardCharsets.UTF_8
                        );

        zip.putNextEntry(
                new ZipEntry(name)
        );

        zip.write(data);
        zip.closeEntry();

        hashes.put(
                name,
                sha256(data)
        );
    }

    private static void addTextNoManifestHash(
            ZipOutputStream zip,
            String name,
            String text
    ) throws Exception {
        zip.putNextEntry(
                new ZipEntry(name)
        );

        zip.write(
                (text == null
                        ? ""
                        : text)
                        .getBytes(
                                StandardCharsets.UTF_8
                        )
        );

        zip.closeEntry();
    }

    private static void addFileWithHash(
            ZipOutputStream zip,
            Map<String, String> hashes,
            String name,
            File file
    ) throws Exception {
        MessageDigest digest =
                MessageDigest.getInstance(
                        "SHA-256"
                );

        ZipEntry entry =
                new ZipEntry(name);

        entry.setTime(
                file.lastModified()
        );

        zip.putNextEntry(entry);

        try (BufferedInputStream in =
                     new BufferedInputStream(
                             new FileInputStream(
                                     file
                             )
                     )) {

            byte[] buffer =
                    new byte[COPY_BUFFER];

            int count;

            while ((count =
                            in.read(buffer))
                            > 0) {

                zip.write(
                        buffer,
                        0,
                        count
                );

                digest.update(
                        buffer,
                        0,
                        count
                );
            }
        }

        zip.closeEntry();

        hashes.put(
                name,
                hex(
                        digest.digest()
                )
        );
    }

    private static void addRedactedFileWithHash(
            ZipOutputStream zip,
            Map<String, String> hashes,
            String name,
            File file
    ) throws Exception {
        MessageDigest digest =
                MessageDigest.getInstance(
                        "SHA-256"
                );

        ZipEntry entry =
                new ZipEntry(name);

        entry.setTime(
                file.lastModified()
        );

        zip.putNextEntry(entry);

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     new FileInputStream(
                                             file
                                     ),
                                     StandardCharsets
                                             .UTF_8
                             ),
                             32 * 1024
                     )) {

            String line;

            while ((line =
                            reader.readLine())
                            != null) {

                byte[] data =
                        (LogRedactor
                                .redactLine(line)
                                + "\n")
                                .getBytes(
                                        StandardCharsets
                                                .UTF_8
                                );

                zip.write(data);
                digest.update(data);
            }
        }

        zip.closeEntry();

        hashes.put(
                name,
                hex(
                        digest.digest()
                )
        );
    }

    private static String buildHashManifest(
            Map<String, String> hashes
    ) {
        StringBuilder out =
                new StringBuilder();

        out.append(
                "# SHA-256 of files inside this diagnostic ZIP\n"
        );

        for (Map.Entry<String, String> entry :
                hashes.entrySet()) {

            out.append(
                    entry.getValue()
            ).append(
                    "  "
            ).append(
                    entry.getKey()
            ).append('\n');
        }

        return out.toString();
    }

    private static String sha256(
            byte[] data
    ) throws Exception {
        MessageDigest digest =
                MessageDigest.getInstance(
                        "SHA-256"
                );

        return hex(
                digest.digest(data)
        );
    }

    private static String hex(
            byte[] data
    ) {
        StringBuilder out =
                new StringBuilder();

        for (byte value : data) {
            out.append(
                    String.format(
                            Locale.US,
                            "%02x",
                            value & 0xff
                    )
            );
        }

        return out.toString();
    }

    private static String sanitizeName(
            String name
    ) {
        return name == null
                || name.isEmpty()
                ? "log.log"
                : name.replaceAll(
                        "[^A-Za-z0-9._-]",
                        "_"
                );
    }

    private static void notify(
            ProgressCallback callback,
            String message
    ) {
        if (callback != null) {
            callback.onProgress(
                    message
            );
        }
    }

    private static String safeMessage(
            Throwable e
    ) {
        return e.getMessage() == null
                ? e.getClass()
                        .getSimpleName()
                : e.getMessage();
    }

    private static final class HeaderInfo {
        String mode = "";
        String packages = "";
        String uids = "";
    }

    public static final class Result {
        public final boolean success;
        public final File file;
        public final String error;
        public final boolean redacted;

        private Result(
                boolean success,
                File file,
                String error,
                boolean redacted
        ) {
            this.success = success;
            this.file = file;
            this.error = error;
            this.redacted = redacted;
        }

        static Result success(
                File file,
                boolean redacted
        ) {
            return new Result(
                    true,
                    file,
                    "",
                    redacted
            );
        }

        static Result error(
                String error
        ) {
            return new Result(
                    false,
                    null,
                    error,
                    false
            );
        }
    }
}
