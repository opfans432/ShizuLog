package dev.shizulog.app;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LogFilterEngine {

    public static final int MAX_RESULTS = 5000;

    private static final Pattern THREADTIME = Pattern.compile(
            "^\\s*\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+" +
                    "(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+([^:]+):\\s?(.*)$"
    );

    private static final Pattern CRASH_MARKER = Pattern.compile(
            "(?i)FATAL EXCEPTION|ANR in\\s|Fatal signal|SIGSEGV|SIGABRT|signal 11|signal 6|native crash|AndroidRuntime"
    );

    private LogFilterEngine() {}

    public static Result filter(File file, Spec spec) throws Exception {
        if (file == null || !file.isFile()) return Result.error("日志文件不存在");
        if (spec == null) spec = Spec.all();

        List<Match> matches = new ArrayList<>();
        boolean truncated = false;

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long lineNumber = 0L;
            while (true) {
                long offset = raf.getFilePointer();
                String encoded = raf.readLine();
                if (encoded == null) break;
                lineNumber++;
                String line = decode(encoded);
                if (!matches(line, spec)) continue;
                matches.add(new Match(offset, lineNumber, line));
                if (matches.size() >= MAX_RESULTS) {
                    truncated = true;
                    break;
                }
            }
        }
        return Result.success(matches, truncated);
    }

    static boolean matches(String line, Spec spec) {
        if (line == null) return false;
        String lower = line.toLowerCase(Locale.ROOT);

        if (spec.crashOnly && !CRASH_MARKER.matcher(line).find()) return false;
        if (!spec.processKeyword.isEmpty() &&
                !lower.contains(spec.processKeyword.toLowerCase(Locale.ROOT))) return false;
        if (!spec.textKeyword.isEmpty() &&
                !lower.contains(spec.textKeyword.toLowerCase(Locale.ROOT))) return false;

        Matcher m = THREADTIME.matcher(line);
        if (!m.find()) {
            // System lines can still be useful for package/process substring filtering.
            return spec.minLevel <= 0 && spec.tag.isEmpty() && spec.pid <= 0;
        }

        String level = m.group(4);
        String tag = m.group(5).trim();
        int pid = parseInt(m.group(2));

        if (spec.minLevel > 0 && levelRank(level) < spec.minLevel) return false;
        if (spec.pid > 0 && pid != spec.pid) return false;
        if (!spec.tag.isEmpty() &&
                !tag.toLowerCase(Locale.ROOT).contains(spec.tag.toLowerCase(Locale.ROOT))) return false;

        return true;
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value); }
        catch (Exception ignored) { return -1; }
    }

    public static int levelRank(String level) {
        if (level == null || level.isEmpty()) return 0;
        switch (level.charAt(0)) {
            case 'V': return 1;
            case 'D': return 2;
            case 'I': return 3;
            case 'W': return 4;
            case 'E': return 5;
            case 'F': return 6;
            default: return 0;
        }
    }

    private static String decode(String encodedLine) {
        return new String(encodedLine.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    public static final class Spec {
        public final int minLevel;
        public final String tag;
        public final int pid;
        public final String processKeyword;
        public final String textKeyword;
        public final boolean crashOnly;

        public Spec(int minLevel, String tag, int pid, String processKeyword,
                    String textKeyword, boolean crashOnly) {
            this.minLevel = Math.max(0, Math.min(6, minLevel));
            this.tag = safe(tag);
            this.pid = Math.max(0, pid);
            this.processKeyword = safe(processKeyword);
            this.textKeyword = safe(textKeyword);
            this.crashOnly = crashOnly;
        }

        public static Spec all() { return new Spec(0, "", 0, "", "", false); }
        private static String safe(String value) { return value == null ? "" : value.trim(); }
    }

    public static final class Match {
        public final long byteOffset;
        public final long lineNumber;
        public final String line;
        Match(long byteOffset, long lineNumber, String line) {
            this.byteOffset = byteOffset;
            this.lineNumber = lineNumber;
            this.line = line;
        }
    }

    public static final class Result {
        public final boolean success;
        public final String error;
        public final List<Match> matches;
        public final boolean truncated;

        private Result(boolean success, String error, List<Match> matches, boolean truncated) {
            this.success = success;
            this.error = error;
            this.matches = matches;
            this.truncated = truncated;
        }

        static Result success(List<Match> matches, boolean truncated) {
            return new Result(true, "", matches, truncated);
        }

        static Result error(String error) {
            return new Result(false, error, new ArrayList<>(), false);
        }
    }
}
