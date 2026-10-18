package com.smsbridge.pc.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/** 配色、字体和绘制小工具。 */
public final class Theme {
    public static final Color BG_TOP = new Color(0xFFF8F4);
    public static final Color BG_BOTTOM = new Color(0xEEF7FF);
    public static final Color CARD = new Color(0xFFFFFDFC);
    public static final Color CARD_SOFT = new Color(0xFFF7F2);
    public static final Color INK = new Color(0x4C4459);
    public static final Color MUTED = new Color(0x958DA6);
    public static final Color LINE = new Color(0xF0E7F0);
    public static final Color MINT = new Color(0x8FDFCC);
    public static final Color MINT_DARK = new Color(0x2FAF96);
    public static final Color MINT_SOFT = new Color(0xE4F8F3);
    public static final Color PEACH = new Color(0xFFC3AF);
    public static final Color PEACH_DARK = new Color(0xF08A6B);
    public static final Color PEACH_SOFT = new Color(0xFFF0EA);
    public static final Color LAVENDER = new Color(0xC9BCFF);
    public static final Color LAVENDER_SOFT = new Color(0xF2EEFF);
    public static final Color YELLOW = new Color(0xFFD98E);
    public static final Color DANGER = new Color(0xE8746B);
    public static final Color SHADOW = new Color(0x6B5C86);

    private static final String[] FONT_NAMES = {
            "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC",
            "Noto Sans CJK SC", "Source Han Sans SC", "SansSerif"
    };

    private static String fontName;

    private Theme() {
    }

    public static synchronized String fontName() {
        if (fontName == null) {
            String[] available = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            outer:
            for (String candidate : FONT_NAMES) {
                for (String name : available) {
                    if (name.equalsIgnoreCase(candidate)) {
                        fontName = name;
                        break outer;
                    }
                }
            }
            if (fontName == null) {
                fontName = Font.SANS_SERIF;
            }
        }
        return fontName;
    }

    public static Font font(int style, float size) {
        return new Font(fontName(), style, Math.round(size));
    }

    public static Font regular(float size) {
        return font(Font.PLAIN, size);
    }

    public static Font bold(float size) {
        return font(Font.BOLD, size);
    }

    public static Graphics2D prepare(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    public static void fillRound(Graphics2D g2, double x, double y, double w, double h,
                                 double radius, Color color) {
        g2.setColor(color);
        g2.fill(new RoundRectangle2D.Double(x, y, w, h, radius, radius));
    }

    public static void shadow(Graphics2D g2, double x, double y, double w, double h,
                              double radius, int strength) {
        for (int i = 6; i >= 1; i--) {
            int alpha = Math.max(2, strength / (i + 1));
            g2.setColor(new Color(SHADOW.getRed(), SHADOW.getGreen(), SHADOW.getBlue(), alpha));
            g2.fill(new RoundRectangle2D.Double(x - i * 0.6, y + i * 0.9,
                    w + i * 1.2, h + i * 1.2, radius + i, radius + i));
        }
    }

    public static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                Math.max(0, Math.min(255, alpha)));
    }

    public static String ellipsis(String text, java.awt.FontMetrics metrics, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + ellipsis) > maxWidth) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    public static String htmlEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("\n", "<br>");
    }
}
