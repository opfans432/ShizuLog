package dev.shizulog.app;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class LogSearchEngine {

    public static final int MAX_RESULTS = 5000;

    private static final Pattern ERROR_PATTERN =
            Pattern.compile(
                    "(?i)"
                            + "(?:\\sE\\s+[^:]+:)"
                            + "|(?:FATAL EXCEPTION)"
                            + "|(?:ANR in\\s)"
                            + "|(?:Fatal signal)"
                            + "|(?:SIGSEGV)"
                            + "|(?:SIGABRT)"
                            + "|(?:signal 11)"
                            + "|(?:signal 6)"
                            + "|(?:native crash)"
            );

    private LogSearchEngine() {}

    public static SearchResult search(
            File file,
            String query,
            boolean regex
    ) throws Exception {
        if (file == null || !file.isFile()) {
            return SearchResult.error(
                    "日志文件不存在"
            );
        }

        if (query == null
                || query.trim().isEmpty()) {
            return SearchResult.error(
                    "请输入搜索内容"
            );
        }

        final Pattern regexPattern;

        if (regex) {
            try {
                regexPattern =
                        Pattern.compile(
                                query,
                                Pattern.CASE_INSENSITIVE
                                        | Pattern.UNICODE_CASE
                        );
            } catch (PatternSyntaxException e) {
                return SearchResult.error(
                        "正则表达式错误："
                                + e.getDescription()
                );
            }
        } else {
            regexPattern = null;
        }

        String plainNeedle =
                regex
                        ? ""
                        : query.toLowerCase(
                                Locale.ROOT
                        );

        List<Match> matches =
                new ArrayList<>();

        boolean truncated = false;

        try (RandomAccessFile raf =
                     new RandomAccessFile(
                             file,
                             "r"
                     )) {

            long lineNumber = 0L;

            while (true) {
                long offset =
                        raf.getFilePointer();

                String encodedLine =
                        raf.readLine();

                if (encodedLine == null) {
                    break;
                }

                lineNumber++;

                String line =
                        decodeReadLine(
                                encodedLine
                        );

                boolean hit;

                if (regex) {
                    hit =
                            regexPattern
                                    .matcher(line)
                                    .find();
                } else {
                    hit =
                            line.toLowerCase(
                                    Locale.ROOT
                            ).contains(
                                    plainNeedle
                            );
                }

                if (!hit) {
                    continue;
                }

                matches.add(
                        new Match(
                                offset,
                                lineNumber,
                                line
                        )
                );

                if (matches.size()
                        >= MAX_RESULTS) {
                    truncated = true;
                    break;
                }
            }
        }

        return SearchResult.success(
                matches,
                truncated
        );
    }

    public static SearchResult findErrors(
            File file
    ) throws Exception {
        if (file == null || !file.isFile()) {
            return SearchResult.error(
                    "日志文件不存在"
            );
        }

        List<Match> matches =
                new ArrayList<>();

        boolean truncated = false;

        try (RandomAccessFile raf =
                     new RandomAccessFile(
                             file,
                             "r"
                     )) {

            long lineNumber = 0L;

            while (true) {
                long offset =
                        raf.getFilePointer();

                String encodedLine =
                        raf.readLine();

                if (encodedLine == null) {
                    break;
                }

                lineNumber++;

                String line =
                        decodeReadLine(
                                encodedLine
                        );

                if (!ERROR_PATTERN
                        .matcher(line)
                        .find()) {
                    continue;
                }

                matches.add(
                        new Match(
                                offset,
                                lineNumber,
                                line
                        )
                );

                if (matches.size()
                        >= MAX_RESULTS) {
                    truncated = true;
                    break;
                }
            }
        }

        return SearchResult.success(
                matches,
                truncated
        );
    }

    public static String readContext(
            File file,
            long offset,
            int beforeLines,
            int afterLines
    ) throws Exception {
        if (file == null || !file.isFile()) {
            return "";
        }

        long safeOffset =
                Math.max(
                        0L,
                        Math.min(
                                offset,
                                file.length()
                        )
                );

        long lookBack =
                Math.max(
                        64L * 1024L,
                        beforeLines
                                * 4096L
                );

        long start =
                Math.max(
                        0L,
                        safeOffset - lookBack
                );

        Deque<ContextLine> previous =
                new ArrayDeque<>();

        List<ContextLine> following =
                new ArrayList<>();

        try (RandomAccessFile raf =
                     new RandomAccessFile(
                             file,
                             "r"
                     )) {

            raf.seek(start);

            if (start > 0) {
                raf.readLine();
            }

            long lineNumber =
                    countLinesBefore(
                            file,
                            raf.getFilePointer()
                    );

            while (true) {
                long lineOffset =
                        raf.getFilePointer();

                String encodedLine =
                        raf.readLine();

                if (encodedLine == null) {
                    break;
                }

                lineNumber++;

                String line =
                        decodeReadLine(
                                encodedLine
                        );

                if (lineOffset < safeOffset) {
                    previous.addLast(
                            new ContextLine(
                                    lineNumber,
                                    line
                            )
                    );

                    while (previous.size()
                            > beforeLines) {
                        previous.removeFirst();
                    }

                    continue;
                }

                following.add(
                        new ContextLine(
                                lineNumber,
                                line
                        )
                );

                if (following.size()
                        >= afterLines + 1) {
                    break;
                }
            }
        }

        StringBuilder out =
                new StringBuilder();

        for (ContextLine line :
                previous) {
            appendNumberedLine(
                    out,
                    line
            );
        }

        for (ContextLine line :
                following) {
            appendNumberedLine(
                    out,
                    line
            );
        }

        return out.toString()
                .trim();
    }

    public static long countLinesBefore(
            File file,
            long offset
    ) throws Exception {
        if (file == null
                || !file.isFile()
                || offset <= 0L) {
            return 0L;
        }

        long target =
                Math.min(
                        offset,
                        file.length()
                );

        long count = 0L;

        try (RandomAccessFile raf =
                     new RandomAccessFile(
                             file,
                             "r"
                     )) {

            byte[] buffer =
                    new byte[64 * 1024];

            long remaining =
                    target;

            while (remaining > 0L) {
                int wanted =
                        (int) Math.min(
                                buffer.length,
                                remaining
                        );

                int read =
                        raf.read(
                                buffer,
                                0,
                                wanted
                        );

                if (read <= 0) {
                    break;
                }

                for (int i = 0;
                     i < read;
                     i++) {
                    if (buffer[i] == '\n') {
                        count++;
                    }
                }

                remaining -= read;
            }
        }

        return count;
    }

    private static void appendNumberedLine(
            StringBuilder out,
            ContextLine line
    ) {
        if (out.length() > 0) {
            out.append('\n');
        }

        out.append(
                String.format(
                        Locale.US,
                        "%8d | %s",
                        line.lineNumber,
                        line.text
                )
        );
    }

    private static String decodeReadLine(
            String encodedLine
    ) {
        return new String(
                encodedLine.getBytes(
                        StandardCharsets
                                .ISO_8859_1
                ),
                StandardCharsets.UTF_8
        );
    }

    private static final class ContextLine {
        final long lineNumber;
        final String text;

        ContextLine(
                long lineNumber,
                String text
        ) {
            this.lineNumber = lineNumber;
            this.text = text;
        }
    }

    public static final class Match {
        public final long byteOffset;
        public final long lineNumber;
        public final String line;

        Match(
                long byteOffset,
                long lineNumber,
                String line
        ) {
            this.byteOffset = byteOffset;
            this.lineNumber = lineNumber;
            this.line = line;
        }
    }

    public static final class SearchResult {
        public final boolean success;
        public final String error;
        public final List<Match> matches;
        public final boolean truncated;

        private SearchResult(
                boolean success,
                String error,
                List<Match> matches,
                boolean truncated
        ) {
            this.success = success;
            this.error = error;
            this.matches = matches;
            this.truncated = truncated;
        }

        static SearchResult success(
                List<Match> matches,
                boolean truncated
        ) {
            return new SearchResult(
                    true,
                    "",
                    matches,
                    truncated
            );
        }

        static SearchResult error(
                String error
        ) {
            return new SearchResult(
                    false,
                    error,
                    new ArrayList<>(),
                    false
            );
        }
    }
}
