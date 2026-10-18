package com.smsbridge.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 手机端持久化：配对信息、待发队列、最近同步记录、开关。 */
public final class Prefs {
    public static final class Msg {
        public final String id;
        public final String from;
        public final String body;
        public final String code;
        public final long ts;
        public final boolean copied;

        public Msg(String id, String from, String body, String code, long ts, boolean copied) {
            this.id = id;
            this.from = from;
            this.body = body;
            this.code = code;
            this.ts = ts;
            this.copied = copied;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("from", from);
                o.put("body", body);
                o.put("code", code == null ? "" : code);
                o.put("ts", ts);
                o.put("copied", copied);
            } catch (JSONException ignored) {
                // 忽略
            }
            return o;
        }

        static Msg fromJson(JSONObject o) {
            return new Msg(
                    o.optString("id", ""),
                    o.optString("from", ""),
                    o.optString("body", ""),
                    o.optString("code", ""),
                    o.optLong("ts", 0L),
                    o.optBoolean("copied", false));
        }
    }

    private static final String NAME = "smsbridge";
    private static final int MAX_QUEUE = 50;
    private static final int MAX_RECENT = 30;
    private static final int MAX_HANDLED_NOTIFS = 200;

    private final SharedPreferences sp;

    private Prefs(SharedPreferences sp) {
        this.sp = sp;
    }

    public static Prefs get(Context context) {
        return new Prefs(context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE));
    }

    public String deviceId() {
        String id = sp.getString("deviceId", null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            sp.edit().putString("deviceId", id).apply();
        }
        return id;
    }

    public String deviceName() {
        String name = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    public String host() {
        return sp.getString("host", "");
    }

    public int port() {
        return sp.getInt("port", 47823);
    }

    public void setServer(String host, int port) {
        sp.edit().putString("host", host).putInt("port", port).apply();
    }

    public String pin() {
        return sp.getString("pin", "");
    }

    public void setPin(String pin) {
        sp.edit().putString("pin", pin).apply();
    }

    public String token() {
        return sp.getString("token", "");
    }

    public void setToken(String token) {
        sp.edit().putString("token", token).apply();
    }

    public String pcName() {
        return sp.getString("pcName", "电脑");
    }

    public void setPcName(String name) {
        sp.edit().putString("pcName", name).apply();
    }

    public boolean enabled() {
        return sp.getBoolean("enabled", false);
    }

    public void setEnabled(boolean enabled) {
        sp.edit().putBoolean("enabled", enabled).apply();
    }

    public boolean autoStart() {
        return sp.getBoolean("autoStart", true);
    }

    public void setAutoStart(boolean value) {
        sp.edit().putBoolean("autoStart", value).apply();
    }

    public boolean batteryAsked() {
        return sp.getBoolean("batteryAsked", false);
    }

    public void setBatteryAsked(boolean value) {
        sp.edit().putBoolean("batteryAsked", value).apply();
    }

    public boolean hideBackground() {
        return sp.getBoolean("hideBackground", false);
    }

    public void setHideBackground(boolean value) {
        sp.edit().putBoolean("hideBackground", value).apply();
    }

    public boolean notificationAccessAsked() {
        return sp.getBoolean("notificationAccessAsked", false);
    }

    public void setNotificationAccessAsked(boolean value) {
        sp.edit().putBoolean("notificationAccessAsked", value).apply();
    }

    /** 首次打开必须确认的免责声明。 */
    public boolean disclaimerAccepted() {
        return sp.getBoolean("disclaimerAccepted", false);
    }

    public void setDisclaimerAccepted(boolean value) {
        sp.edit().putBoolean("disclaimerAccepted", value).apply();
    }

    public boolean hasPairing() {
        return !token().isEmpty() || !pin().isEmpty();
    }

    public void clearPairing() {
        sp.edit().remove("token").remove("pin").remove("pcName").apply();
    }

    public synchronized List<Msg> queue() {
        return readList("queue", MAX_QUEUE);
    }

    public synchronized void enqueue(Msg msg) {
        List<Msg> list = readList("queue", MAX_QUEUE);
        for (Msg m : list) {
            if (m.id.equals(msg.id)) {
                return;
            }
        }
        list.add(msg);
        while (list.size() > MAX_QUEUE) {
            list.remove(0);
        }
        writeList("queue", list);
    }

    public synchronized void removeQueued(String id) {
        List<Msg> list = readList("queue", MAX_QUEUE);
        if (list.removeIf(m -> m.id.equals(id))) {
            writeList("queue", list);
        }
    }

    public synchronized boolean hasQueued(String id) {
        for (Msg msg : readList("queue", MAX_QUEUE)) {
            if (msg.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    public synchronized List<Msg> recent() {
        return readList("recent", MAX_RECENT);
    }

    public synchronized boolean hasRecent(String id) {
        for (Msg msg : readList("recent", MAX_RECENT)) {
            if (msg.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 记住已经上报过的通知（通知 key + 正文指纹）。
     *
     * 通知读取服务每次重连都会把通知栏里还挂着的通知再扫一遍，短信 App 也会反复重发同一条通知，
     * 没有这条记录同一封短信就会被重复上报。返回 true 表示这条通知还没处理过。
     */
    public synchronized boolean markNotifHandled(String key, String fingerprint, long windowMs) {
        long now = System.currentTimeMillis();
        List<String> kept = new ArrayList<>();
        boolean duplicate = false;
        String raw = sp.getString("handledNotifs", "");
        if (!raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                String[] parts = line.split("\u0001");
                if (parts.length != 3) {
                    continue;
                }
                long ts;
                try {
                    ts = Long.parseLong(parts[0]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (now - ts > windowMs) {
                    continue;
                }
                kept.add(line);
                if (parts[1].equals(key) && parts[2].equals(fingerprint)) {
                    duplicate = true;
                }
            }
        }
        if (duplicate) {
            return false;
        }
        kept.add(now + "\u0001" + key + "\u0001" + fingerprint);
        while (kept.size() > MAX_HANDLED_NOTIFS) {
            kept.remove(0);
        }
        StringBuilder sb = new StringBuilder();
        for (String line : kept) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        sp.edit().putString("handledNotifs", sb.toString()).apply();
        return true;
    }

    /** 防止同一条通知被重复入队：用“发件人+正文”在很短的时间窗内去重。 */
    public synchronized boolean hasRecentContent(String from, String body, long windowMs) {
        long now = System.currentTimeMillis();
        for (Msg msg : readList("recent", MAX_RECENT)) {
            if (Math.abs(now - msg.ts) > windowMs) {
                continue;
            }
            if (same(msg.from, from) && same(msg.body, body)) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean hasQueuedContent(String from, String body, long windowMs) {
        long now = System.currentTimeMillis();
        for (Msg msg : readList("queue", MAX_QUEUE)) {
            if (Math.abs(now - msg.ts) > windowMs) {
                continue;
            }
            if (same(msg.from, from) && same(msg.body, body)) {
                return true;
            }
        }
        return false;
    }

    /** 同一发件人 + 同一个验证码在窗口内已经上报过，就按重复处理。 */
    public synchronized boolean hasRecentCode(String from, String code, long windowMs) {
        if (code == null || code.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (Msg msg : readList("recent", MAX_RECENT)) {
            if (Math.abs(now - msg.ts) > windowMs) {
                continue;
            }
            if (same(msg.from, from) && code.equals(msg.code)) {
                return true;
            }
        }
        for (Msg msg : readList("queue", MAX_QUEUE)) {
            if (Math.abs(now - msg.ts) > windowMs) {
                continue;
            }
            if (same(msg.from, from) && code.equals(msg.code)) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", "").trim();
    }

    public synchronized void addRecent(Msg msg) {
        List<Msg> list = readList("recent", MAX_RECENT);
        for (Msg existing : list) {
            if (existing.id.equals(msg.id) && existing.copied && !msg.copied) {
                // 不要用“等待电脑确认”覆盖已经同步成功的状态
                return;
            }
        }
        list.removeIf(m -> m.id.equals(msg.id));
        list.add(0, msg);
        while (list.size() > MAX_RECENT) {
            list.remove(list.size() - 1);
        }
        writeList("recent", list);
    }

    public synchronized void clearRecent() {
        sp.edit().remove("recent").apply();
    }

    public synchronized void addEvent(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        String trimmed = line.length() > 300 ? line.substring(0, 300) : line;
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
        List<String> events = events();
        events.add(0, time + "  " + trimmed);
        while (events.size() > 40) {
            events.remove(events.size() - 1);
        }
        JSONArray array = new JSONArray();
        for (String event : events) {
            array.put(event);
        }
        sp.edit().putString("events", array.toString()).apply();
    }

    public synchronized List<String> events() {
        List<String> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(sp.getString("events", "[]"));
            for (int i = 0; i < array.length() && result.size() < 40; i++) {
                String line = array.optString(i, "");
                if (!line.isEmpty()) {
                    result.add(line);
                }
            }
        } catch (JSONException ignored) {
            // 忽略
        }
        return result;
    }

    private List<Msg> readList(String key, int limit) {
        List<Msg> result = new ArrayList<>();
        String raw = sp.getString(key, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && result.size() < limit; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o != null) {
                    result.add(Msg.fromJson(o));
                }
            }
        } catch (JSONException ignored) {
            // 损坏则当空列表
        }
        return result;
    }

    private void writeList(String key, List<Msg> list) {
        JSONArray array = new JSONArray();
        for (Msg m : list) {
            array.put(m.toJson());
        }
        sp.edit().putString(key, array.toString()).apply();
    }
}
