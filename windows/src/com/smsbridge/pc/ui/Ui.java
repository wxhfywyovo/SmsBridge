package com.smsbridge.pc.ui;

import com.smsbridge.pc.AutoStart;
import com.smsbridge.pc.BridgeService;
import com.smsbridge.pc.ClipboardService;
import com.smsbridge.pc.Config;
import com.smsbridge.pc.HistoryStore;
import com.smsbridge.pc.Main;
import com.smsbridge.pc.NetUtil;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.JWindow;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/** 圆润可爱风主界面。 */
public final class Ui implements BridgeService.Listener {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final JFrame frame;
    private final BridgeService service;
    private final Config config;
    private final String pcName;
    private final ClipboardService clipboard = new ClipboardService();

    private final Mascot mascot = new Mascot();
    private final Components.Pill statusPill =
            new Components.Pill("等待手机连接", new Color(0xB9A7FF), Theme.LAVENDER_SOFT);
    private final Components.PinDisplay pinDisplay = new Components.PinDisplay();
    private final Components.CodeDisplay latestCode = new Components.CodeDisplay();
    private final Components.Toast toast = new Components.Toast();
    private final Components.ToggleSwitch autoStartToggle = new Components.ToggleSwitch(false);
    private final Components.ToggleSwitch fullMessageToggle = new Components.ToggleSwitch(false);

    private JLabel statusTitle;
    private JLabel statusSub;
    private JLabel addressLabel;
    private JLabel deviceCount;
    private JLabel historyCount;
    private JLabel latestMeta;
    private JLabel latestHint;
    private JPanel deviceList;
    private JPanel historyList;
    private JScrollPane historyScroll;
    private Components.SoftButton copyButton;
    private Components.SoftButton fullCopyButton;
    private Components.SoftButton retryButton;

    private final Deque<String> logs = new ArrayDeque<>();
    private final List<Config.Device> demoDevices = new ArrayList<>();
    private final List<HistoryStore.Entry> demoEntries = new ArrayList<>();
    private TrayIcon trayIcon;
    private JWindow trayMenuAnchor;
    private JPopupMenu trayMenu;
    private long lastTrayMenuAt;
    private boolean online;
    private boolean serverRunning = true;
    private boolean emptyHistoryShown;
    private boolean forceExit;
    private String currentClip = "";
    private String currentMessageBody = "";
    private long lastMessageAt;

    public Ui(BridgeService service, Config config) {
        this.service = service;
        this.config = config;
        this.pcName = service != null ? service.pcName() : computerName();

        frame = new JFrame("短信小桥 SmsBridge");
        frame.setUndecorated(true);
        try {
            frame.setBackground(new Color(0, 0, 0, 0));
        } catch (RuntimeException e) {
            frame.setBackground(Theme.BG_TOP);
        }
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(1200, 820);
        frame.setMinimumSize(new Dimension(1080, 750));
        frame.setContentPane(buildRoot());
        frame.setLocationRelativeTo(null);
        frame.setIconImage(createIcon(128));
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestClose();
            }
        });
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                positionToast();
            }
        });
        frame.getLayeredPane().add(toast, JLayeredPane.POPUP_LAYER);
        installTray();
        updatePin();
        updateAddress();
        setOnline(false, "");
        updateServerState(service == null || service.isRunning(),
                service == null ? "" : service.lastError());
        autoStartToggle.setOn(AutoStart.isEnabled());
        fullMessageToggle.setOn(config.copyMode() == Config.CopyMode.MESSAGE);
        refreshDevices();
        refreshHistory();
        if (!config.saveError().isEmpty()) {
            showToast(config.saveError());
        }
    }

    private static String computerName() {
        String name = System.getenv("COMPUTERNAME");
        return name == null || name.isBlank() ? "我的电脑" : name;
    }

    public void showWindow() {
        if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) {
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        }
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
    }

    public void startTrayOnly() {
        frame.setVisible(false);
    }

    public JFrame frame() {
        return frame;
    }

    public void capture(Path target) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame.setVisible(true);
            frame.toFront();
        });
        Thread.sleep(1400);
        int width = frame.getWidth();
        int height = frame.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D background = image.createGraphics();
        background.setPaint(new GradientPaint(0, 0, new Color(0xF7F2FA),
                width, height, new Color(0xEAF5FF)));
        background.fillRect(0, 0, width, height);
        background.dispose();
        SwingUtilities.invokeAndWait(() -> {
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            frame.paintAll(g);
            g.dispose();
        });
        ImageIO.write(image, "png", target.toFile());
    }

    // ---------------------------------------------------------------- layout

    private JPanel buildRoot() {
        JPanel root = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.prepare(g);
                int w = getWidth();
                int h = getHeight();
                for (int i = 13; i >= 1; i--) {
                    g2.setColor(new Color(Theme.SHADOW.getRed(), Theme.SHADOW.getGreen(),
                            Theme.SHADOW.getBlue(), 3 + (13 - i) / 3));
                    g2.fill(new RoundRectangle2D.Double(i, i + 2, w - 2 * i, h - 2 * i,
                            30 + i, 30 + i));
                }
                g2.setPaint(new GradientPaint(0, 0, Theme.BG_TOP, w, h, Theme.BG_BOTTOM));
                g2.fill(new RoundRectangle2D.Double(14, 14, w - 28, h - 28, 34, 34));
                g2.setColor(Theme.alpha(Theme.LAVENDER, 30));
                g2.fillOval(w - 300, 10, 250, 250);
                g2.setColor(Theme.alpha(Theme.PEACH, 26));
                g2.fillOval(20, h - 260, 250, 250);
                g2.setColor(Theme.alpha(Theme.MINT, 24));
                g2.fillOval(w - 190, h - 170, 160, 160);
                g2.dispose();
            }
        };
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(14, 16, 16, 16));
        root.add(buildTitleBar(), BorderLayout.NORTH);
        root.add(buildContent(), BorderLayout.CENTER);
        return root;
    }

    private JPanel buildTitleBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(2, 10, 4, 8));

        Mascot logo = new Mascot();
        logo.setPreferredSize(new Dimension(42, 48));
        logo.setMood(false, true);

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = label("短信小桥", Theme.bold(19), Theme.INK);
        JLabel subtitle = label("SmsBridge  ·  手机短信，一秒到电脑", Theme.regular(11), Theme.MUTED);
        titles.add(title);
        titles.add(subtitle);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.add(logo);
        left.add(Box.createHorizontalStrut(6));
        left.add(titles);

        Components.SoftButton minimize = new Components.SoftButton("—", new Color(0, 0, 0, 0),
                Theme.MUTED).ghost(null);
        minimize.setPreferredSize(new Dimension(34, 30));
        minimize.setRadius(12);
        minimize.setToolTipText("最小化");
        minimize.addActionListener(e -> frame.setState(Frame.ICONIFIED));

        Components.SoftButton close = new Components.SoftButton("×", new Color(0, 0, 0, 0),
                Theme.MUTED).ghost(null);
        close.setPreferredSize(new Dimension(34, 30));
        close.setRadius(12);
        close.setToolTipText("关闭");
        close.addActionListener(e -> requestClose());

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(statusPill);
        right.add(Box.createHorizontalStrut(10));
        right.add(minimize);
        right.add(Box.createHorizontalStrut(2));
        right.add(close);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        makeDraggable(bar);
        makeDraggable(logo);
        makeDraggable(title);
        makeDraggable(subtitle);
        return bar;
    }

    private JPanel buildContent() {
        JPanel content = new JPanel(new GridBagLayout());
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(4, 20, 6, 20));

        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = 0;
        lc.weightx = 0;
        lc.weighty = 1;
        lc.fill = GridBagConstraints.BOTH;
        lc.insets = new Insets(0, 0, 0, 18);

        GridBagConstraints rc = new GridBagConstraints();
        rc.gridx = 1;
        rc.gridy = 0;
        rc.weightx = 1;
        rc.weighty = 1;
        rc.fill = GridBagConstraints.BOTH;

        content.add(buildLeft(), lc);
        content.add(buildRight(), rc);
        return content;
    }

    private JPanel buildLeft() {
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setPreferredSize(new Dimension(380, 0));
        left.setMaximumSize(new Dimension(380, Integer.MAX_VALUE));

        Components.Card connection = new Components.Card(new BorderLayout(0, 8));
        connection.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel head = new JPanel(new BorderLayout(12, 0));
        head.setOpaque(false);
        mascot.setPreferredSize(new Dimension(108, 116));
        head.add(mascot, BorderLayout.WEST);

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        statusTitle = label("等待手机连接", Theme.bold(17), Theme.INK);
        statusSub = htmlLabel("手机和电脑连同一个 Wi-Fi<br>短信会自动复制到剪贴板", 11.5f, Theme.MUTED, 218);
        JLabel pc = label("电脑 · " + pcName, Theme.regular(10.5f), Theme.MUTED);
        addressLabel = label("", Theme.bold(10.5f), Theme.MINT_DARK);
        pc.setBorder(new EmptyBorder(5, 0, 1, 0));
        info.add(statusTitle);
        info.add(Box.createVerticalStrut(3));
        info.add(statusSub);
        info.add(pc);
        info.add(addressLabel);
        head.add(info, BorderLayout.CENTER);
        connection.add(head, BorderLayout.NORTH);

        JPanel pinBlock = new JPanel(new GridBagLayout());
        pinBlock.setOpaque(false);
        GridBagConstraints pgc = new GridBagConstraints();
        pgc.gridx = 0;
        pgc.weightx = 1;
        pgc.anchor = GridBagConstraints.WEST;
        pgc.fill = GridBagConstraints.HORIZONTAL;

        JPanel pinHead = new JPanel(new BorderLayout());
        pinHead.setOpaque(false);
        pinHead.add(label("配对码", Theme.bold(12.5f), Theme.INK), BorderLayout.WEST);
        JLabel pinTip = label("配对一次，永久自动连接", Theme.regular(10), Theme.MUTED);
        pinHead.add(pinTip, BorderLayout.EAST);
        pgc.gridy = 0;
        pinBlock.add(pinHead, pgc);

        pinDisplay.setAlignmentX(Component.LEFT_ALIGNMENT);
        pinDisplay.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        pinDisplay.setToolTipText("点击复制配对码");
        pinDisplay.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                copyPin();
            }
        });
        pgc.gridy = 1;
        pgc.fill = GridBagConstraints.NONE;
        pgc.insets = new Insets(7, 0, 10, 0);
        pinBlock.add(pinDisplay, pgc);

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        Components.SoftButton regenerate = new Components.SoftButton("换一个",
                new Color(0, 0, 0, 0), Theme.MUTED).ghost(Theme.LINE);
        regenerate.setPreferredSize(new Dimension(84, 34));
        regenerate.setRadius(13);
        regenerate.addActionListener(e -> regeneratePin());

        Components.SoftButton copyAddress = new Components.SoftButton("复制配对信息",
                Theme.MINT_SOFT, Theme.MINT_DARK);
        copyAddress.setPreferredSize(new Dimension(118, 34));
        copyAddress.setRadius(13);
        copyAddress.addActionListener(e -> copyPairingInfo());

        retryButton = new Components.SoftButton("重试启动", Theme.PEACH_SOFT, Theme.PEACH_DARK);
        retryButton.setPreferredSize(new Dimension(98, 34));
        retryButton.setRadius(13);
        retryButton.setVisible(false);
        retryButton.addActionListener(e -> {
            if (service != null) {
                service.restart();
            }
        });

        buttons.add(regenerate);
        buttons.add(copyAddress);
        buttons.add(retryButton);
        pgc.gridy = 2;
        pgc.fill = GridBagConstraints.HORIZONTAL;
        pgc.insets = new Insets(0, 0, 8, 0);
        pinBlock.add(buttons, pgc);

        JLabel hint = new JLabel("<html>点「复制配对信息」，手机 App 会自动读取 IP 和配对码。<br>"
                + "之后就算离开电脑、电脑重启，也会自动连回来。</html>");
        hint.setFont(Theme.regular(10.3f));
        hint.setForeground(Theme.MUTED);
        pgc.gridy = 3;
        pgc.insets = new Insets(0, 0, 0, 0);
        pinBlock.add(hint, pgc);
        connection.add(pinBlock, BorderLayout.CENTER);
        left.add(connection);
        left.add(Box.createVerticalStrut(12));

        Components.Card devices = new Components.Card(new BorderLayout(0, 8));
        devices.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel deviceHead = new JPanel(new BorderLayout());
        deviceHead.setOpaque(false);
        deviceHead.add(label("已配对设备", Theme.bold(12.5f), Theme.INK), BorderLayout.WEST);
        deviceCount = label("0 台", Theme.bold(10.5f), Theme.MINT_DARK);
        deviceHead.add(deviceCount, BorderLayout.EAST);
        devices.add(deviceHead, BorderLayout.NORTH);
        deviceList = new JPanel();
        deviceList.setOpaque(false);
        deviceList.setLayout(new BoxLayout(deviceList, BoxLayout.Y_AXIS));
        JScrollPane deviceScroll = Components.scroll(deviceList);
        deviceScroll.setPreferredSize(new Dimension(330, 108));
        devices.add(deviceScroll, BorderLayout.CENTER);
        left.add(devices);
        left.add(Box.createVerticalStrut(12));

        Components.Card prefs = new Components.Card(new BorderLayout(0, 6));
        prefs.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel prefRows = new JPanel();
        prefRows.setOpaque(false);
        prefRows.setLayout(new BoxLayout(prefRows, BoxLayout.Y_AXIS));
        prefRows.add(prefRow("开机自动启动", "电脑重启后自动恢复服务", autoStartToggle));
        autoStartToggle.setListener(value -> {
            boolean ok = AutoStart.set(value);
            if (!ok) {
                autoStartToggle.setOn(!value);
                showToast("设置开机启动失败，请检查权限");
            } else {
                config.setAutoStart(value);
                showToast(value ? "已开启开机自动启动" : "已关闭开机自动启动");
            }
        });
        prefRows.add(Box.createVerticalStrut(6));
        prefRows.add(prefRow("复制整条短信", "关闭时只复制 4-8 位验证码", fullMessageToggle));
        fullMessageToggle.setListener(value -> {
            config.setCopyMode(value ? Config.CopyMode.MESSAGE : Config.CopyMode.CODE);
            if (service != null) {
                service.setCopyMode(value ? Config.CopyMode.MESSAGE : Config.CopyMode.CODE);
            }
            showToast(value ? "之后会复制整条短信" : "之后只复制验证码");
        });
        prefs.add(prefRows, BorderLayout.NORTH);

        JPanel prefButtons = new JPanel();
        prefButtons.setOpaque(false);
        prefButtons.setLayout(new BoxLayout(prefButtons, BoxLayout.X_AXIS));
        Components.SoftButton settings = new Components.SoftButton("端口设置",
                new Color(0, 0, 0, 0), Theme.MUTED).ghost(Theme.LINE);
        settings.setPreferredSize(new Dimension(86, 30));
        settings.setRadius(12);
        settings.addActionListener(e -> openSettings());
        Components.SoftButton folder = new Components.SoftButton("打开文件夹",
                new Color(0, 0, 0, 0), Theme.MUTED).ghost(Theme.LINE);
        folder.setPreferredSize(new Dimension(94, 30));
        folder.setRadius(12);
        folder.addActionListener(e -> openConfigFolder());
        prefButtons.add(settings);
        prefButtons.add(Box.createHorizontalStrut(8));
        prefButtons.add(folder);
        prefs.add(prefButtons, BorderLayout.SOUTH);
        left.add(prefs);
        left.add(Box.createVerticalGlue());
        return left;
    }

    private JPanel prefRow(String title, String subtitle, Components.ToggleSwitch toggle) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(label(title, Theme.bold(12), Theme.INK));
        text.add(label(subtitle, Theme.regular(9.8f), Theme.MUTED));
        row.add(text, BorderLayout.CENTER);
        row.add(toggle, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        return row;
    }

    private JPanel buildRight() {
        JPanel right = new JPanel(new BorderLayout(0, 14));
        right.setOpaque(false);

        Components.Card latest = new Components.Card(new BorderLayout(14, 0));
        JPanel latestLeft = new JPanel();
        latestLeft.setOpaque(false);
        latestLeft.setLayout(new BoxLayout(latestLeft, BoxLayout.Y_AXIS));
        latestLeft.add(label("最新验证码", Theme.bold(11), Theme.MUTED));
        latestLeft.add(Box.createVerticalStrut(2));
        latestLeft.add(latestCode);
        latestMeta = label("等待手机短信…", Theme.regular(10.8f), Theme.MUTED);
        latestLeft.add(latestMeta);
        latest.add(latestLeft, BorderLayout.CENTER);

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        copyButton = new Components.SoftButton("复制", Theme.MINT, Color.WHITE);
        copyButton.setPreferredSize(new Dimension(104, 44));
        copyButton.setRadius(16);
        copyButton.setAlignmentX(Component.RIGHT_ALIGNMENT);
        copyButton.addActionListener(e -> copyLatest());

        fullCopyButton = new Components.SoftButton("复制完整短信",
                Theme.MINT_SOFT, Theme.MINT_DARK);
        fullCopyButton.setPreferredSize(new Dimension(104, 32));
        fullCopyButton.setRadius(13);
        fullCopyButton.setAlignmentX(Component.RIGHT_ALIGNMENT);
        fullCopyButton.addActionListener(e -> copyFullMessage());

        latestHint = label("收到短信会自动复制", Theme.regular(10), Theme.MUTED);
        latestHint.setAlignmentX(Component.RIGHT_ALIGNMENT);
        actions.add(copyButton);
        actions.add(Box.createVerticalStrut(6));
        actions.add(fullCopyButton);
        actions.add(Box.createVerticalStrut(6));
        actions.add(latestHint);
        latest.add(actions, BorderLayout.EAST);
        right.add(latest, BorderLayout.NORTH);

        Components.Card history = new Components.Card(new BorderLayout(0, 10));
        JPanel historyHead = new JPanel(new BorderLayout());
        historyHead.setOpaque(false);
        JPanel titleRow = new JPanel();
        titleRow.setOpaque(false);
        titleRow.setLayout(new BoxLayout(titleRow, BoxLayout.X_AXIS));
        titleRow.add(label("同步记录", Theme.bold(15), Theme.INK));
        titleRow.add(Box.createHorizontalStrut(8));
        historyCount = label("0 条", Theme.regular(10.5f), Theme.MUTED);
        titleRow.add(historyCount);
        historyHead.add(titleRow, BorderLayout.WEST);

        Components.SoftButton clear = new Components.SoftButton("清空",
                new Color(0, 0, 0, 0), Theme.MUTED).ghost(Theme.LINE);
        clear.setPreferredSize(new Dimension(62, 28));
        clear.setRadius(11);
        clear.addActionListener(e -> clearHistory());
        historyHead.add(clear, BorderLayout.EAST);

        history.add(historyHead, BorderLayout.NORTH);
        historyList = new JPanel();
        historyList.setOpaque(false);
        historyList.setLayout(new BoxLayout(historyList, BoxLayout.Y_AXIS));
        historyScroll = Components.scroll(historyList);
        history.add(historyScroll, BorderLayout.CENTER);
        right.add(history, BorderLayout.CENTER);
        return right;
    }

    // ---------------------------------------------------------------- widgets

    private JLabel label(String text, java.awt.Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    private JLabel htmlLabel(String html, float size, Color color, int width) {
        JLabel label = new JLabel("<html><div style='width:" + width + "px'>"
                + html + "</div></html>");
        label.setFont(Theme.regular(size));
        label.setForeground(color);
        return label;
    }

    private JComponent chip(String text) {
        Components.RoundedPanel panel = new Components.RoundedPanel(
                new BorderLayout(), Theme.MINT_SOFT, 11, false);
        panel.setBorder(new EmptyBorder(3, 9, 3, 9));
        panel.add(label(text, Theme.bold(10.2f), Theme.MINT_DARK), BorderLayout.CENTER);
        return panel;
    }

    private JComponent deviceRow(Config.Device device) {
        Components.RoundedPanel row = new Components.RoundedPanel(
                new BorderLayout(10, 0), new Color(0xF9F5FC), 15, false);
        row.setBorder(new EmptyBorder(7, 9, 7, 9));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

        Components.RoundedPanel avatar = new Components.RoundedPanel(
                new BorderLayout(), Theme.MINT_SOFT, 15, false);
        avatar.setPreferredSize(new Dimension(30, 30));
        String initial = device.name().isEmpty() ? "手" : device.name().substring(0, 1);
        JLabel initialLabel = label(initial, Theme.bold(12), Theme.MINT_DARK);
        initialLabel.setHorizontalAlignment(SwingConstants.CENTER);
        avatar.add(initialLabel, BorderLayout.CENTER);
        row.add(avatar, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(label(device.name().isEmpty() ? "手机" : device.name(), Theme.bold(11.5f), Theme.INK));
        text.add(label("上次连接 " + formatLastSeen(device.lastSeen()), Theme.regular(9.5f), Theme.MUTED));
        row.add(text, BorderLayout.CENTER);

        Components.SoftButton remove = new Components.SoftButton("移除",
                new Color(0, 0, 0, 0), Theme.MUTED).ghost(null);
        remove.setPreferredSize(new Dimension(52, 26));
        remove.setRadius(10);
        remove.addActionListener(e -> {
            if (service != null) {
                service.unpair(device.id());
            } else {
                demoDevices.removeIf(d -> d.id().equals(device.id()));
                refreshDevices();
            }
            showToast("已移除该设备");
        });
        row.add(remove, BorderLayout.EAST);
        return row;
    }

    private JComponent historyRow(HistoryStore.Entry entry) {
        Components.RoundedPanel row = new Components.RoundedPanel(
                new BorderLayout(10, 5), Theme.CARD_SOFT, 18, false);
        row.setBorder(new EmptyBorder(10, 12, 10, 12));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        JPanel who = new JPanel();
        who.setOpaque(false);
        who.setLayout(new BoxLayout(who, BoxLayout.X_AXIS));
        who.add(label(entry.from().isEmpty() ? "未知号码" : entry.from(),
                Theme.bold(11.8f), Theme.INK));
        who.add(Box.createHorizontalStrut(7));
        who.add(label(formatTime(entry.ts()), Theme.regular(9.8f), Theme.MUTED));
        top.add(who, BorderLayout.WEST);
        if (entry.code() != null) {
            JComponent codeChip = chip("验证码 " + entry.code());
            codeChip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            codeChip.setToolTipText("只复制验证码");
            codeChip.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    clipboard.copy(entry.code());
                    showToast("已复制验证码 " + entry.code());
                    e.consume();
                }
            });
            top.add(codeChip, BorderLayout.EAST);
        }
        row.add(top, BorderLayout.NORTH);

        Components.WrappedText body = new Components.WrappedText(entry.body(),
                Theme.regular(11.2f), Theme.alpha(Theme.INK, 205), 500);
        row.add(body, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                Math.max(62, row.getPreferredSize().height)));

        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                clipboard.copy(entry.body());
                showToast("已复制完整短信");
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                row.setToolTipText("点击复制完整短信；点右侧验证码可只复制验证码");
            }
        });
        row.setToolTipText("点击复制完整短信；点右侧验证码可只复制验证码");
        return row;
    }

    private JComponent emptyState() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(52, 0, 0, 0));
        JLabel big = label("还没有同步记录", Theme.bold(15), Theme.MUTED);
        big.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel small = htmlLabel("配对成功后，手机收到的短信会自动出现在这里，<br>"
                + "验证码也会悄悄躺进电脑剪贴板～", 11.3f, Theme.MUTED, 420);
        small.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(big);
        panel.add(Box.createVerticalStrut(6));
        panel.add(small);
        return panel;
    }

    // ---------------------------------------------------------------- data

    private void updatePin() {
        pinDisplay.setPin(config.pin());
    }

    private void regeneratePin() {
        if (service != null) {
            service.regeneratePin();
        } else {
            config.regeneratePin();
        }
        updatePin();
        showToast("配对码已更新，已配对设备不受影响");
    }

    private void updateAddress() {
        if (addressLabel != null) {
            addressLabel.setText(NetUtil.primaryIp() + " : " + config.port());
        }
    }

    private void copyPairingInfo() {
        String payload;
        if (service != null) {
            payload = service.pairingPayload();
        } else {
            payload = "SMSBRIDGE-PAIR|v=1|host=192.168.1.5|port=" + config.port()
                    + "|pin=" + config.pin() + "|name=演示电脑";
        }
        clipboard.copy(payload);
        showToast("配对信息已复制，手机打开 App 会自动读取");
    }

    private void copyPin() {
        String pin = config.pin();
        clipboard.copy(pin);
        showToast("配对码 " + pin + " 已复制");
    }

    private void refreshDevices() {
        if (deviceList == null) {
            return;
        }
        deviceList.removeAll();
        List<Config.Device> devices = service != null ? service.devices() : demoDevices;
        deviceCount.setText(devices.size() + " 台");
        if (devices.isEmpty()) {
            JLabel empty = label("还没有配对设备～", Theme.regular(10.8f), Theme.MUTED);
            empty.setBorder(new EmptyBorder(10, 6, 8, 6));
            deviceList.add(empty);
        } else {
            for (Config.Device device : devices) {
                deviceList.add(deviceRow(device));
                deviceList.add(Box.createVerticalStrut(5));
            }
        }
        deviceList.revalidate();
        deviceList.repaint();
    }

    private void refreshHistory() {
        if (historyList == null) {
            return;
        }
        historyList.removeAll();
        List<HistoryStore.Entry> entries = service != null
                ? service.history().recent(80) : new ArrayList<>(demoEntries);
        emptyHistoryShown = entries.isEmpty();
        if (entries.isEmpty()) {
            historyList.add(emptyState());
        } else {
            for (int i = entries.size() - 1; i >= 0; i--) {
                historyList.add(historyRow(entries.get(i)));
                historyList.add(Box.createVerticalStrut(7));
            }
        }
        historyCount.setText(entries.size() + " 条");
        setLatest(entries.isEmpty() ? null : entries.get(entries.size() - 1));
        historyList.revalidate();
        historyList.repaint();
    }

    private void prependHistory(HistoryStore.Entry entry) {
        if (emptyHistoryShown) {
            historyList.removeAll();
            emptyHistoryShown = false;
        }
        JComponent row = historyRow(entry);
        historyList.add(row, 0);
        historyList.add(Box.createVerticalStrut(7), 1);
        while (historyList.getComponentCount() > 170) {
            historyList.remove(historyList.getComponentCount() - 1);
        }
        int count = service != null ? service.history().size() : demoEntries.size();
        historyCount.setText(count + " 条");
        setLatest(entry);
        historyList.revalidate();
        historyList.repaint();
        SwingUtilities.invokeLater(() -> historyScroll.getVerticalScrollBar().setValue(0));
    }

    private void setLatest(HistoryStore.Entry entry) {
        if (entry == null) {
            latestCode.setCode(null);
            latestMeta.setText("等待手机短信…");
            latestHint.setText("收到短信会自动复制");
            latestHint.setForeground(Theme.MUTED);
            copyButton.setText("复制");
            fullCopyButton.setText("复制完整短信");
            fullCopyButton.setEnabled(false);
            currentClip = "";
            currentMessageBody = "";
            return;
        }
        currentClip = entry.code() != null ? entry.code() : entry.body();
        currentMessageBody = entry.body();
        fullCopyButton.setEnabled(true);
        latestCode.setCode(entry.code());
        latestMeta.setText("来自 " + (entry.from().isEmpty() ? "未知号码" : entry.from())
                + " · " + formatTime(entry.ts()));
        copyButton.setText(entry.code() != null ? "复制验证码" : "复制短信");
        fullCopyButton.setText("复制完整短信");
        if (entry.copied()) {
            latestHint.setText(entry.code() != null ? "已自动复制验证码" : "已复制整条短信");
            latestHint.setForeground(Theme.MINT_DARK);
        } else {
            latestHint.setText("自动复制失败，点右侧按钮");
            latestHint.setForeground(Theme.DANGER);
        }
    }

    private void copyLatest() {
        if (currentClip.isEmpty()) {
            showToast("还没有可复制的短信");
            return;
        }
        clipboard.copy(currentClip);
        showToast("已复制 " + (currentClip.length() > 20 ? "短信内容" : currentClip));
    }

    private void copyFullMessage() {
        if (currentMessageBody.isEmpty()) {
            showToast("还没有可复制的短信");
            return;
        }
        clipboard.copy(currentMessageBody);
        showToast("已复制完整短信");
    }

    private void clearHistory() {
        if (service != null) {
            service.clearHistory();
        } else {
            demoEntries.clear();
        }
        refreshHistory();
        showToast("记录已清空");
    }

    private String formatTime(long ts) {
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault());
        LocalDate today = LocalDate.now();
        if (time.toLocalDate().equals(today)) {
            return "今天 " + time.format(TIME);
        }
        if (time.toLocalDate().equals(today.minusDays(1))) {
            return "昨天 " + time.format(TIME);
        }
        return time.format(DATE_TIME);
    }

    private String formatLastSeen(long ts) {
        if (ts <= 0) {
            return "从未连接";
        }
        long delta = System.currentTimeMillis() - ts;
        if (delta < 60_000) {
            return "刚刚";
        }
        if (delta < 3_600_000) {
            return (delta / 60_000) + " 分钟前";
        }
        return formatTime(ts);
    }

    // ---------------------------------------------------------------- states

    private void setOnline(boolean nowOnline, String device) {
        this.online = nowOnline;
        mascot.setMood(nowOnline, serverRunning);
        if (!serverRunning) {
            return;
        }
        if (nowOnline) {
            statusPill.setState("已连接 · " + device, Theme.MINT_DARK, Theme.MINT_SOFT);
            statusTitle.setText("手机已连接");
            statusSub.setText("<html><div style='width:218px'>短信会实时同步到电脑，"
                    + "验证码自动进剪贴板。</div></html>");
            statusTitle.setForeground(Theme.INK);
            updateTrayTooltip();
        } else {
            statusPill.setState("等待手机连接", new Color(0xB9A7FF), Theme.LAVENDER_SOFT);
            statusTitle.setText("等待手机连接");
            statusSub.setText("<html><div style='width:218px'>手机和电脑连同一个 Wi-Fi，"
                    + "在 App 里输入配对码即可。</div></html>");
            statusTitle.setForeground(Theme.INK);
            updateTrayTooltip();
        }
    }

    private void updateServerState(boolean ok, String error) {
        this.serverRunning = ok;
        mascot.setMood(online, ok);
        updateAddress();
        if (!ok) {
            statusPill.setState("服务未启动", Theme.DANGER, Theme.PEACH_SOFT);
            statusTitle.setText("服务没有启动");
            statusTitle.setForeground(Theme.DANGER);
            statusSub.setText("<html><div style='width:218px'>"
                    + Theme.htmlEscape(error == null || error.isBlank()
                    ? "端口被占用或被防火墙拦截，换个端口再试。" : error)
                    + "</div></html>");
            if (retryButton != null) {
                retryButton.setVisible(true);
            }
            updateTrayTooltip();
        } else {
            if (retryButton != null) {
                retryButton.setVisible(false);
            }
            setOnline(online, service != null ? service.connectedDevice() : "");
            updateTrayTooltip();
        }
    }

    private void updateTrayTooltip() {
        if (trayIcon == null) {
            return;
        }
        trayIcon.setToolTip("短信小桥 · " + (!serverRunning ? "服务未启动"
                : online ? "已连接 " + (service != null ? service.connectedDevice() : "手机")
                : "等待手机连接"));
    }

    // ---------------------------------------------------------------- listener

    @Override
    public void onServerStatus(boolean running, String error) {
        SwingUtilities.invokeLater(() -> {
            if (!running && error != null && !error.isBlank()) {
                setOnline(false, "");
                updateServerState(false, error);
                showToast(error);
            } else if (!running) {
                setOnline(false, "");
                updateServerState(false, "");
            } else {
                updateServerState(true, "");
            }
        });
    }

    @Override
    public void onConnectionChanged(boolean online, String deviceName) {
        SwingUtilities.invokeLater(() -> setOnline(online, deviceName));
    }

    @Override
    public void onMessage(HistoryStore.Entry entry) {
        SwingUtilities.invokeLater(() -> {
            prependHistory(entry);
            lastMessageAt = System.currentTimeMillis();
            String message = entry.code() != null ? "验证码 " + entry.code() + " 已复制"
                    : "新短信已复制";
            showToast(message);
            if (trayIcon != null && (!frame.isVisible() || !frame.isActive())) {
                try {
                    trayIcon.displayMessage(entry.code() != null ? "验证码 " + entry.code() : "新短信",
                            entry.from() + " · " + (entry.copied() ? "已复制到剪贴板" : "复制失败"),
                            TrayIcon.MessageType.INFO);
                } catch (RuntimeException ignored) {
                    // 通知失败忽略
                }
            }
        });
    }

    @Override
    public void onDevicesChanged() {
        SwingUtilities.invokeLater(this::refreshDevices);
    }

    @Override
    public void onLog(String line) {
        SwingUtilities.invokeLater(() -> {
            logs.addLast(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    + "  " + line);
            while (logs.size() > 60) {
                logs.removeFirst();
            }
        });
    }

    // ---------------------------------------------------------------- extras

    private void showToast(String message) {
        toast.show(message);
        positionToast();
        toast.repaint();
    }

    private void positionToast() {
        if (toast == null || frame == null) {
            return;
        }
        Dimension size = toast.getPreferredSize();
        int x = Math.max(20, (frame.getWidth() - size.width) / 2);
        int y = Math.max(20, frame.getHeight() - size.height - 30);
        toast.setBounds(x, y, size.width, size.height);
    }

    private void openConfigFolder() {
        try {
            File dir = config.dir().toFile();
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            } else {
                new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
            }
        } catch (Exception e) {
            showToast("打开文件夹失败：" + e.getMessage());
        }
    }

    private void openSettings() {
        JDialog dialog = new JDialog(frame, "设置", true);
        dialog.setUndecorated(true);
        dialog.setBackground(new Color(0, 0, 0, 0));
        Components.RoundedPanel panel = new Components.RoundedPanel(
                new BorderLayout(0, 12), Theme.CARD, 26, true);
        panel.setBorder(new EmptyBorder(22, 24, 22, 24));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(label("设置", Theme.bold(17), Theme.INK), BorderLayout.WEST);
        Components.SoftButton close = new Components.SoftButton("×",
                new Color(0, 0, 0, 0), Theme.MUTED).ghost(null);
        close.setPreferredSize(new Dimension(32, 28));
        close.setRadius(11);
        close.addActionListener(e -> dialog.dispose());
        header.add(close, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JPanel portRow = new JPanel(new BorderLayout(10, 0));
        portRow.setOpaque(false);
        JPanel portText = new JPanel();
        portText.setOpaque(false);
        portText.setLayout(new BoxLayout(portText, BoxLayout.Y_AXIS));
        portText.add(label("服务端口", Theme.bold(12), Theme.INK));
        portText.add(label("1024 - 65535，改完会自动重启服务", Theme.regular(9.8f), Theme.MUTED));
        portRow.add(portText, BorderLayout.CENTER);
        Components.RoundField portField = new Components.RoundField(
                Integer.toString(config.port()));
        portField.setPreferredSize(new Dimension(110, 38));
        portRow.add(portField, BorderLayout.EAST);
        form.add(portRow);
        form.add(Box.createVerticalStrut(12));

        JPanel trayRow = new JPanel(new BorderLayout(10, 0));
        trayRow.setOpaque(false);
        trayRow.add(label("关闭窗口时最小化到托盘", Theme.bold(12), Theme.INK),
                BorderLayout.CENTER);
        Components.ToggleSwitch trayToggle =
                new Components.ToggleSwitch(config.closeToTray());
        trayRow.add(trayToggle, BorderLayout.EAST);
        form.add(trayRow);
        form.add(Box.createVerticalStrut(14));

        form.add(label("最近事件", Theme.bold(12), Theme.INK));
        form.add(Box.createVerticalStrut(5));
        StringBuilder logText = new StringBuilder();
        for (String line : logs) {
            logText.append(line).append('\n');
        }
        JTextArea area = new JTextArea(logText.toString());
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Theme.regular(10.5f));
        area.setForeground(Theme.MUTED);
        area.setBackground(new Color(0xFAF6FD));
        area.setBorder(new EmptyBorder(8, 10, 8, 10));
        JScrollPane logScroll = new JScrollPane(area);
        logScroll.setBorder(BorderFactory.createLineBorder(new Color(0xEDE5F4), 1, true));
        logScroll.setPreferredSize(new Dimension(400, 120));
        form.add(logScroll);
        panel.add(form, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        JLabel version = label("SmsBridge " + Main.VERSION + " · 仅局域网", Theme.regular(9.8f),
                Theme.MUTED);
        footer.add(version, BorderLayout.WEST);
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        Components.SoftButton cancel = new Components.SoftButton("取消",
                new Color(0, 0, 0, 0), Theme.MUTED).ghost(Theme.LINE);
        cancel.setPreferredSize(new Dimension(72, 34));
        cancel.setRadius(13);
        cancel.addActionListener(e -> dialog.dispose());
        Components.SoftButton save = new Components.SoftButton("保存",
                Theme.MINT, Color.WHITE);
        save.setPreferredSize(new Dimension(80, 34));
        save.setRadius(13);
        save.addActionListener(e -> {
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                showToast("端口必须是数字");
                return;
            }
            if (port < 1024 || port > 65535) {
                showToast("端口范围是 1024 - 65535");
                return;
            }
            boolean changed = port != config.port();
            config.setPort(port);
            config.setCloseToTray(trayToggle.isOn());
            dialog.dispose();
            updateAddress();
            if (changed && service != null) {
                service.restart();
                showToast("端口已改为 " + port + "，服务重启中");
            } else {
                showToast("设置已保存");
            }
        });
        buttons.add(cancel);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(save);
        footer.add(buttons, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        dialog.setSize(470, 430);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void requestClose() {
        if (forceExit) {
            return;
        }
        if (config.closeToTray() && trayIcon != null) {
            frame.setVisible(false);
            try {
                trayIcon.displayMessage("短信小桥还在后台", "短信仍会继续同步，点托盘图标可以打开。",
                        TrayIcon.MessageType.INFO);
            } catch (RuntimeException ignored) {
                // 忽略
            }
            return;
        }
        exitApp();
    }

    public void exitApp() {
        forceExit = true;
        if (service != null) {
            service.stop();
        }
        if (trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }
        if (trayMenuAnchor != null) {
            trayMenuAnchor.dispose();
        }
        frame.dispose();
        System.exit(0);
    }

    private void installTray() {
        if (!SystemTray.isSupported() || GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            trayIcon = new TrayIcon(createTrayImage(), "短信小桥");
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> SwingUtilities.invokeLater(this::showWindow));
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    maybeShowTrayMenu(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    maybeShowTrayMenu(e);
                }
            });
            SystemTray.getSystemTray().add(trayIcon);
            updateTrayTooltip();
        } catch (Exception e) {
            trayIcon = null;
        }
    }

    private void maybeShowTrayMenu(MouseEvent e) {
        if (e == null) {
            return;
        }
        if (e.isPopupTrigger() || e.getButton() == MouseEvent.BUTTON3) {
            long now = System.currentTimeMillis();
            if (now - lastTrayMenuAt < 250) {
                return;
            }
            lastTrayMenuAt = now;
            SwingUtilities.invokeLater(this::showTrayMenu);
        }
    }

    private void showTrayMenu() {
        try {
            if (trayMenu != null && trayMenu.isVisible()) {
                trayMenu.setVisible(false);
                return;
            }
            if (trayMenuAnchor == null) {
                trayMenuAnchor = new JWindow();
                trayMenuAnchor.setSize(1, 1);
                trayMenuAnchor.setBackground(new Color(0, 0, 0, 0));
                trayMenuAnchor.setAlwaysOnTop(true);
                trayMenuAnchor.setFocusableWindowState(false);
                trayMenuAnchor.setAutoRequestFocus(false);
            }
            Point pointer = MouseInfo.getPointerInfo() == null
                    ? null : MouseInfo.getPointerInfo().getLocation();
            if (pointer == null) {
                return;
            }
            trayMenu = buildTrayMenu();
            trayMenu.setLightWeightPopupEnabled(false);
            Dimension size = trayMenu.getPreferredSize();
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            int x = Math.max(screen.x,
                    Math.min(pointer.x, screen.x + screen.width - size.width));
            int y = pointer.y - size.height;
            if (y < screen.y) {
                y = pointer.y;
            }
            y = Math.max(screen.y,
                    Math.min(y, screen.y + screen.height - size.height));
            trayMenuAnchor.setLocation(x, y);
            trayMenuAnchor.setVisible(true);
            trayMenuAnchor.toFront();
            trayMenu.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                    // 不需要处理
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    if (trayMenuAnchor != null) {
                        trayMenuAnchor.setVisible(false);
                    }
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                    if (trayMenuAnchor != null) {
                        trayMenuAnchor.setVisible(false);
                    }
                }
            });
            trayMenu.show(trayMenuAnchor, 0, 0);
        } catch (Exception ignored) {
            // 托盘菜单失败不影响主功能
        }
    }

    private JPopupMenu buildTrayMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(Theme.CARD);
        menu.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.LINE, 1, true),
                new EmptyBorder(6, 6, 6, 6)));
        menu.setFont(Theme.regular(13));

        JMenuItem show = trayItem("显示主界面");
        show.addActionListener(e -> showWindow());
        JMenuItem copy = trayItem("复制配对信息");
        copy.addActionListener(e -> copyPairingInfo());
        JMenuItem full = trayItem("复制最新完整短信");
        full.addActionListener(e -> copyFullMessage());
        JMenuItem exit = trayItem("退出");
        exit.addActionListener(e -> exitApp());

        menu.add(show);
        menu.add(copy);
        menu.add(full);
        menu.addSeparator();
        menu.add(exit);
        return menu;
    }

    private JMenuItem trayItem(String text) {
        JMenuItem item = new JMenuItem(text);
        item.setFont(Theme.regular(13));
        item.setForeground(Theme.INK);
        item.setBackground(Theme.CARD);
        item.setOpaque(true);
        item.setBorder(new EmptyBorder(8, 14, 8, 14));
        item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        item.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                item.setBackground(Theme.MINT_SOFT);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                item.setBackground(Theme.CARD);
            }
        });
        return item;
    }

    private Image createTrayImage() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = Theme.prepare(image.getGraphics());
        Theme.fillRound(g2, 10, 6, 44, 54, 17, Theme.MINT);
        g2.setColor(Color.WHITE);
        g2.fillRoundRect(17, 14, 30, 30, 12, 12);
        g2.setColor(Theme.INK);
        g2.fillOval(24, 24, 5, 6);
        g2.fillOval(35, 24, 5, 6);
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawArc(25, 28, 14, 10, 200, 140);
        g2.setColor(Theme.PEACH_DARK);
        g2.fillOval(43, 4, 12, 12);
        g2.setColor(Color.WHITE);
        g2.fillOval(46, 7, 6, 6);
        g2.dispose();
        return image;
    }

    private Image createIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = Theme.prepare(image.getGraphics());
        g2.setPaint(new GradientPaint(0, 0, Theme.LAVENDER, size, size, Theme.MINT));
        g2.fillRoundRect(0, 0, size, size, size / 3, size / 3);
        g2.setColor(Color.WHITE);
        g2.fillRoundRect(size / 5, size / 8, size * 3 / 5, size * 3 / 4,
                size / 4, size / 4);
        g2.setColor(Theme.INK);
        g2.fillOval(size * 5 / 12, size * 5 / 12, size / 12, size / 10);
        g2.fillOval(size * 7 / 12, size * 5 / 12, size / 12, size / 10);
        g2.setStroke(new BasicStroke(size / 32f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g2.drawArc(size * 5 / 12, size * 7 / 12, size / 6, size / 8, 200, 140);
        g2.dispose();
        return image;
    }

    private void makeDraggable(Component component) {
        MouseAdapter adapter = new MouseAdapter() {
            private Point start;

            @Override
            public void mousePressed(MouseEvent e) {
                start = e.getLocationOnScreen();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (start == null) {
                    return;
                }
                Point current = e.getLocationOnScreen();
                frame.setLocation(frame.getX() + current.x - start.x,
                        frame.getY() + current.y - start.y);
                start = current;
            }
        };
        component.addMouseListener(adapter);
        component.addMouseMotionListener(adapter);
    }

    /** 截图模式用的演示数据。 */
    public void demo() {
        demoDevices.clear();
        demoDevices.add(new Config.Device("demo-1", "Xiaomi 14", "", System.currentTimeMillis() - 120_000));
        demoDevices.add(new Config.Device("demo-2", "iPad Air", "", System.currentTimeMillis() - 86_400_000L));
        long now = System.currentTimeMillis();
        demoEntries.clear();
        demoEntries.add(new HistoryStore.Entry("demo-a", "中国银行",
                "【中国银行】您尾号 1234 的账户正在进行登录操作，验证码：482913，5 分钟内有效。请勿泄露给他人。",
                "482913", now - 240_000, true));
        demoEntries.add(new HistoryStore.Entry("demo-b", "美团",
                "【美团】您正在登录美团，验证码 306688，请于 5 分钟内输入。如非本人操作请忽略。",
                "306688", now - 120_000, true));
        demoEntries.add(new HistoryStore.Entry("demo-c", "GitHub",
                "[GitHub] Your verification code is 927461. It expires in 10 minutes.",
                "927461", now - 20_000, true));
        setOnline(true, "Xiaomi 14");
        refreshDevices();
        refreshHistory();
        showToast("演示数据");
    }
}
