package com.smsbridge.mobile;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 排查用短信日志。
 *
 * 位置：Android/data/com.smsbridge.mobile/files/sms-debug.log（外部私有目录），
 * 没有外部存储时退回 App 内部 filesDir。日志包含短信完整正文，排查后请清空。
 */
public final class SmsLogger {
    private static final String FILE_NAME = "sms-debug.log";
    private static final long MAX_BYTES = 512 * 1024;

    private SmsLogger() {
    }

    public static synchronized void logSms(Context context, String source,
                                           Prefs.Msg msg, String extra) {
        if (msg == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append('\n');
            sb.append('[').append(now()).append("][").append(source).append("]\n");
            sb.append("from=").append(safe(msg.from))
                    .append(" code=").append(safe(msg.code))
                    .append(" id=").append(safe(msg.id))
                    .append(" ts=").append(msg.ts)
                    .append(" len=").append(msg.body == null ? 0 : msg.body.length());
            if (extra != null && !extra.isEmpty()) {
                sb.append(' ').append(extra);
            }
            sb.append('\n');
            sb.append("----- SMS BODY BEGIN -----\n");
            sb.append(msg.body == null ? "" : msg.body);
            sb.append("\n----- SMS BODY END -----\n");
            append(context, sb.toString());
        } catch (Throwable ignored) {
            // 日志失败绝对不能影响短信同步
        }
    }

    public static synchronized void event(Context context, String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        try {
            append(context, "[" + now() + "][event] " + line + "\n");
        } catch (Throwable ignored) {
            // 日志失败绝对不能影响同步
        }
    }

    public static synchronized String read(Context context) {
        try {
            File file = file(context);
            if (!file.isFile()) {
                return "";
            }
            try (FileInputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                String text = out.toString(StandardCharsets.UTF_8.name());
                // 只展示最近一部分，避免弹窗过大
                if (text.length() > 120_000) {
                    text = "…（只显示最近 12 万字符）…\n"
                            + text.substring(text.length() - 120_000);
                }
                return text;
            }
        } catch (Throwable e) {
            return "读取日志失败：" + e.getMessage();
        }
    }

    public static synchronized void clear(Context context) {
        try {
            File file = file(context);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(file.getParentFile(), FILE_NAME + ".1").delete();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    public static File file(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, FILE_NAME);
    }

    public static String path(Context context) {
        try {
            return file(context).getAbsolutePath();
        } catch (Throwable e) {
            return "(日志路径不可用)";
        }
    }

    private static void append(Context context, String text) {
        File file = file(context);
        try {
            rotateIfNeeded(file);
            boolean fresh = !file.isFile() || file.length() == 0;
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                if (fresh) {
                    out.write(header(context).getBytes(StandardCharsets.UTF_8));
                }
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException ignored) {
            // 日志失败不能影响短信同步
        }
    }

    private static void rotateIfNeeded(File file) {
        if (!file.isFile() || file.length() <= MAX_BYTES) {
            return;
        }
        File old = new File(file.getParentFile(), FILE_NAME + ".1");
        //noinspection ResultOfMethodCallIgnored
        old.delete();
        //noinspection ResultOfMethodCallIgnored
        file.renameTo(old);
    }

    private static String header(Context context) {
        String version = "?";
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            version = info.versionName + " (" + info.versionCode + ")";
        } catch (Exception ignored) {
            // 忽略
        }
        return "===== SmsBridge SMS debug log =====\n"
                + "app=" + version + "\n"
                + "device=" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "android=" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "path=" + path(context) + "\n"
                + "注意：日志含完整短信内容，排查后请清空。\n"
                + "===================================\n";
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
