package dev.shizulog.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RealTimeLogAnalyzer {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_EVENTS = 8000;

    private static final Pattern THREADTIME_UID =
            Pattern.compile(
                    "^\\s*\\d{2}-\\d{2}\\s+"
                            + "\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+"
                            + "(\\S+)\\s+"
                            + "(\\d+)\\s+"
                            + "(\\d+)\\s+"
                            + "([VDIWEF])\\s+"
                            + "([^:]+):\\s?(.*)$"
            );

    private static final Pattern START_PROC =
            Pattern.compile(
                    "(?i)\\bStart proc\\s+(\\d+):([^/\\s]+)"
            );

    private static final Pattern DIED_PROC =
            Pattern.compile(
                    "(?i)\\bProcess\\s+([^\\s]+)\\s+\\(pid\\s+(\\d+)\\)\\s+has died"
            );

    private static final Pattern KILL_PROC =
            Pattern.compile(
                    "(?i)\\bKilling\\s+(\\d+):([^/\\s]+)"
            );

    private final Deque<Event> events = new ArrayDeque<>();
    private final Map<Integer, String> pidNames = new LinkedHashMap<>();
    private final Set<Integer> allSeenPids = new HashSet<>();

    private long totalLines;
    private long totalPidChanges;
    private String latestProcessChange = "—";

    public synchronized void reset() {
        events.clear();
        pidNames.clear();
        allSeenPids.clear();
        totalLines = 0L;
        totalPidChanges = 0L;
        latestProcessChange = "—";
    }

    public synchronized void onLine(String line) {
        onLine(line, System.currentTimeMillis());
    }

    synchronized void onLine(String line, long now) {
        if (line == null || line.isEmpty()) {
            return;
        }

        totalLines++;

        Event event = parseEvent(line, now);
        events.addLast(event);

        if (event.pid > 0 && allSeenPids.add(event.pid)) {
            totalPidChanges++;
            latestProcessChange = "新增 PID " + event.pid;
        }

        parseProcessLifecycle(line);
        prune(now);
    }

    public synchronized Snapshot snapshot() {
        return snapshot(System.currentTimeMillis());
    }

    synchronized Snapshot snapshot(long now) {
        prune(now);

        long verbose = 0L;
        long debug = 0L;
        long info = 0L;
        long warn = 0L;
        long error = 0L;
        long fatal = 0L;
        Set<Integer> pids = new HashSet<>();
        Map<String, Integer> tags = new HashMap<>();

        long oldest = now;

        for (Event event : events) {
            oldest = Math.min(oldest, event.timeMs);

            if (event.pid > 0) {
                pids.add(event.pid);
            }

            if (!event.tag.isEmpty()) {
                tags.put(
                        event.tag,
                        tags.getOrDefault(event.tag, 0) + 1
                );
            }

            switch (event.priority) {
                case "V": verbose++; break;
                case "D": debug++; break;
                case "I": info++; break;
                case "W": warn++; break;
                case "E": error++; break;
                case "F": fatal++; break;
                default: break;
            }
        }

        long windowLines = events.size();
        long errorLines = error + fatal;
        double ageSeconds = Math.max(
                1.0,
                Math.min(
                        WINDOW_MS,
                        now - oldest
                ) / 1000.0
        );

        double linesPerSecond = windowLines / ageSeconds;
        double errorsPerMinute = errorLines * 60.0 / ageSeconds;

        List<Map.Entry<String, Integer>> sortedTags =
                new ArrayList<>(tags.entrySet());

        sortedTags.sort(
                Comparator.<Map.Entry<String, Integer>>comparingInt(
                        Map.Entry::getValue
                ).reversed()
                        .thenComparing(Map.Entry::getKey)
        );

        List<TagCount> topTags = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sortedTags.size()); i++) {
            Map.Entry<String, Integer> item = sortedTags.get(i);
            topTags.add(new TagCount(item.getKey(), item.getValue()));
        }

        return new Snapshot(
                totalLines,
                windowLines,
                verbose,
                debug,
                info,
                warn,
                error,
                fatal,
                linesPerSecond,
                errorsPerMinute,
                pids.size(),
                totalPidChanges,
                latestProcessChange,
                topTags,
                new LinkedHashMap<>(pidNames)
        );
    }

    private Event parseEvent(String line, long now) {
        Matcher matcher = THREADTIME_UID.matcher(line);
        if (!matcher.find()) {
            return new Event(now, -1, "", "");
        }

        int pid = parseInt(matcher.group(2));
        String priority = matcher.group(4);
        String tag = matcher.group(5).trim();

        return new Event(now, pid, priority, tag);
    }

    private void parseProcessLifecycle(String line) {
        Matcher start = START_PROC.matcher(line);
        if (start.find()) {
            int pid = parseInt(start.group(1));
            String process = start.group(2);
            if (pid > 0) {
                pidNames.put(pid, process);
                totalPidChanges++;
                latestProcessChange = "启动 " + pid + ":" + process;
            }
            return;
        }

        Matcher died = DIED_PROC.matcher(line);
        if (died.find()) {
            String process = died.group(1);
            int pid = parseInt(died.group(2));
            if (pid > 0) {
                pidNames.remove(pid);
                totalPidChanges++;
                latestProcessChange = "退出 " + pid + ":" + process;
            }
            return;
        }

        Matcher kill = KILL_PROC.matcher(line);
        if (kill.find()) {
            int pid = parseInt(kill.group(1));
            String process = kill.group(2);
            if (pid > 0) {
                pidNames.remove(pid);
                totalPidChanges++;
                latestProcessChange = "结束 " + pid + ":" + process;
            }
        }
    }

    private void prune(long now) {
        long minTime = now - WINDOW_MS;
        while (!events.isEmpty()) {
            Event first = events.peekFirst();
            if (first.timeMs >= minTime && events.size() <= MAX_EVENTS) {
                break;
            }
            events.removeFirst();
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static final class Event {
        final long timeMs;
        final int pid;
        final String priority;
        final String tag;

        Event(long timeMs, int pid, String priority, String tag) {
            this.timeMs = timeMs;
            this.pid = pid;
            this.priority = priority == null ? "" : priority;
            this.tag = tag == null ? "" : tag;
        }
    }

    public static final class TagCount {
        public final String tag;
        public final int count;

        TagCount(String tag, int count) {
            this.tag = tag;
            this.count = count;
        }
    }

    public static final class Snapshot {
        public final long totalLines;
        public final long windowLines;
        public final long verbose;
        public final long debug;
        public final long info;
        public final long warn;
        public final long error;
        public final long fatal;
        public final double linesPerSecond;
        public final double errorsPerMinute;
        public final int activePidCount;
        public final long pidChanges;
        public final String latestProcessChange;
        public final List<TagCount> topTags;
        public final Map<Integer, String> pidNames;

        Snapshot(
                long totalLines,
                long windowLines,
                long verbose,
                long debug,
                long info,
                long warn,
                long error,
                long fatal,
                double linesPerSecond,
                double errorsPerMinute,
                int activePidCount,
                long pidChanges,
                String latestProcessChange,
                List<TagCount> topTags,
                Map<Integer, String> pidNames
        ) {
            this.totalLines = totalLines;
            this.windowLines = windowLines;
            this.verbose = verbose;
            this.debug = debug;
            this.info = info;
            this.warn = warn;
            this.error = error;
            this.fatal = fatal;
            this.linesPerSecond = linesPerSecond;
            this.errorsPerMinute = errorsPerMinute;
            this.activePidCount = activePidCount;
            this.pidChanges = pidChanges;
            this.latestProcessChange = latestProcessChange;
            this.topTags = topTags;
            this.pidNames = pidNames;
        }

        public String topTagsText() {
            if (topTags == null || topTags.isEmpty()) {
                return "—";
            }

            StringBuilder out = new StringBuilder();
            for (int i = 0; i < topTags.size(); i++) {
                if (i > 0) out.append(" · ");
                TagCount item = topTags.get(i);
                out.append(item.tag)
                        .append(' ')
                        .append(item.count);
            }
            return out.toString();
        }
    }
}
