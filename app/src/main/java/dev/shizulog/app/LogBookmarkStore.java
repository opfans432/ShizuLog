package dev.shizulog.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LogBookmarkStore {
    private static final String PREFS = "shizulog_log_bookmarks_v1";
    private LogBookmarkStore() {}

    public static List<Bookmark> load(Context context, File file) {
        List<Bookmark> out = new ArrayList<>();
        if (context == null || file == null) return out;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray array = new JSONArray(prefs.getString(key(file), "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                out.add(new Bookmark(
                        item.optLong("offset", 0L),
                        item.optLong("line", 1L),
                        item.optString("preview", ""),
                        item.optLong("created", 0L)
                ));
            }
        } catch (Exception ignored) {}
        out.sort(Comparator.comparingLong(item -> item.lineNumber));
        return out;
    }

    public static boolean add(Context context, File file, Bookmark bookmark) {
        if (context == null || file == null || bookmark == null) return false;
        List<Bookmark> current = load(context, file);
        for (Bookmark item : current) if (item.lineNumber == bookmark.lineNumber) return false;
        current.add(bookmark);
        current.sort(Comparator.comparingLong(item -> item.lineNumber));
        save(context, file, current);
        return true;
    }

    public static boolean removeLine(Context context, File file, long lineNumber) {
        List<Bookmark> current = load(context, file);
        boolean removed = current.removeIf(item -> item.lineNumber == lineNumber);
        if (removed) save(context, file, current);
        return removed;
    }

    public static void clear(Context context, File file) {
        if (context == null || file == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(key(file)).apply();
    }

    private static void save(Context context, File file, List<Bookmark> bookmarks) {
        JSONArray array = new JSONArray();
        for (Bookmark item : bookmarks) {
            JSONObject object = new JSONObject();
            try {
                object.put("offset", item.byteOffset);
                object.put("line", item.lineNumber);
                object.put("preview", item.preview);
                object.put("created", item.createdAt);
                array.put(object);
            } catch (Exception ignored) {}
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(key(file), array.toString()).apply();
    }

    private static String key(File file) {
        try { return file.getCanonicalPath(); }
        catch (Exception ignored) { return file.getAbsolutePath(); }
    }

    public static final class Bookmark {
        public final long byteOffset;
        public final long lineNumber;
        public final String preview;
        public final long createdAt;

        public Bookmark(long byteOffset, long lineNumber, String preview, long createdAt) {
            this.byteOffset = Math.max(0L, byteOffset);
            this.lineNumber = Math.max(1L, lineNumber);
            this.preview = preview == null ? "" : preview.trim();
            this.createdAt = createdAt;
        }
    }
}
