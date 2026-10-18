package com.smsbridge.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** 和桌面端同款的小手机吉祥物，会呼吸、眨眼。 */
public final class MascotView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean online;
    private boolean running = true;
    private long start = SystemClock.uptimeMillis();

    public MascotView(Context context) {
        super(context);
    }

    public MascotView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMood(boolean online, boolean running) {
        this.online = online;
        this.running = running;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float scale = Math.min(w / 120f, h / 132f);
        canvas.save();
        canvas.translate((w - 120 * scale) / 2f, (h - 132 * scale) / 2f);
        canvas.scale(scale, scale);

        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        canvas.translate(0, (float) Math.sin(t * 1.7f) * 2.2f);

        int body = !running ? 0xFFD9D3E3 : online ? 0xFF8FDFCC : 0xFFFFC3AF;
        int bodyDark = !running ? 0xFFBDB5CB : online ? 0xFF2FAF96 : 0xFFF08A6B;

        // 天线
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0x994C4459);
        canvas.drawLine(60, 25, 60, 13, paint);

        // 爱心
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF08A6B);
        path.reset();
        path.moveTo(60, 13);
        path.cubicTo(55, 9, 51, 5, 54, 2);
        path.cubicTo(56, 0, 59, 1, 60, 4);
        path.cubicTo(61, 1, 64, 0, 66, 2);
        path.cubicTo(69, 5, 65, 9, 60, 13);
        canvas.drawPath(path, paint);

        // 影子
        paint.setColor(0x186B5C86);
        canvas.drawOval(new RectF(30, 112, 90, 124), paint);

        // 身体
        paint.setColor(0x224C4459);
        canvas.drawRoundRect(new RectF(23, 29, 101, 118), 30, 30, paint);
        paint.setColor(body);
        canvas.drawRoundRect(new RectF(22, 24, 100, 118), 30, 30, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.4f);
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(new RectF(22, 24, 100, 118), 30, 30, paint);

        // 屏幕
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFDFB);
        canvas.drawRoundRect(new RectF(31, 36, 91, 100), 20, 20, paint);

        // 眼睛（偶尔眨一下）
        long now = SystemClock.uptimeMillis();
        boolean blink = (now % 4200) < 130;
        paint.setColor(0xFF4C4459);
        if (blink) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.6f);
            canvas.drawLine(44, 66, 50, 66, paint);
            canvas.drawLine(70, 66, 76, 66, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawOval(new RectF(45, 62, 51.4f, 70.2f), paint);
            canvas.drawOval(new RectF(69, 62, 75.4f, 70.2f), paint);
            paint.setColor(Color.WHITE);
            canvas.drawOval(new RectF(46.6f, 63.4f, 48.7f, 66f), paint);
            canvas.drawOval(new RectF(70.6f, 63.4f, 72.7f, 66f), paint);
        }

        // 腮红
        paint.setColor(0x66FF96A0);
        canvas.drawOval(new RectF(38, 76, 49, 82), paint);
        canvas.drawOval(new RectF(71, 76, 82, 82), paint);

        // 微笑
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFF4C4459);
        RectF smile = new RectF(52, 72, 70, 88);
        canvas.drawArc(smile, 20, 140, false, paint);

        // 底部圆点
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x664C4459);
        canvas.drawCircle(60, 109, 3, paint);

        // 状态装饰
        if (online) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f);
            paint.setColor(0xFF2FAF96);
            canvas.drawArc(new RectF(96, 42, 114, 60), -50, 100, false, paint);
            canvas.drawArc(new RectF(96, 34, 126, 64), -50, 100, false, paint);
        } else if (running) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(bodyDark);
            for (int i = 0; i < 3; i++) {
                canvas.drawCircle(104 + i * 5,
                        74 + (float) Math.sin(t * 2 + i) * 2, 1.6f, paint);
            }
        }
        canvas.restore();
        postInvalidateDelayed(33);
    }
}
