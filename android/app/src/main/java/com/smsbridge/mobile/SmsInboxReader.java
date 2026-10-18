package com.smsbridge.mobile;

import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 短信收件箱兜底读取。
 *
 * 部分国产 ROM 不把 SMS_RECEIVED 广播发给非默认短信应用，或者应用被后台限制时
 * 收不到广播。只要用户授予 READ_SMS，就可以直接查询 content://sms/inbox 兜底。
 */
public final class SmsInboxReader {
    /** 最新一条短信的诊断结果，包含失败原因和 Cursor 元数据。 */
    public static final class Probe {
        public final boolean permissionGranted;
        public final int cursorCount;
        public final List<String> columns;
        public final String error;
        public final Prefs.Msg latest;
        public final long elapsedMs;
        public final long rowId;
        public final int type;

        Probe(boolean permissionGranted, int cursorCount, List<String> columns,
              String error, Prefs.Msg latest, long elapsedMs, long rowId, int type) {
            this.permissionGranted = permissionGranted;
            this.cursorCount = cursorCount;
            this.columns = columns;
            this.error = error;
            this.latest = latest;
            this.elapsedMs = elapsedMs;
            this.rowId = rowId;
            this.type = type;
        }

        public boolean success() {
            return permissionGranted && error == null && latest != null;
        }

        public String summary() {
            if (!permissionGranted) {
                return "没有读取短信权限";
            }
            if (error != null) {
                return "查询失败：" + error;
            }
            if (latest == null) {
                return "收件箱为空（或没有 SMS）";
            }
            return "读取成功：" + latest.from + " · " + latest.body.length() + " 字";
        }
    }

    private SmsInboxReader() {
    }

    public static List<Prefs.Msg> readSince(Context context, long since) {
        List<Prefs.Msg> result = new ArrayList<>();
        if (context.checkSelfPermission(android.Manifest.permission.READ_SMS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return result;
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{"_id", "address", "body", "date"},
                    "date > ?",
                    new String[]{Long.toString(Math.max(0L, since))},
                    "date ASC LIMIT 30");
            if (cursor == null) {
                return result;
            }
            int addressIndex = cursor.getColumnIndex("address");
            int bodyIndex = cursor.getColumnIndex("body");
            int dateIndex = cursor.getColumnIndex("date");
            while (cursor.moveToNext()) {
                String from = addressIndex >= 0 ? cursor.getString(addressIndex) : "";
                String body = bodyIndex >= 0 ? cursor.getString(bodyIndex) : "";
                long date = dateIndex >= 0 ? cursor.getLong(dateIndex) : 0L;
                if (body == null || body.isEmpty() || date <= 0) {
                    continue;
                }
                if (from == null || from.isEmpty()) {
                    from = "未知号码";
                }
                String id = Long.toHexString(date) + "-"
                        + Integer.toHexString((from + "|" + body).hashCode());
                result.add(new Prefs.Msg(id, from, body, SmsCode.extract(body), date, false));
            }
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
        return result;
    }

    /**
     * 直接读取收件箱最新一条，不限制时间，用来判断到底能不能读 SMS Provider。
     * 失败信息会原样返回，方便区分“权限被拒 / ROM 拦截 / Cursor 为空 / 不在收件箱”。
     */
    public static Probe probeLatest(Context context) {
        long started = System.currentTimeMillis();
        boolean granted = context.checkSelfPermission(android.Manifest.permission.READ_SMS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            return new Probe(false, -1, new ArrayList<>(), null, null,
                    System.currentTimeMillis() - started, -1, -1);
        }
        Cursor cursor = null;
        int count = -1;
        List<String> columns = new ArrayList<>();
        String error = null;
        Prefs.Msg latest = null;
        long rowId = -1;
        int type = -1;
        try {
            // 查整个 content://sms，而不是只查 inbox：
            // 有些 ROM 会把短信放在非 type=1 的记录里。
            cursor = context.getContentResolver().query(
                    Telephony.Sms.CONTENT_URI,
                    new String[]{"_id", "address", "body", "date", "type"},
                    null,
                    null,
                    "_id DESC");
            if (cursor == null) {
                error = "query() 返回 null Cursor";
            } else {
                count = cursor.getCount();
                String[] names = cursor.getColumnNames();
                if (names != null) {
                    columns = Arrays.asList(names);
                }
                if (cursor.moveToFirst()) {
                    int addressIndex = cursor.getColumnIndex("address");
                    int bodyIndex = cursor.getColumnIndex("body");
                    int dateIndex = cursor.getColumnIndex("date");
                    int idIndex = cursor.getColumnIndex("_id");
                    int typeIndex = cursor.getColumnIndex("type");
                    String from = addressIndex >= 0 ? cursor.getString(addressIndex) : "";
                    String body = bodyIndex >= 0 ? cursor.getString(bodyIndex) : "";
                    long date = dateIndex >= 0 ? cursor.getLong(dateIndex) : 0L;
                    rowId = idIndex >= 0 ? cursor.getLong(idIndex) : -1;
                    type = typeIndex >= 0 ? cursor.getInt(typeIndex) : -1;
                    if (body == null) {
                        body = "";
                    }
                    if (from == null || from.isEmpty()) {
                        from = "未知号码";
                    }
                    String id = Long.toHexString(date) + "-"
                            + Integer.toHexString((from + "|" + body).hashCode());
                    latest = new Prefs.Msg(id, from, body, SmsCode.extract(body), date, false);
                }
            }
        } catch (Exception e) {
            error = e.getClass().getName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
        return new Probe(granted, count, columns, error, latest,
                System.currentTimeMillis() - started, rowId, type);
    }
}
