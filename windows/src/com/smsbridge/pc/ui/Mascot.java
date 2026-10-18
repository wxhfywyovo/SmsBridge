package com.smsbridge.pc.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;
import javax.swing.Timer;

/** 会呼吸、会眨眼的小手机吉祥物。 */
public final class Mascot extends JComponent {
    private boolean online;
    private boolean serverRunning = true;
    private final Timer timer;
    private float phase;

    public Mascot() {
        setOpaque(false);
        setPreferredSize(new Dimension(110, 120));
        timer = new Timer(40, e -> {
            phase += 0.06f;
            repaint();
        });
    }

    public void setMood(boolean online, boolean serverRunning) {
        this.online = online;
        this.serverRunning = serverRunning;
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        timer.start();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.prepare(g);
        double scale = Math.min(getWidth() / 120.0, getHeight() / 132.0);
        g2.translate((getWidth() - 120 * scale) / 2.0, (getHeight() - 132 * scale) / 2.0);
        g2.scale(scale, scale);

        double bob = Math.sin(phase) * 2.2;
        g2.translate(0, bob);

        Color body = !serverRunning ? new Color(0xD9D3E3)
                : online ? Theme.MINT : Theme.PEACH;
        Color bodyDark = !serverRunning ? new Color(0xBDB5CB)
                : online ? Theme.MINT_DARK : Theme.PEACH_DARK;

        // 天线 + 小爱心
        g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(Theme.alpha(Theme.INK, 150));
        g2.drawLine(60, 25, 60, 13);
        g2.setColor(Theme.PEACH_DARK);
        Path2D heart = new Path2D.Double();
        heart.moveTo(60, 13);
        heart.curveTo(55, 9, 51, 5, 54, 2);
        heart.curveTo(56, 0, 59, 1, 60, 4);
        heart.curveTo(61, 1, 64, 0, 66, 2);
        heart.curveTo(69, 5, 65, 9, 60, 13);
        g2.fill(heart);

        // 身体
        g2.setColor(new Color(0, 0, 0, 22));
        g2.fill(new RoundRectangle2D.Double(23, 29, 78, 94, 30, 30));
        g2.setColor(body);
        g2.fill(new RoundRectangle2D.Double(22, 24, 78, 94, 30, 30));
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(3.4f));
        g2.draw(new RoundRectangle2D.Double(22, 24, 78, 94, 30, 30));

        // 屏幕
        g2.setColor(new Color(0xFFFDFB));
        g2.fill(new RoundRectangle2D.Double(31, 36, 60, 64, 20, 20));

        // 眼睛
        long now = System.currentTimeMillis();
        boolean blink = (now % 4200) < 130;
        g2.setColor(Theme.INK);
        if (blink) {
            g2.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(44, 66, 50, 66);
            g2.drawLine(70, 66, 76, 66);
        } else {
            g2.fill(new Ellipse2D.Double(45, 62, 6.4, 8.2));
            g2.fill(new Ellipse2D.Double(69, 62, 6.4, 8.2));
            g2.setColor(Color.WHITE);
            g2.fill(new Ellipse2D.Double(46.6, 63.4, 2.1, 2.6));
            g2.fill(new Ellipse2D.Double(70.6, 63.4, 2.1, 2.6));
        }

        // 腮红
        g2.setColor(new Color(255, 150, 160, 90));
        g2.fill(new Ellipse2D.Double(38, 76, 11, 6));
        g2.fill(new Ellipse2D.Double(71, 76, 11, 6));

        // 微笑
        g2.setColor(Theme.INK);
        g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Arc2D.Double(52, 72, 16, 14, 200, 140, Arc2D.OPEN));

        // 底部小圆点
        g2.setColor(Theme.alpha(Theme.INK, 60));
        g2.fillOval(57, 106, 6, 6);

        // 状态小装饰
        if (online) {
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(Theme.MINT_DARK);
            g2.draw(new Arc2D.Double(96, 42, 18, 18, 40, 100, Arc2D.OPEN));
            g2.draw(new Arc2D.Double(96, 34, 30, 30, 40, 100, Arc2D.OPEN));
        } else if (serverRunning) {
            g2.setColor(bodyDark);
            for (int i = 0; i < 3; i++) {
                g2.fillOval(104 + i * 5, 74 + (int) (Math.sin(phase + i) * 2), 3, 3);
            }
        }

        g2.dispose();
    }
}
