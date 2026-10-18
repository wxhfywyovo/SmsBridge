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
    /** 同一条通知（key + 正文完全相同）在这段时间内只上报一次，跨进程重启也有效。 */
    private static final long HANDLED_WINDOW_MS = 3 * 60 * 1000L;
    /** 同一个发件人 + 同一个验证码，在这段时间内只上报一次。 */
    private static final long CODE_DEDUP_WINDOW_MS = 2 * 60 * 1000L;
    /** 刚连上时只补扫最近 1 分钟内发出的通知，避免把通知栏里的旧短信再上报一遍。 */
    private static final long ATTACH_SCAN_MS = 60 * 1000L;
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
                long now = System.currentTimeMillis();
                for (StatusBarNotification sbn : active) {
                    // 只补扫刚发出来的，通知栏里挂着的旧短信不再重复上报
                    if (sbn == null || now - sbn.getPostTime() > ATTACH_SCAN_MS) {
                        continue;
                    }
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
        // 分组汇总通知（「短信 · 今天」这种）本身不带正文，真正的消息在子通知里，跳过。
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;
        }
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
        if (body.isEmpty()) {
            body = text(extras.getCharSequence(Notification.EXTRA_TITLE_BIG));
        }
        if (body.isEmpty() && notification.tickerText != null) {
            body = text(notification.tickerText);
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
            // 通知里确实没有正文（锁屏隐藏内容 / 只推空壳通知）：
            // 不同步到电脑，避免把占位文字写进剪贴板；只在手机上提示一次。
            SyncService.publishHint("这条短信没有正文",
                    "短信 App 的通知里没有内容，请在系统设置里允许它显示通知内容");
            return;
        }
        if (title.isEmpty()) {
            title = "短信";
        }

        Prefs prefs = Prefs.get(this);
        // 同一条通知（key + 正文都相同）短期内只上报一次：
        // 短信 App 会反复重发通知，通知读取服务重连时也会把还在通知栏里的通知再扫一遍。
        String notifKey = sbn.getKey() == null ? "" : sbn.getKey();
        String fingerprint = Integer.toHexString((pkg + "|" + title + "|" + body).hashCode());
        if (!prefs.markNotifHandled(notifKey, fingerprint, HANDLED_WINDOW_MS)) {
            return;
        }
        String code = SmsCode.extract(body);
        long ts = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
        String id = "notif-" + ts + "-" + fingerprint;
        Prefs.Msg msg = new Prefs.Msg(id, title, body, code, ts, false);

        // 第二层：同一条内容在队列/历史里刚出现过也跳过（防跨 key 的重复通知）。
        if (prefs.hasRecentContent(title, body, DEDUP_WINDOW_MS)
                || prefs.hasQueuedContent(title, body, DEDUP_WINDOW_MS)) {
            return;
        }
        // 第三层：正文里夹了「未读 X 条」之类变动文字时，用验证码本身判重。
        if (prefs.hasRecentCode(title, code, CODE_DEDUP_WINDOW_MS)) {
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
