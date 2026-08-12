package dev.shizulog.app;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CrashAnalyzer {

    private static final int MAX_ANALYSIS_BYTES =
            4 * 1024 * 1024;

    private static final int MAX_STACK_LINES = 140;

    private static final Pattern JAVA_EXCEPTION =
            Pattern.compile(
                    "(?:Caused by:\\s*)?"
                            + "([A-Za-z_$][\\w$]*"
                            + "(?:\\.[\\w$]+)+"
                            + "(?:Exception|Error|Throwable))"
                            + "(?::\\s*(.*))?"
            );

    private static final Pattern STACK_FRAME =
            Pattern.compile(
                    "\\bat\\s+([^\\s(]+\\([^)]*\\))"
            );

    private static final Pattern NATIVE_FRAME =
            Pattern.compile(
                    "#\\d+\\s+pc\\s+"
                            + "[0-9a-fA-F]+\\s+"
                            + ".*"
            );

    private static final Pattern NATIVE_PROCESS =
            Pattern.compile(
                    "pid:\\s*(\\d+)"
                            + ".*?"
                            + "tid:\\s*(\\d+)"
                            + "(?:.*?name:\\s*([^>\\n]+?))?"
                            + "(?:\\s+>>>\\s*([^<\\n]+)\\s*<<<)?",
                    Pattern.CASE_INSENSITIVE
            );

    private CrashAnalyzer() {}

    public static Result analyze(File file) {
        if (file == null || !file.isFile()) {
            return Result.empty(
                    "日志文件不存在"
            );
        }

        String text;

        try {
            text = readTail(
                    file,
                    MAX_ANALYSIS_BYTES
            );
        } catch (Exception e) {
            return Result.empty(
                    "读取失败："
                            + safeMessage(e)
            );
        }

        if (text.isEmpty()) {
            return Result.empty(
                    "日志文件为空"
            );
        }

        int javaIndex =
                lastIndexOfIgnoreCase(
                        text,
                        "FATAL EXCEPTION"
                );

        int anrIndex =
                lastIndexOfIgnoreCase(
                        text,
                        "ANR in "
                );

        int nativeIndex =
                lastNativeCrashIndex(text);

        int latest =
                Math.max(
                        javaIndex,
                        Math.max(
                                anrIndex,
                                nativeIndex
                        )
                );

        if (latest < 0) {
            return Result.empty(
                    "未检测到明显崩溃标记"
            );
        }

        if (latest == javaIndex) {
            return analyzeJavaCrash(
                    text,
                    javaIndex
            );
        }

        if (latest == anrIndex) {
            return analyzeAnr(
                    text,
                    anrIndex
            );
        }

        return analyzeNativeCrash(
                text,
                nativeIndex
        );
    }

    private static Result analyzeJavaCrash(
            String text,
            int index
    ) {
        String block =
                sliceCrashBlock(
                        text,
                        index,
                        260
                );

        String thread =
                extractAfterToken(
                        firstLineContaining(
                                block,
                                "FATAL EXCEPTION"
                        ),
                        "FATAL EXCEPTION:"
                );

        String process = "";
        String pid = "";

        for (String line :
                block.split("\\n")) {

            int processIndex =
                    line.indexOf("Process:");

            if (processIndex >= 0) {
                String tail =
                        line.substring(
                                processIndex
                        );

                String parsedProcess =
                        extractBetween(
                                tail,
                                "Process:",
                                ","
                        );

                if (!parsedProcess.isEmpty()) {
                    process = parsedProcess;
                }

                String parsedPid =
                        extractAfterToken(
                                tail,
                                "PID:"
                        );

                if (!parsedPid.isEmpty()) {
                    pid = firstToken(
                            parsedPid
                    );
                }

                break;
            }
        }

        String exceptionClass = "";
        String exceptionMessage = "";
        String rootCause = "";
        String rootCauseMessage = "";
        String keyLocation = "";

        Matcher matcher =
                JAVA_EXCEPTION.matcher(block);

        while (matcher.find()) {
            String clazz =
                    safeGroup(
                            matcher,
                            1
                    );

            String message =
                    safeGroup(
                            matcher,
                            2
                    );

            String matchedText =
                    matcher.group();

            boolean causedBy =
                    matchedText != null
                            && matchedText
                                    .trim()
                                    .startsWith(
                                            "Caused by:"
                                    );

            if (exceptionClass.isEmpty()) {
                exceptionClass = clazz;
                exceptionMessage = message;
            }

            if (causedBy) {
                rootCause = clazz;
                rootCauseMessage = message;
            }
        }

        List<String> frames =
                extractJavaFrames(block);

        for (String frame : frames) {
            if (!isFrameworkFrame(frame)) {
                keyLocation = frame;
                break;
            }
        }

        if (keyLocation.isEmpty()
                && !frames.isEmpty()) {
            keyLocation = frames.get(0);
        }

        String causeText =
                buildCauseText(
                        rootCause,
                        rootCauseMessage
                );

        if (causeText.isEmpty()) {
            causeText =
                    buildCauseText(
                            exceptionClass,
                            exceptionMessage
                    );
        }

        return new Result(
                true,
                "Java 崩溃",
                process,
                pid,
                thread,
                exceptionClass,
                causeText,
                keyLocation,
                "FATAL EXCEPTION",
                trimStackExcerpt(block),
                buildSummary(
                        "Java 崩溃",
                        process,
                        pid,
                        thread,
                        exceptionClass,
                        causeText,
                        keyLocation
                )
        );
    }

    private static Result analyzeAnr(
            String text,
            int index
    ) {
        int start =
                Math.max(
                        0,
                        lineStart(
                                text,
                                index
                        )
                );

        String block =
                sliceCrashBlock(
                        text,
                        start,
                        220
                );

        String anrLine =
                firstLineContaining(
                        block,
                        "ANR in "
                );

        String process =
                extractAfterToken(
                        anrLine,
                        "ANR in "
                );

        if (process.contains(" ")) {
            process =
                    firstToken(process);
        }

        String reason = "";

        for (String line :
                block.split("\\n")) {

            int reasonIndex =
                    line.indexOf("Reason:");

            if (reasonIndex >= 0) {
                reason =
                        line.substring(
                                reasonIndex
                                        + "Reason:".length()
                        ).trim();

                break;
            }
        }

        String keyLocation = "";

        List<String> frames =
                extractJavaFrames(block);

        for (String frame : frames) {
            if (!isFrameworkFrame(frame)) {
                keyLocation = frame;
                break;
            }
        }

        return new Result(
                true,
                "ANR / 无响应",
                process,
                "",
                "main（可能）",
                "Application Not Responding",
                reason,
                keyLocation,
                "ANR",
                trimStackExcerpt(block),
                buildSummary(
                        "ANR / 无响应",
                        process,
                        "",
                        "main（可能）",
                        "Application Not Responding",
                        reason,
                        keyLocation
                )
        );
    }

    private static Result analyzeNativeCrash(
            String text,
            int index
    ) {
        int start =
                Math.max(
                        0,
                        lineStart(
                                text,
                                index
                        )
                );

        String block =
                sliceCrashBlock(
                        text,
                        start,
                        260
                );

        String triggerLine =
                findNativeTriggerLine(block);

        String process = "";
        String pid = "";
        String thread = "";

        Matcher processMatcher =
                NATIVE_PROCESS.matcher(block);

        if (processMatcher.find()) {
            pid = safeGroup(
                    processMatcher,
                    1
            );

            String tid =
                    safeGroup(
                            processMatcher,
                            2
                    );

            String threadName =
                    safeGroup(
                            processMatcher,
                            3
                    ).trim();

            String packageName =
                    safeGroup(
                            processMatcher,
                            4
                    ).trim();

            if (!packageName.isEmpty()) {
                process = packageName;
            }

            if (!threadName.isEmpty()) {
                thread = threadName;
            } else if (!tid.isEmpty()) {
                thread = "tid " + tid;
            }
        }

        String exceptionClass =
                nativeSignalType(
                        triggerLine
                );

        String keyLocation = "";

        for (String line :
                block.split("\\n")) {

            Matcher frame =
                    NATIVE_FRAME.matcher(line);

            if (frame.find()) {
                keyLocation =
                        frame.group().trim();
                break;
            }
        }

        return new Result(
                true,
                "Native 崩溃",
                process,
                pid,
                thread,
                exceptionClass,
                cleanLogPrefix(
                        triggerLine
                ),
                keyLocation,
                "Native signal",
                trimStackExcerpt(block),
                buildSummary(
                        "Native 崩溃",
                        process,
                        pid,
                        thread,
                        exceptionClass,
                        cleanLogPrefix(
                                triggerLine
                        ),
                        keyLocation
                )
        );
    }

    private static int lastNativeCrashIndex(
            String text
    ) {
        String[] markers = {
                "Fatal signal",
                "SIGSEGV",
                "SIGABRT",
                "signal 11",
                "signal 6",
                "native crash"
        };

        int result = -1;

        for (String marker : markers) {
            result =
                    Math.max(
                            result,
                            lastIndexOfIgnoreCase(
                                    text,
                                    marker
                            )
                    );
        }

        return result;
    }

    private static String nativeSignalType(
            String line
    ) {
        String lower =
                line == null
                        ? ""
                        : line.toLowerCase(
                                Locale.ROOT
                        );

        if (lower.contains("sigsegv")
                || lower.contains(
                "signal 11")) {
            return "SIGSEGV (signal 11)";
        }

        if (lower.contains("sigabrt")
                || lower.contains(
                "signal 6")) {
            return "SIGABRT (signal 6)";
        }

        if (lower.contains(
                "fatal signal")) {
            return "Fatal signal";
        }

        return "Native crash";
    }

    private static String findNativeTriggerLine(
            String block
    ) {
        String[] markers = {
                "Fatal signal",
                "SIGSEGV",
                "SIGABRT",
                "signal 11",
                "signal 6",
                "native crash"
        };

        for (String line :
                block.split("\\n")) {

            for (String marker : markers) {
                if (containsIgnoreCase(
                        line,
                        marker
                )) {
                    return line.trim();
                }
            }
        }

        return "";
    }

    private static String sliceCrashBlock(
            String text,
            int startIndex,
            int maxLines
    ) {
        if (text == null
                || text.isEmpty()) {
            return "";
        }

        int start =
                Math.max(
                        0,
                        lineStart(
                                text,
                                startIndex
                        )
                );

        String tail =
                text.substring(start);

        String[] lines =
                tail.split(
                        "\\n",
                        -1
                );

        StringBuilder out =
                new StringBuilder();

        int limit =
                Math.min(
                        lines.length,
                        maxLines
                );

        for (int i = 0;
             i < limit;
             i++) {

            String line = lines[i];

            if (i > 10
                    && isNewCrashBoundary(line)) {
                break;
            }

            out.append(line)
                    .append('\n');
        }

        return out.toString();
    }

    private static boolean isNewCrashBoundary(
            String line
    ) {
        if (line == null) {
            return false;
        }

        return line.contains(
                "# ===== AUTO CRASH SNAPSHOT ====="
        )
                || line.contains(
                "# ===== END CRASH SNAPSHOT ====="
        );
    }

    private static List<String> extractJavaFrames(
            String block
    ) {
        List<String> frames =
                new ArrayList<>();

        Matcher matcher =
                STACK_FRAME.matcher(block);

        while (matcher.find()) {
            String frame =
                    safeGroup(
                            matcher,
                            1
                    );

            if (!frame.isEmpty()) {
                frames.add(frame);
            }
        }

        return frames;
    }

    private static boolean isFrameworkFrame(
            String frame
    ) {
        if (frame == null) {
            return true;
        }

        String lower =
                frame.toLowerCase(
                        Locale.ROOT
                );

        return lower.startsWith("java.")
                || lower.startsWith("javax.")
                || lower.startsWith("android.")
                || lower.startsWith("androidx.")
                || lower.startsWith("kotlin.")
                || lower.startsWith("kotlinx.")
                || lower.startsWith("dalvik.")
                || lower.startsWith("sun.")
                || lower.startsWith("com.android.")
                || lower.startsWith("libcore.");
    }

    private static String trimStackExcerpt(
            String block
    ) {
        if (block == null
                || block.isEmpty()) {
            return "";
        }

        String[] lines =
                block.split(
                        "\\n",
                        -1
                );

        StringBuilder out =
                new StringBuilder();

        int count = 0;

        for (String line : lines) {
            if (count >= MAX_STACK_LINES) {
                out.append(
                        "…（堆栈过长，已截断；完整内容请打开原始日志）"
                );
                break;
            }

            out.append(
                    cleanLogPrefix(line)
            ).append('\n');

            count++;
        }

        return out.toString().trim();
    }

    private static String cleanLogPrefix(
            String line
    ) {
        if (line == null) {
            return "";
        }

        // Strip the common threadtime / threadtime,uid prefix when present.
        return line.replaceFirst(
                "^\\s*\\d{2}-\\d{2}\\s+"
                        + "\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+"
                        + "(?:\\d+\\s+)?"
                        + "\\d+\\s+\\d+\\s+"
                        + "[VDIWEF]\\s+"
                        + "[^:]+:\\s?",
                ""
        );
    }

    private static String buildCauseText(
            String clazz,
            String message
    ) {
        if (clazz == null
                || clazz.isEmpty()) {
            return "";
        }

        if (message == null
                || message.trim().isEmpty()) {
            return clazz;
        }

        return clazz
                + ": "
                + message.trim();
    }

    private static String buildSummary(
            String type,
            String process,
            String pid,
            String thread,
            String exception,
            String cause,
            String keyLocation
    ) {
        StringBuilder out =
                new StringBuilder();

        appendSummaryLine(
                out,
                "类型",
                type
        );

        appendSummaryLine(
                out,
                "进程",
                process
        );

        appendSummaryLine(
                out,
                "PID",
                pid
        );

        appendSummaryLine(
                out,
                "线程",
                thread
        );

        appendSummaryLine(
                out,
                "异常",
                exception
        );

        appendSummaryLine(
                out,
                "原因",
                cause
        );

        appendSummaryLine(
                out,
                "关键位置",
                keyLocation
        );

        return out.toString().trim();
    }

    private static void appendSummaryLine(
            StringBuilder out,
            String label,
            String value
    ) {
        if (value == null
                || value.trim().isEmpty()) {
            return;
        }

        if (out.length() > 0) {
            out.append('\n');
        }

        out.append(label)
                .append("：")
                .append(value.trim());
    }

    private static int lastIndexOfIgnoreCase(
            String text,
            String needle
    ) {
        if (text == null
                || needle == null) {
            return -1;
        }

        return text.toLowerCase(
                Locale.ROOT
        ).lastIndexOf(
                needle.toLowerCase(
                        Locale.ROOT
                )
        );
    }

    private static boolean containsIgnoreCase(
            String text,
            String needle
    ) {
        return lastIndexOfIgnoreCase(
                text,
                needle
        ) >= 0;
    }

    private static int lineStart(
            String text,
            int index
    ) {
        if (text == null
                || text.isEmpty()) {
            return 0;
        }

        int safeIndex =
                Math.max(
                        0,
                        Math.min(
                                index,
                                text.length() - 1
                        )
                );

        int newline =
                text.lastIndexOf(
                        '\n',
                        safeIndex
                );

        return newline < 0
                ? 0
                : newline + 1;
    }

    private static String firstLineContaining(
            String text,
            String needle
    ) {
        if (text == null) {
            return "";
        }

        for (String line :
                text.split("\\n")) {

            if (line.contains(needle)) {
                return line;
            }
        }

        return "";
    }

    private static String extractAfterToken(
            String text,
            String token
    ) {
        if (text == null
                || token == null) {
            return "";
        }

        int index =
                text.indexOf(token);

        if (index < 0) {
            return "";
        }

        return text.substring(
                index + token.length()
        ).trim();
    }

    private static String extractBetween(
            String text,
            String startToken,
            String endToken
    ) {
        if (text == null) {
            return "";
        }

        int start =
                text.indexOf(startToken);

        if (start < 0) {
            return "";
        }

        start += startToken.length();

        int end =
                text.indexOf(
                        endToken,
                        start
                );

        if (end < 0) {
            end = text.length();
        }

        return text.substring(
                start,
                end
        ).trim();
    }

    private static String firstToken(
            String text
    ) {
        if (text == null) {
            return "";
        }

        String trimmed =
                text.trim();

        if (trimmed.isEmpty()) {
            return "";
        }

        int space =
                trimmed.indexOf(' ');

        int comma =
                trimmed.indexOf(',');

        int end =
                trimmed.length();

        if (space >= 0) {
            end = Math.min(
                    end,
                    space
            );
        }

        if (comma >= 0) {
            end = Math.min(
                    end,
                    comma
            );
        }

        return trimmed.substring(
                0,
                end
        ).trim();
    }

    private static String safeGroup(
            Matcher matcher,
            int group
    ) {
        try {
            String value =
                    matcher.group(group);

            return value == null
                    ? ""
                    : value.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readTail(
            File file,
            int maxBytes
    ) throws Exception {
        try (RandomAccessFile raf =
                     new RandomAccessFile(
                             file,
                             "r"
                     )) {

            long start =
                    Math.max(
                            0L,
                            file.length()
                                    - maxBytes
                    );

            raf.seek(start);

            int length =
                    (int) (
                            file.length()
                                    - start
                    );

            byte[] data =
                    new byte[length];

            raf.readFully(data);

            return new String(
                    data,
                    StandardCharsets.UTF_8
            );
        }
    }

    private static String safeMessage(
            Throwable error
    ) {
        String message =
                error.getMessage();

        return message == null
                ? error.getClass()
                        .getSimpleName()
                : message;
    }

    public static final class Result {
        public final boolean detected;
        public final String type;
        public final String process;
        public final String pid;
        public final String thread;
        public final String exception;
        public final String cause;
        public final String keyLocation;
        public final String trigger;
        public final String stackExcerpt;
        public final String summary;

        Result(
                boolean detected,
                String type,
                String process,
                String pid,
                String thread,
                String exception,
                String cause,
                String keyLocation,
                String trigger,
                String stackExcerpt,
                String summary
        ) {
            this.detected = detected;
            this.type = type;
            this.process = process;
            this.pid = pid;
            this.thread = thread;
            this.exception = exception;
            this.cause = cause;
            this.keyLocation = keyLocation;
            this.trigger = trigger;
            this.stackExcerpt = stackExcerpt;
            this.summary = summary;
        }

        static Result empty(
                String reason
        ) {
            return new Result(
                    false,
                    "未检测到",
                    "",
                    "",
                    "",
                    "",
                    reason,
                    "",
                    "",
                    "",
                    "未检测到明显崩溃\n"
                            + reason
            );
        }
    }
}
