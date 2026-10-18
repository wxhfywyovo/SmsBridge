package com.smsbridge.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/**
 * 收到短信后先保存到手机本地，再交给前台服务同步。
 *
 * 注意：这里不能在“未开启同步”时直接 return，否则用户会以为权限没用、
 * 短信凭空消失。即使服务没开，也要把短信显示在 App 的最近列表里。
 */
public final class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }
        Prefs prefs = Prefs.get(context);
        try {
            SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (messages == null || messages.length == 0) {
                prefs.addEvent("收到短信广播，但没有解析出 SmsMessage");
                return;
            }
            StringBuilder body = new StringBuilder();
            for (SmsMessage message : messages) {
                if (message != null && message.getMessageBody() != null) {
                    body.append(message.getMessageBody());
                }
            }
            if (body.length() == 0) {
                prefs.addEvent("收到短信广播，但正文为空");
                return;
            }
            String from = messages[0].getDisplayOriginatingAddress();
            if (from == null || from.isEmpty()) {
                from = "未知号码";
            }
            long ts = messages[0].getTimestampMillis();
            if (ts <= 0) {
                ts = System.currentTimeMillis();
            }
            String id = Long.toHexString(ts) + "-"
                    + Integer.toHexString((from + "|" + body).hashCode());
            String code = SmsCode.extract(body.toString());

            prefs.addEvent("收到短信：" + from + "，" + body.length() + " 字"
                    + (code == null ? "" : "，本地识别 " + code));
            Prefs.Msg local = new Prefs.Msg(id, from, body.toString(), code, ts, false);
            prefs.addRecent(local);
            SyncService.publishLocalSms(local);

            if (!prefs.enabled()) {
                SmsLogger.logSms(context, "broadcast", local,
                        "enabled=false（只在手机保存）");
                prefs.addEvent("同步开关未开启，短信只保存在手机，未发送到电脑");
                return;
            }

            // 先入队，再启动服务；即使启动服务失败/被系统拦截，下次连上也会补发。
            prefs.enqueue(local);
            SmsLogger.logSms(context, "broadcast", local, "enabled=true queued=true");
            Intent service = new Intent(context, SyncService.class)
                    .setAction(SyncService.ACTION_SMS)
                    .putExtra(SyncService.EXTRA_FROM, from)
                    .putExtra(SyncService.EXTRA_BODY, body.toString())
                    .putExtra(SyncService.EXTRA_TS, ts)
                    .putExtra(SyncService.EXTRA_ID, id);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(service);
                } else {
                    context.startService(service);
                }
                prefs.addEvent("已请求同步服务发送短信");
            } catch (Exception e) {
                String reason = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                prefs.addEvent("启动同步服务失败：" + reason);
            }
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            prefs.addEvent("短信接收异常：" + reason);
            SmsLogger.event(context, "短信接收异常：" + reason);
        }
    }
}
