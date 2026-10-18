package com.smsbridge.mobile;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 短信通过「通知读取」获取。
 *
 * 开启系统的「通知使用权」后，这里能拿到系统短信 App 通知里的
 * 发件人和完整正文，再走同一条同步链路。
 */
public final class SmsNotificationListener extends NotificationListenerService {
    /** 去重窗口：只用来防止同一条通知（系统重发/多条渠道）被重复入队。 */
    private static final long DEDUP_WINDOW_MS = 90 * 1000L;
    private static volatile boolean connected;

    public static boolean isConnected() {
        return connected;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        // 刚开启通知使用权时，把通知栏里已经存在的消息也扫一遍。
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    handleNotification(sbn);
                }
            }
        } catch (Exception ignored) {
            // 忽略
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        connected = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handleNotification(sbn);
    }

    private void handleNotification(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return;
        }
        String pkg = sbn.getPackageName();
        if (pkg == null || getPackageName().equals(pkg)) {
            return;
        }
        if (!isSmsPackage(pkg)) {
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
        if (body.isEmpty()) {
            body = textArray(extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY));
        }
        String sub = text(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));

        // vivo 等部分机型把短信正文塞进标题，格式「发件人∣正文」，拆出来当正文用。
        if (body.isEmpty() && sub.isEmpty() && title.contains("∣")) {
            String[] parts = title.split("∣", 2);
            if (parts.length >= 2) {
                body = parts[1].trim();
            }
        }
        if (!sub.isEmpty() && !body.contains(sub)) {
            body = body.isEmpty() ? sub : body + " " + sub;
        }
        if (body.isEmpty()) {
            // 锁屏隐藏敏感内容等场景拿不到正文，仍然提示有一条短信到达。
            body = "（短信正文被系统隐藏，请在系统设置里允许该短信 App 显示完整通知）";
        }
        if (title.isEmpty()) {
            title = "短信";
        }

        Prefs prefs = Prefs.get(this);
        String code = SmsCode.extract(body);
        long ts = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
        String id = "notif-" + ts + "-"
                + Integer.toHexString((pkg + "|" + title + "|" + body).hashCode());
        Prefs.Msg msg = new Prefs.Msg(id, title, body, code, ts, false);

        // 防止同一条通知被重复入队；窗口很短，隔几分钟重发的同文验证码不会丢。
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
        } catch (Exception ignored) {
            // 忽略
        }
    }

    /** 判断是否为系统短信 App：包名前缀匹配 + 各厂商已知包名。 */
    public static boolean isSmsPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        // 各厂商定制短信包名基本都带 com.android.mms / com.mms 前缀。
        if (pkg.startsWith("com.android.mms") || pkg.startsWith("com.mms")) {
            return true;
        }
        switch (pkg) {
            case "com.google.android.apps.messaging":
            case "com.samsung.android.messaging":
            case "com.miui.smsextra":
            case "com.transsion.mms":
            case "com.sonyericsson.conversations":
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
