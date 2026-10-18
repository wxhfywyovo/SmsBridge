package com.smsbridge.mobile;

import android.app.Notification;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.Set;

/**
 * 通知读取兜底。
 *
 * 有些消息可能只存在于厂商自己的数据库，不会出现在 content://sms/inbox。
 * 只要用户开启「通知使用权」，就能从通知里拿到标题和完整正文，再走同一条同步链路。
 */
public final class SmsNotificationListener extends NotificationListenerService {
    private static final long DEDUP_WINDOW_MS = 10 * 60 * 1000L;
    private static volatile boolean connected;

    public static boolean isConnected() {
        return connected;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        SmsLogger.event(this, "通知读取已连接");
        // 刚开启通知使用权时，把通知栏里已经存在的消息也扫一遍。
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    handleNotification(sbn, "active");
                }
            }
        } catch (Exception e) {
            SmsLogger.event(this, "扫描现有通知失败："
                    + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        connected = false;
        SmsLogger.event(this, "通知读取已断开");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handleNotification(sbn, "posted");
    }

    private void handleNotification(StatusBarNotification sbn, String source) {
        if (sbn == null || sbn.getNotification() == null) {
            return;
        }
        String pkg = sbn.getPackageName();
        if (pkg == null || getPackageName().equals(pkg)) {
            return;
        }
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        if (extras == null) {
            return;
        }
        String title = text(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        if (title.isEmpty()) {
            title = text(extras.getCharSequence(Notification.EXTRA_TITLE));
        }
        String body = text(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (body.isEmpty()) {
            body = text(extras.getCharSequence(Notification.EXTRA_TEXT));
        }
        if (body.isEmpty()) {
            body = textArray(extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES));
        }
        if (body.isEmpty()) {
            body = messages(extras);
        }
        String sub = text(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        if (body.isEmpty() && sub.isEmpty()) {
            return;
        }
        if (!sub.isEmpty() && !body.contains(sub)) {
            body = body.isEmpty() ? sub : body + " " + sub;
        }
        if (title.isEmpty()) {
            title = "通知消息";
        }

        Prefs prefs = Prefs.get(this);

        // 读取范围完全由用户在 App 里勾选；勾选 QQ / 微信后同样会同步。
        Set<String> selected = prefs.notifPackages();
        boolean inScope = selected.isEmpty() ? isSmsPackage(pkg) : selected.contains(pkg);
        if (!inScope) {
            SmsLogger.event(this, "跳过通知（未在读取列表）pkg=" + pkg
                    + " title=" + title + " source=" + source);
            return;
        }

        String code = SmsCode.extract(body);
        boolean likelyCode = code != null
                || body.matches("(?s).*(验证码|校验码|动态码|确认码|激活码|安全码|提取码|代码|"
                + "verification code|passcode|\\botp\\b|認証番号|確認コード|認証コード).*");

        long ts = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
        String id = "notif-" + ts + "-"
                + Integer.toHexString((pkg + "|" + title + "|" + body).hashCode());
        Prefs.Msg msg = new Prefs.Msg(id, title, body, code, ts, false);

        // 只记录用户勾选的 App，避免把别的应用的通知正文写进日志。
        SmsLogger.logSms(this, "notification", msg,
                "pkg=" + pkg + " source=" + source + " likelyCode=" + likelyCode);
        if (likelyCode) {
            prefs.addEvent("通知读取：" + title + "，pkg=" + pkg + "，"
                    + body.length() + " 字" + (code == null ? "" : "，验证码 " + code));
        }
        if (!likelyCode) {
            return;
        }
        // 广播/inbox 可能已经处理了同一条短信，按“发件人+正文”去重。
        if (prefs.hasRecentContent(title, body, DEDUP_WINDOW_MS)
                || prefs.hasQueuedContent(title, body, DEDUP_WINDOW_MS)) {
            return;
        }
        prefs.addRecent(msg);
        SyncService.publishLocalSms(msg);
        prefs.enqueue(msg);
        if (!prefs.enabled()) {
            return;
        }
        Intent service = new Intent(this, SyncService.class)
                .setAction(SyncService.ACTION_SMS)
                .putExtra(SyncService.EXTRA_FROM, title)
                .putExtra(SyncService.EXTRA_BODY, body)
                .putExtra(SyncService.EXTRA_TS, ts)
                .putExtra(SyncService.EXTRA_ID, id);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Exception e) {
            SmsLogger.event(this, "通知消息启动同步服务失败："
                    + e.getClass().getSimpleName());
        }
    }

    private boolean isSmsPackage(String pkg) {
        String defaultPkg = Telephony.Sms.getDefaultSmsPackage(this);
        if (defaultPkg != null && defaultPkg.equals(pkg)) {
            return true;
        }
        switch (pkg) {
            case "com.android.mms":
            case "com.vivo.mms":
            case "com.google.android.apps.messaging":
            case "com.samsung.android.messaging":
            case "com.miui.smsextra":
            case "com.coloros.mms":
            case "com.oppo.mms":
            case "com.huawei.mms":
            case "com.hihonor.mms":
                return true;
            default:
                return false;
        }
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String textArray(CharSequence[] lines) {
        if (lines == null || lines.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CharSequence line : lines) {
            if (line != null && line.length() > 0) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String messages(Bundle extras) {
        Parcelable[] raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Parcelable parcelable : raw) {
            if (!(parcelable instanceof Bundle)) {
                continue;
            }
            Bundle bundle = (Bundle) parcelable;
            CharSequence text = bundle.getCharSequence("text");
            if (text != null && text.length() > 0) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }
}
