package dev.shizulog.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class LogFilterPresetStore {
    private static final String PREFS = "shizulog_filter_presets_v1";
    private static final String KEY = "presets";
    private LogFilterPresetStore() {}

    public static List<Preset> load(Context context) {
        List<Preset> out = new ArrayList<>();
        if (context == null) return out;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                out.add(new Preset(
                        object.optString("name", "未命名"),
                        new LogFilterEngine.Spec(
                                object.optInt("minLevel", 0),
                                object.optString("tag", ""),
                                object.optInt("pid", 0),
                                object.optString("process", ""),
                                object.optString("text", ""),
                                object.optBoolean("crashOnly", false)
                        )
                ));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void saveOrReplace(Context context, Preset preset) {
        if (context == null || preset == null || preset.name.trim().isEmpty()) return;
        List<Preset> current = load(context);
        boolean replaced = false;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).name.equalsIgnoreCase(preset.name)) {
                current.set(i, preset);
                replaced = true;
                break;
            }
        }
        if (!replaced) current.add(preset);
        write(context, current);
    }

    public static void delete(Context context, String name) {
        List<Preset> current = load(context);
        current.removeIf(item -> item.name.equalsIgnoreCase(name));
        write(context, current);
    }

    private static void write(Context context, List<Preset> presets) {
        JSONArray array = new JSONArray();
        for (Preset preset : presets) {
            JSONObject object = new JSONObject();
            try {
                object.put("name", preset.name);
                object.put("minLevel", preset.spec.minLevel);
                object.put("tag", preset.spec.tag);
                object.put("pid", preset.spec.pid);
                object.put("process", preset.spec.processKeyword);
                object.put("text", preset.spec.textKeyword);
                object.put("crashOnly", preset.spec.crashOnly);
                array.put(object);
            } catch (Exception ignored) {}
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, array.toString()).apply();
    }

    public static final class Preset {
        public final String name;
        public final LogFilterEngine.Spec spec;
        public Preset(String name, LogFilterEngine.Spec spec) {
            this.name = name == null ? "" : name.trim();
            this.spec = spec == null ? LogFilterEngine.Spec.all() : spec;
        }
    }
}
