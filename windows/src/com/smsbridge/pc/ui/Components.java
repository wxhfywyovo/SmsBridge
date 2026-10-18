package com.smsbridge.pc.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** 一整套圆角小部件，避免使用系统默认的方角控件。 */
public final class Components {
    private Components() {
    }

    public static class RoundedPanel extends JPanel {
        private final Color fill;
        private final int radius;
        private final boolean shadow;

        public RoundedPanel(java.awt.LayoutManager layout, Color fill, int radius, boolean shadow) {
            super(layout);
            this.fill = fill;
            this.radius = radius;
            this.shadow = shadow;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.prepare(g);
            if (shadow) {
                Theme.shadow(g2, 4, 5, getWidth() - 8, getHeight() - 10, radius, 26);
            }
            Theme.fillRound(g2, 0, 0, getWidth(), getHeight(), radius, fill);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static final class Card extends RoundedPanel {
        public Card(java.awt.LayoutManager layout) {
            super(layout, Theme.CARD, 26, true);
            setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        }
    }

    public static final class SoftButton extends JComponent {
        private String text;
        private Color background;
        private Color foreground;
        private Color border;
        private int radius = 18;
        private boolean hover;
        private boolean pressed;
        private final List<ActionListener> listeners = new ArrayList<>();
        private Font font;

        public SoftButton(String text, Color background, Color foreground) {
            this.text = text;
            this.background = background;
            this.foreground = foreground;
            this.font = Theme.bold(13);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            setPreferredSize(new Dimension(measureWidth() + 30, 38));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    pressed = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && isEnabled()) {
                        pressed = true;
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    boolean wasPressed = pressed;
                    pressed = false;
                    repaint();
                    if (wasPressed && SwingUtilities.isLeftMouseButton(e)
                            && contains(e.getPoint()) && isEnabled()) {
                        fire();
                    }
                }
            });
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "click");
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "click");
            getActionMap().put("click", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (isEnabled()) {
                        fire();
                    }
                }
            });
        }

        public SoftButton ghost(Color color) {
            this.background = new Color(0, 0, 0, 0);
            this.border = color == null ? Theme.LINE : color;
            repaint();
            return this;
        }

        public SoftButton withBorder(Color color) {
            this.border = color;
            return this;
        }

        public SoftButton withFont(Font font) {
            this.font = font;
            int height = Math.max(36, getFontMetrics(font).getHeight() + 16);
            setPreferredSize(new Dimension(measureWidth() + 30, height));
            return this;
        }

        public void setText(String text) {
            this.text = text;
            setPreferredSize(new Dimension(measureWidth() + 30, getHeight()));
            revalidate();
            repaint();
        }

        public String getText() {
            return text;
        }

        public void setBackgroundColor(Color color) {
            this.background = color;
            repaint();
        }

        public void setForegroundColor(Color color) {
            this.foreground = color;
            repaint();
        }

        public void setRadius(int radius) {
            this.radius = radius;
            repaint();
        }

        public void addActionListener(ActionListener listener) {
            listeners.add(listener);
        }

        public void doClick() {
            if (isEnabled()) {
                fire();
            }
        }

        private void fire() {
            ActionEvent event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, text);
            for (ActionListener listener : new ArrayList<>(listeners)) {
                listener.actionPerformed(event);
            }
        }

        private int measureWidth() {
            if (text == null) {
                return 20;
            }
            return getFontMetrics(font).stringWidth(text);
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setCursor(enabled ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : Cursor.getDefaultCursor());
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.prepare(g);
            int w = getWidth();
            int h = getHeight();
            int y = pressed ? 1 : 0;
            Color bg = background;
            if (!isEnabled()) {
                bg = Theme.alpha(background, 90);
            } else if (hover) {
                bg = background.getAlpha() == 0
                        ? new Color(0xF3EEFA)
                        : brighten(background, 1.04);
            }
            if (bg.getAlpha() > 0) {
                g2.setColor(new Color(Theme.SHADOW.getRed(), Theme.SHADOW.getGreen(),
                        Theme.SHADOW.getBlue(), 18));
                g2.fillRoundRect(0, y + 3, w, h - 3, radius, radius);
                g2.setColor(bg);
                g2.fillRoundRect(0, y, w, h - 3, radius, radius);
            }
            if (border != null) {
                g2.setStroke(new BasicStroke(1.4f));
                g2.setColor(isEnabled() ? border : Theme.alpha(border, 100));
                g2.drawRoundRect(0, y, w - 1, h - 4, radius, radius);
            }
            if (hasFocus()) {
                g2.setStroke(new BasicStroke(1.4f));
                g2.setColor(Theme.alpha(Theme.MINT_DARK, 90));
                g2.drawRoundRect(2, y + 2, w - 5, h - 8, radius, radius);
            }
            g2.setFont(font);
            g2.setColor(isEnabled() ? foreground : Theme.alpha(foreground, 120));
            FontMetrics fm = g2.getFontMetrics();
            int textY = y + (h - 3 - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(text, (w - fm.stringWidth(text)) / 2, textY);
            g2.dispose();
        }

        private static Color brighten(Color color, double factor) {
            int r = Math.min(255, (int) (color.getRed() * factor));
            int g = Math.min(255, (int) (color.getGreen() * factor));
            int b = Math.min(255, (int) (color.getBlue() * factor));
            return new Color(r, g, b, color.getAlpha());
        }
    }

    public static final class Pill extends JComponent {
        private String text;
        private Color dot;
        private Color background;

        public Pill(String text, Color dot, Color background) {
            this.text = text;
            this.dot = dot;
            this.background = background;
            setOpaque(false);
            setFont(Theme.bold(12));
            setPreferredSize(new Dimension(measureWidth(), 30));
        }

        public void setState(String text, Color dot, Color background) {
            this.text = text;
            this.dot = dot;
            this.background = background;
            setPreferredSize(new Dimension(measureWidth(), 30));
            revalidate();
            repaint();
        }

        private int measureWidth() {
            return getFontMetrics(Theme.bold(12)).stringWidth(text) + 38;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.prepare(g);
            int w = getWidth();
            int h = getHeight();
            Theme.fillRound(g2, 0, 0, w, h, h, background);
            int dotSize = 8;
            int dotX = 13;
            int dotY = (h - dotSize) / 2;
            g2.setColor(Theme.alpha(dot, 60));
            g2.fillOval(dotX - 3, dotY - 3, dotSize + 6, dotSize + 6);
            g2.setColor(dot);
            g2.fillOval(dotX, dotY, dotSize, dotSize);
            g2.setFont(getFont());
            g2.setColor(Theme.INK);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, 27, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    public static final class ToggleSwitch extends JComponent {
        private boolean on;
        private boolean hover;
        private float position;
        private Consumer<Boolean> listener;
        private final Timer timer;

        public ToggleSwitch(boolean initial) {
            this.on = initial;
            this.position = initial ? 1f : 0f;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            setPreferredSize(new Dimension(50, 28));
            timer = new Timer(16, e -> {
                float target = on ? 1f : 0f;
                float delta = target - position;
                if (Math.abs(delta) < 0.02f) {
                    position = target;
                    ((Timer) e.getSource()).stop();
                } else {
                    position += delta * 0.28f;
                }
                repaint();
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        toggle();
                    }
                }
            });
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle");
            getActionMap().put("toggle", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    toggle();
                }
            });
        }

        public void setOn(boolean value) {
            if (this.on == value) {
                position = value ? 1f : 0f;
                repaint();
                return;
            }
            this.on = value;
            timer.start();
            repaint();
        }

        public boolean isOn() {
            return on;
        }

        public void setListener(Consumer<Boolean> listener) {
            this.listener = listener;
        }

        private void toggle() {
            setOn(!on);
            if (listener != null) {
                listener.accept(on);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.prepare(g);
            int w = getWidth();
            int h = getHeight();
            Color track = on ? Theme.MINT_DARK : new Color(0xE7E1EE);
            if (hover) {
                track = on ? Theme.MINT_DARK.brighter() : new Color(0xDED6E8);
            }
            Theme.fillRound(g2, 0, 0, w, h, h, track);
            int knob = h - 6;
            int x = (int) (3 + (w - knob - 6) * position);
            g2.setColor(new Color(0, 0, 0, 28));
            g2.fillOval(x + 1, 4, knob, knob);
            g2.setColor(Color.WHITE);
            g2.fillOval(x, 3, knob, knob);
            g2.dispose();
        }
    }

    public static final class PinDisplay extends JComponent {
        private String pin = "";

        public PinDisplay() {
            setOpaque(false);
            setPreferredSize(new Dimension(276, 48));
        }

        public void setPin(String pin) {
            this.pin = pin == null ? "" : pin;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.prepare(g);
            int box = 40;
            int gap = 7;
            int y = Math.max(0, (getHeight() - box) / 2);
            Font font = Theme.bold(23);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            for (int i = 0; i < 6; i++) {
                int x = i * (box + gap);
                Theme.fillRound(g2, x, y, box, box, 12, Theme.LAVENDER_SOFT);
                g2.setColor(new Color(0xE4DAFA));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(x, y, box, box, 12, 12);
                String ch = i < pin.length() ? pin.substring(i, i + 1) : "·";
                g2.setColor(i < pin.length() ? Theme.INK : new Color(0xC9C0D8));
                int tx = x + (box - fm.stringWidth(ch)) / 2;
                int ty = y + (box - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(ch, tx, ty);
            }
            g2.dispose();
        }
    }

    public static final class CodeDisplay extends JComponent {
        private String code = "";
        private boolean hasCode;

        public CodeDisplay() {
            setOpaque(false);
            setPreferredSize(new Dimension(260, 58));
        }

        public void setCode(String code) {
            this.code = code == null ? "" : code;
            this.hasCode = code != null && !code.isEmpty();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.prepare(g);
            String text = hasCode ? code : "— — —";
            Font font = Theme.bold(hasCode ? 40 : 30);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            int spacing = hasCode ? 5 : 2;
            int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            int x = 0;
            g2.setColor(hasCode ? Theme.MINT_DARK : new Color(0xC9C0D8));
            for (int i = 0; i < text.length(); i++) {
                String ch = text.substring(i, i + 1);
                g2.drawString(ch, x, y);
                x += fm.stringWidth(ch) + spacing;
            }
            g2.dispose();
        }
    }

    public static final class WrappedText extends JComponent {
        private String text;
        private Font font;
        private Color color;
        private final int maxWidth;

        public WrappedText(String text, Font font, Color color, int maxWidth) {
            this.text = text == null ? "" : text;
            this.font = font;
            this.color = color;
            this.maxWidth = maxWidth;
            setOpaque(false);
            updatePreferredHeight();
        }

        public void setText(String text) {
            this.text = text == null ? "" : text;
            updatePreferredHeight();
            revalidate();
            repaint();
        }

        public void setTextColor(Color color) {
            this.color = color;
            repaint();
        }

        private void updatePreferredHeight() {
            List<String> lines = wrap();
            int lineHeight = getFontMetrics(font).getHeight();
            setPreferredSize(new Dimension(maxWidth, Math.max(lineHeight, lines.size() * lineHeight)));
        }

        private List<String> wrap() {
            List<String> lines = new ArrayList<>();
            FontMetrics fm = getFontMetrics(font);
            for (String paragraph : text.split("\n", -1)) {
                if (paragraph.isEmpty()) {
                    lines.add("");
                    continue;
                }
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < paragraph.length(); i++) {
                    char c = paragraph.charAt(i);
                    if (line.length() > 0
                            && fm.stringWidth(line.toString() + c) > maxWidth) {
                        int space = line.lastIndexOf(" ");
                        if (space > line.length() / 3) {
                            lines.add(line.substring(0, space));
                            line.delete(0, space + 1);
                        } else {
                            lines.add(line.toString());
                            line.setLength(0);
                        }
                    }
                    line.append(c);
                }
                if (line.length() > 0) {
                    lines.add(line.toString());
                }
            }
            return lines;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.prepare(g);
            g2.setFont(font);
            g2.setColor(color);
            FontMetrics fm = g2.getFontMetrics();
            int y = fm.getAscent();
            for (String line : wrap()) {
                g2.drawString(line, 0, y);
                y += fm.getHeight();
            }
            g2.dispose();
        }
    }

    public static final class RoundField extends javax.swing.JTextField {
        public RoundField(String text) {
            super(text);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
            setFont(Theme.regular(14));
            setForeground(Theme.INK);
            setCaretColor(Theme.INK);
            setSelectionColor(Theme.LAVENDER_SOFT);
            setPreferredSize(new Dimension(190, 42));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.prepare(g);
            Theme.fillRound(g2, 0, 0, getWidth(), getHeight(), 14, new Color(0xF7F3FB));
            g2.setColor(new Color(0xE9E1F2));
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            if (hasFocus()) {
                g2.setColor(Theme.alpha(Theme.MINT_DARK, 120));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 14, 14);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static final class CuteScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(0xD8CFE6);
            trackColor = new Color(0, 0, 0, 0);
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private JButton zeroButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            button.setMinimumSize(new Dimension(0, 0));
            button.setMaximumSize(new Dimension(0, 0));
            button.setBorder(BorderFactory.createEmptyBorder());
            return button;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            // 透明轨道
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
            if (bounds.height <= 0 || bounds.width <= 0) {
                return;
            }
            Graphics2D g2 = Theme.prepare(g);
            g2.setColor(isDragging ? new Color(0xBDB1D2) : new Color(0xD8CFE6));
            g2.fillRoundRect(bounds.x + 2, bounds.y + 2, Math.max(4, bounds.width - 4),
                    Math.max(12, bounds.height - 4), 8, 8);
            g2.dispose();
        }
    }

    public static final class Toast extends JComponent {
        private String message = "";
        private float alpha = 1f;
        private long shownAt;
        private final Timer timer;

        public Toast() {
            setOpaque(false);
            setVisible(false);
            timer = new Timer(30, e -> {
                long elapsed = System.currentTimeMillis() - shownAt;
                if (elapsed > 1700) {
                    alpha -= 0.07f;
                    if (alpha <= 0f) {
                        alpha = 0f;
                        setVisible(false);
                        ((Timer) e.getSource()).stop();
                    }
                }
                repaint();
            });
        }

        public void show(String text) {
            this.message = text;
            this.alpha = 1f;
            this.shownAt = System.currentTimeMillis();
            setVisible(true);
            timer.stop();
            timer.start();
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(Theme.bold(13));
            return new Dimension(fm.stringWidth(message) + 46, 42);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.prepare(g);
            g2.setComposite(java.awt.AlphaComposite.getInstance(
                    java.awt.AlphaComposite.SRC_OVER, Math.max(0f, alpha)));
            int w = getWidth();
            int h = getHeight();
            Theme.fillRound(g2, 0, 0, w, h - 3, h, new Color(0x4C4459));
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(15, h / 2, 19, h / 2 + 4);
            g2.drawLine(19, h / 2 + 4, 27, h / 2 - 4);
            g2.setFont(Theme.bold(13));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(message, 36, (h - 3 - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    public static JScrollPane scroll(JComponent view) {
        JScrollPane pane = new JScrollPane(view);
        pane.setOpaque(false);
        pane.getViewport().setOpaque(false);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        JScrollBar bar = pane.getVerticalScrollBar();
        bar.setOpaque(false);
        bar.setUnitIncrement(18);
        bar.setPreferredSize(new Dimension(10, 0));
        bar.setUI(new CuteScrollBarUI());
        pane.getViewport().setScrollMode(javax.swing.JViewport.BLIT_SCROLL_MODE);
        return pane;
    }

    public static JPanel transparentBox(int axis) {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, axis));
        panel.setOpaque(false);
        return panel;
    }

    public static void pad(JComponent component, int top, int left, int bottom, int right) {
        component.setBorder(BorderFactory.createEmptyBorder(top, left, bottom, right));
    }

    public static void gap(JPanel box, int height) {
        box.add(javax.swing.Box.createVerticalStrut(height));
    }

    public static Insets insets(int top, int left, int bottom, int right) {
        return new Insets(top, left, bottom, right);
    }
}
