package com.smsbridge.mobile;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.Set;

/**
 * 短信通过「通知读取」获取。
 *
 * 开启系统的「通知读取」（通知使用权）后，这里能拿到系统短信 App 通知里的
 * 发件人和完整正文，再走同一条同步链路；用户勾选的其他 App 只同步含验证码的通知。
 */
public final class SmsNotificationListener extends NotificationListenerService {
    /**
     * 去重窗口：只用来防止同一条通知（系统重发/多条渠道）被重复入队。
     * 窗口刻意很短——同一条验证码隔几分钟重发是合法的新短信，
     * 必须能再次入队，所以不能用长窗口按「发件人+正文」去重。
     */
    private static final long DEDUP_WINDOW_MS = 90 * 1000L;
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
        if (pkg == null) {
            // 极少数系统通知拿不到包名，记一条便于排查。
            drop("", "", "pkg=null source=" + source, false);
            return;
        }
        if (getPackageName().equals(pkg)) {
            return;
        }
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        if (extras == null) {
            drop(pkg, "", "extras=null source=" + source, false);
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
        // 部分 App 把最新一条消息放在 EXTRA_REMOTE_INPUT_HISTORY，作为补充兜底（改动 C）。
        if (body.isEmpty()) {
            body = textArray(extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY));
        }
        String sub = text(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));

        // 调试日志：无论后续如何处理，都记一条「收到通知」事件。
        // 这让「找不到短信」时能判断：是通知没到，还是卡在哪个环节。
        SmsLogger.event(this, "收到通知 pkg=" + pkg + " title=" + title + " source=" + source);

        Prefs prefs = Prefs.get(this);
        // 系统短信永远在读取范围内，不受勾选影响；勾选的其他 App 才按列表过滤。
        Set<String> selected = prefs.notifPackages();
        boolean isSms = isSmsPackage(pkg);
        boolean inScope = isSms || selected.contains(pkg);

        if (body.isEmpty() && sub.isEmpty()) {
            if (isSms) {
                // 系统短信正文被系统隐藏（锁屏隐藏敏感内容 / 厂商隐私 / Android 12+ 脱敏），
                // 照样入队，让用户至少知道「来短信了」，并提示去关闭隐藏。
                body = "（短信正文被系统隐藏，请在系统设置里关闭该短信 App 的"
                        + "「隐藏敏感通知内容」后重试）";
                // 不 return，继续往下走 inScope / likelyCode 判定。
                prefs.addEvent("收到系统短信通知，但正文被系统隐藏（" + pkg + "）");
            } else {
                drop(pkg, title, "正文为空（取不到 EXTRA_TEXT/BIG_TEXT/TEXT_LINES/"
                        + "MESSAGES/REMOTE_INPUT_HISTORY） source=" + source
                        + " keys=" + extraKeys(extras), inScope);
                return;
            }
        }
        if (!sub.isEmpty() && !body.contains(sub)) {
            body = body.isEmpty() ? sub : body + " " + sub;
        }
        if (title.isEmpty()) {
            title = "通知消息";
        }

        if (!inScope) {
            drop(pkg, title, "跳过：未在读取列表 isSms=" + isSms
                    + " 已勾选=" + selected.size() + " source=" + source, inScope);
            return;
        }

        // 系统短信一律同步（短信正文本来就该同步，不做验证码过滤）；
        // 其它勾选的 App 只同步正文含验证码的通知，避免把聊天记录同步过去。
        String code = SmsCode.extract(body);
        boolean likelyCode = isSms || code != null
                || body.matches("(?s).*(验证码|校验码|动态码|确认码|激活码|安全码|提取码|代码|"
                + "verification code|passcode|\\botp\\b|認証番号|確認コード|認証コード).*");

        long ts = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
        String id = "notif-" + ts + "-"
                + Integer.toHexString((pkg + "|" + title + "|" + body).hashCode());
        Prefs.Msg msg = new Prefs.Msg(id, title, body, code, ts, false);

        // 只记录范围内的 App，避免把别的应用的通知正文写进日志。
        SmsLogger.logSms(this, "notification", msg,
                "pkg=" + pkg + " source=" + source + " likelyCode=" + likelyCode);
        if (likelyCode) {
            prefs.addEvent("通知读取：" + title + "，pkg=" + pkg + "，"
                    + body.length() + " 字" + (code == null ? "" : "，验证码 " + code));
        }
        if (!likelyCode) {
            drop(pkg, title, "跳过：正文不含验证码（非短信 App） source=" + source, inScope);
            return;
        }
        // 防止同一条通知被重复入队；窗口很短，隔几分钟重发的同文验证码不会丢。
        if (prefs.hasRecentContent(title, body, DEDUP_WINDOW_MS)
                || prefs.hasQueuedContent(title, body, DEDUP_WINDOW_MS)) {
            drop(pkg, title, "跳过：90 秒内重复 source=" + source, inScope);
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

    /** 判断是否为系统短信 App：默认短信应用，或各厂商已知的短信包名。 */
    public static boolean isSmsPackage(Context context, String pkg) {
        if (pkg == null) {
            return false;
        }
        try {
            String defaultPkg = Telephony.Sms.getDefaultSmsPackage(context);
            if (defaultPkg != null && defaultPkg.equals(pkg)) {
                return true;
            }
        } catch (Exception ignored) {
            // 某些设备上查询默认短信应用可能失败，继续走白名单
        }
        switch (pkg) {
            case "com.android.mms":
            case "com.android.messaging":
            case "com.vivo.mms":
            case "com.google.android.apps.messaging":
            case "com.samsung.android.messaging":
            case "com.miui.smsextra":
            case "com.coloros.mms":
            case "com.oppo.mms":
            case "com.oneplus.mms":
            case "com.huawei.mms":
            case "com.hihonor.mms":
            case "com.sonyericsson.conversations":
            case "com.transsion.mms":
                return true;
            default:
                return false;
        }
    }

    private boolean isSmsPackage(String pkg) {
        return isSmsPackage(this, pkg);
    }

    /**
     * 通知被丢弃时记一条诊断事件：包名 + 标题 + 丢弃原因。排查「收不到」就靠它。
     * 当 inScope 为 true（系统短信或用户已勾选的 App）时，额外把原因写进 App 内事件列表，
     * 方便用户在 App 诊断里直接看到「这条通知为何被丢」；其它 App 的通知只写文件日志，避免刷屏。
     */
    private void drop(String pkg, String title, String reason, boolean inScope) {
        SmsLogger.event(this, "丢弃通知 pkg=" + (pkg == null || pkg.isEmpty()
                ? "-" : pkg) + " 标题=" + (title == null || title.isEmpty() ? "-" : title)
                + " 原因=" + reason);
        if (inScope) {
            Prefs.get(this).addEvent("丢弃通知：" + title + "（" + pkg + "）：" + reason);
        }
    }

    /** 只取前几个键名，避免把通知里的正文写进日志。 */
    private static String extraKeys(Bundle extras) {
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (String key : extras.keySet()) {
            if (n > 0) {
                sb.append(',');
            }
            sb.append(key);
            if (++n >= 12) {
                sb.append(",…");
                break;
            }
        }
        return sb.append(']').toString();
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
