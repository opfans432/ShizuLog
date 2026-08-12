package dev.shizulog.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class CaptureSessionManager {

    private static final String PREFS =
            "shizulog_sessions";

    private static final String KEY_ACTIVE_ID =
            "active_session_id";

    private static final String DIR =
            "capture_sessions";

    private CaptureSessionManager() {}

    public static synchronized Session begin(
            Context context,
            int mode,
            String[] packages,
            String[] labels,
            int[] uids
    ) {
        if (context == null) {
            return null;
        }

        finishActive(
                context,
                "被新的记录会话接替"
        );

        long now =
                System.currentTimeMillis();

        String id =
                new SimpleDateFormat(
                        "yyyyMMdd_HHmmss_SSS",
                        Locale.US
                ).format(new Date(now))
                        + "_"
                        + UUID.randomUUID()
                        .toString()
                        .substring(0, 8);

        String[] safePackages =
                packages == null
                        ? new String[0]
                        : packages.clone();

        String[] safeLabels =
                labels == null
                        ? new String[0]
                        : labels.clone();

        int[] safeUids =
                uids == null
                        ? new int[0]
                        : uids.clone();

        Session session =
                new Session(
                        id,
                        defaultName(
                                mode,
                                safeLabels,
                                now
                        ),
                        mode,
                        safePackages,
                        safeLabels,
                        safeUids,
                        now,
                        0L,
                        true,
                        "",
                        "正在启动记录",
                        0L
                );

        writeSession(
                context,
                session
        );

        prefs(context)
                .edit()
                .putString(
                        KEY_ACTIVE_ID,
                        id
                )
                .apply();

        return session;
    }

    public static synchronized void updateActive(
            Context context,
            String status,
            String logPath
    ) {
        Session current =
                getActive(context);

        if (current == null) {
            return;
        }

        String nextPath =
                logPath == null
                        || logPath.trim()
                        .isEmpty()
                        ? current.logPath
                        : logPath.trim();

        String nextStatus =
                status == null
                        || status.trim()
                        .isEmpty()
                        ? current.lastStatus
                        : status.trim();

        long bytes =
                fileSize(
                        nextPath
                );

        writeSession(
                context,
                current.copy(
                        current.name,
                        current.endedAt,
                        current.active,
                        nextPath,
                        nextStatus,
                        bytes
                )
        );
    }

    public static synchronized void finishActive(
            Context context,
            String status
    ) {
        Session current =
                getActive(context);

        if (current == null) {
            return;
        }

        long now =
                System.currentTimeMillis();

        String finalStatus =
                status == null
                        || status.trim()
                        .isEmpty()
                        ? "记录已结束"
                        : status.trim();

        writeSession(
                context,
                current.copy(
                        current.name,
                        now,
                        false,
                        current.logPath,
                        finalStatus,
                        fileSize(
                                current.logPath
                        )
                )
        );

        prefs(context)
                .edit()
                .remove(
                        KEY_ACTIVE_ID
                )
                .apply();
    }

    public static synchronized void recoverStaleActive(
            Context context,
            boolean recordingFlag
    ) {
        if (recordingFlag) {
            return;
        }

        Session current =
                getActive(context);

        if (current == null) {
            return;
        }

        finishActive(
                context,
                "上次记录已结束"
        );
    }

    public static synchronized Session getActive(
            Context context
    ) {
        if (context == null) {
            return null;
        }

        String id =
                prefs(context)
                        .getString(
                                KEY_ACTIVE_ID,
                                ""
                        );

        if (id == null
                || id.isEmpty()) {
            return null;
        }

        return get(
                context,
                id
        );
    }

    public static synchronized Session get(
            Context context,
            String id
    ) {
        if (context == null
                || id == null
                || id.isEmpty()) {
            return null;
        }

        File file =
                sessionFile(
                        context,
                        id
                );

        if (!file.isFile()) {
            return null;
        }

        try {
            String raw =
                    readAll(file);

            return fromJson(
                    new JSONObject(raw)
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    public static synchronized List<Session> list(
            Context context
    ) {
        List<Session> out =
                new ArrayList<>();

        File dir =
                directory(context);

        File[] files =
                dir.listFiles(
                        (parent, name) ->
                                name.endsWith(
                                        ".json"
                                )
                );

        if (files == null) {
            return out;
        }

        for (File file : files) {
            try {
                Session session =
                        fromJson(
                                new JSONObject(
                                        readAll(file)
                                )
                        );

                if (session != null) {
                    long bytes =
                            fileSize(
                                    session.logPath
                            );

                    if (bytes
                            != session.logBytes) {
                        session =
                                session.copy(
                                        session.name,
                                        session.endedAt,
                                        session.active,
                                        session.logPath,
                                        session.lastStatus,
                                        bytes
                                );
                    }

                    out.add(session);
                }
            } catch (Exception ignored) {}
        }

        out.sort(
                Comparator.comparingLong(
                        (Session item) ->
                                item.startedAt
                ).reversed()
        );

        return out;
    }

    public static synchronized Session latestWithLog(
            Context context
    ) {
        for (Session item :
                list(context)) {

            if (item.hasLog()) {
                return item;
            }
        }

        return null;
    }

    public static synchronized boolean rename(
            Context context,
            String id,
            String name
    ) {
        Session session =
                get(
                        context,
                        id
                );

        if (session == null) {
            return false;
        }

        String clean =
                name == null
                        ? ""
                        : name.trim();

        if (clean.isEmpty()) {
            return false;
        }

        writeSession(
                context,
                session.copy(
                        clean,
                        session.endedAt,
                        session.active,
                        session.logPath,
                        session.lastStatus,
                        session.logBytes
                )
        );

        return true;
    }

    public static synchronized boolean deleteMetadata(
            Context context,
            String id
    ) {
        Session session =
                get(
                        context,
                        id
                );

        if (session == null
                || session.active) {
            return false;
        }

        File file =
                sessionFile(
                        context,
                        id
                );

        return !file.exists()
                || file.delete();
    }

    private static SharedPreferences prefs(
            Context context
    ) {
        return context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
    }

    private static File directory(
            Context context
    ) {
        File dir =
                new File(
                        context.getFilesDir(),
                        DIR
                );

        if (!dir.exists()) {
            dir.mkdirs();
        }

        return dir;
    }

    private static File sessionFile(
            Context context,
            String id
    ) {
        return new File(
                directory(context),
                id + ".json"
        );
    }

    private static void writeSession(
            Context context,
            Session session
    ) {
        if (context == null
                || session == null) {
            return;
        }

        try {
            byte[] data =
                    toJson(session)
                            .toString(2)
                            .getBytes(
                                    StandardCharsets
                                            .UTF_8
                            );

            File target =
                    sessionFile(
                            context,
                            session.id
                    );

            File tmp =
                    new File(
                            target.getParentFile(),
                            target.getName()
                                    + ".tmp"
                    );

            try (FileOutputStream out =
                         new FileOutputStream(
                                 tmp
                         )) {
                out.write(data);
                out.flush();
            }

            if (target.exists()
                    && !target.delete()) {
                tmp.delete();
                return;
            }

            if (!tmp.renameTo(target)) {
                try (FileOutputStream out =
                             new FileOutputStream(
                                     target
                             )) {
                    out.write(data);
                    out.flush();
                }

                tmp.delete();
            }
        } catch (Exception ignored) {}
    }

    private static JSONObject toJson(
            Session session
    ) throws Exception {
        JSONObject object =
                new JSONObject();

        object.put(
                "id",
                session.id
        );

        object.put(
                "name",
                session.name
        );

        object.put(
                "mode",
                session.mode
        );

        object.put(
                "packages",
                new JSONArray(
                        Arrays.asList(
                                session.packages
                        )
                )
        );

        object.put(
                "labels",
                new JSONArray(
                        Arrays.asList(
                                session.labels
                        )
                )
        );

        JSONArray uidArray =
                new JSONArray();

        for (int uid :
                session.uids) {
            uidArray.put(uid);
        }

        object.put(
                "uids",
                uidArray
        );

        object.put(
                "startedAt",
                session.startedAt
        );

        object.put(
                "endedAt",
                session.endedAt
        );

        object.put(
                "active",
                session.active
        );

        object.put(
                "logPath",
                session.logPath
        );

        object.put(
                "lastStatus",
                session.lastStatus
        );

        object.put(
                "logBytes",
                session.logBytes
        );

        return object;
    }

    private static Session fromJson(
            JSONObject object
    ) {
        if (object == null) {
            return null;
        }

        JSONArray packageArray =
                object.optJSONArray(
                        "packages"
                );

        JSONArray labelArray =
                object.optJSONArray(
                        "labels"
                );

        JSONArray uidArray =
                object.optJSONArray(
                        "uids"
                );

        String[] packages =
                toStrings(
                        packageArray
                );

        String[] labels =
                toStrings(
                        labelArray
                );

        int[] uids =
                toInts(
                        uidArray
                );

        return new Session(
                object.optString(
                        "id",
                        ""
                ),
                object.optString(
                        "name",
                        "未命名会话"
                ),
                object.optInt(
                        "mode",
                        LogCaptureService
                                .MODE_SINGLE
                ),
                packages,
                labels,
                uids,
                object.optLong(
                        "startedAt",
                        0L
                ),
                object.optLong(
                        "endedAt",
                        0L
                ),
                object.optBoolean(
                        "active",
                        false
                ),
                object.optString(
                        "logPath",
                        ""
                ),
                object.optString(
                        "lastStatus",
                        ""
                ),
                object.optLong(
                        "logBytes",
                        0L
                )
        );
    }

    private static String[] toStrings(
            JSONArray array
    ) {
        if (array == null) {
            return new String[0];
        }

        String[] out =
                new String[
                        array.length()
                ];

        for (int i = 0;
             i < out.length;
             i++) {
            out[i] =
                    array.optString(
                            i,
                            ""
                    );
        }

        return out;
    }

    private static int[] toInts(
            JSONArray array
    ) {
        if (array == null) {
            return new int[0];
        }

        int[] out =
                new int[
                        array.length()
                ];

        for (int i = 0;
             i < out.length;
             i++) {
            out[i] =
                    array.optInt(
                            i,
                            0
                    );
        }

        return out;
    }

    private static String readAll(
            File file
    ) throws Exception {
        try (FileInputStream in =
                     new FileInputStream(
                             file
                     )) {

            byte[] data =
                    new byte[
                            (int) Math.min(
                                    Integer.MAX_VALUE,
                                    file.length()
                            )
                    ];

            int total = 0;
            int count;

            while (total < data.length
                    && (count = in.read(
                            data,
                            total,
                            data.length - total
                    )) > 0) {
                total += count;
            }

            return new String(
                    data,
                    0,
                    total,
                    StandardCharsets.UTF_8
            );
        }
    }

    private static long fileSize(
            String path
    ) {
        if (path == null
                || path.isEmpty()) {
            return 0L;
        }

        File file =
                new File(path);

        return file.isFile()
                ? file.length()
                : 0L;
    }

    private static String defaultName(
            int mode,
            String[] labels,
            long now
    ) {
        String prefix;

        if (mode
                == LogCaptureService
                .MODE_GLOBAL) {
            prefix =
                    "全局 Logcat";
        } else if (mode
                == LogCaptureService
                .MODE_MULTI) {
            prefix =
                    labels.length > 0
                            ? "多应用 · "
                            + labels[0]
                            + (labels.length > 1
                            ? " 等 "
                            + labels.length
                            + " 个"
                            : "")
                            : "多应用";
        } else {
            prefix =
                    labels.length > 0
                            && !labels[0]
                            .isEmpty()
                            ? labels[0]
                            : "单应用";
        }

        return prefix
                + " · "
                + new SimpleDateFormat(
                        "MM-dd HH:mm",
                        Locale.getDefault()
                ).format(
                        new Date(now)
                );
    }

    public static String modeName(
            int mode
    ) {
        if (mode
                == LogCaptureService
                .MODE_GLOBAL) {
            return "全局";
        }

        if (mode
                == LogCaptureService
                .MODE_MULTI) {
            return "多应用";
        }

        return "单应用";
    }

    public static String humanSize(
            long bytes
    ) {
        if (bytes < 1024L) {
            return bytes + " B";
        }

        double kb =
                bytes / 1024.0;

        if (kb < 1024.0) {
            return String.format(
                    Locale.US,
                    "%.1f KB",
                    kb
            );
        }

        double mb =
                kb / 1024.0;

        if (mb < 1024.0) {
            return String.format(
                    Locale.US,
                    "%.2f MB",
                    mb
            );
        }

        return String.format(
                Locale.US,
                "%.2f GB",
                mb / 1024.0
        );
    }

    public static final class Session {
        public final String id;
        public final String name;
        public final int mode;
        public final String[] packages;
        public final String[] labels;
        public final int[] uids;
        public final long startedAt;
        public final long endedAt;
        public final boolean active;
        public final String logPath;
        public final String lastStatus;
        public final long logBytes;

        Session(
                String id,
                String name,
                int mode,
                String[] packages,
                String[] labels,
                int[] uids,
                long startedAt,
                long endedAt,
                boolean active,
                String logPath,
                String lastStatus,
                long logBytes
        ) {
            this.id =
                    id == null
                            ? ""
                            : id;

            this.name =
                    name == null
                            ? "未命名会话"
                            : name;

            this.mode =
                    mode;

            this.packages =
                    packages == null
                            ? new String[0]
                            : packages.clone();

            this.labels =
                    labels == null
                            ? new String[0]
                            : labels.clone();

            this.uids =
                    uids == null
                            ? new int[0]
                            : uids.clone();

            this.startedAt =
                    startedAt;

            this.endedAt =
                    endedAt;

            this.active =
                    active;

            this.logPath =
                    logPath == null
                            ? ""
                            : logPath;

            this.lastStatus =
                    lastStatus == null
                            ? ""
                            : lastStatus;

            this.logBytes =
                    logBytes;
        }

        Session copy(
                String nextName,
                long nextEndedAt,
                boolean nextActive,
                String nextLogPath,
                String nextStatus,
                long nextBytes
        ) {
            return new Session(
                    id,
                    nextName,
                    mode,
                    packages,
                    labels,
                    uids,
                    startedAt,
                    nextEndedAt,
                    nextActive,
                    nextLogPath,
                    nextStatus,
                    nextBytes
            );
        }

        public boolean hasLog() {
            return logPath != null
                    && !logPath.isEmpty()
                    && new File(
                            logPath
                    ).isFile();
        }

        public long durationMs() {
            long end =
                    active
                            ? System.currentTimeMillis()
                            : endedAt;

            if (startedAt <= 0L
                    || end <= startedAt) {
                return 0L;
            }

            return end - startedAt;
        }
    }
}
