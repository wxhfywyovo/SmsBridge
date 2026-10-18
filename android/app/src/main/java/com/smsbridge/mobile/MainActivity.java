package com.smsbridge.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.provider.Telephony;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 圆润可爱风的手机端主界面。 */
public final class MainActivity extends Activity implements SyncService.Listener {
    private static final int REQ_PERMISSIONS = 101;
    private static final String GITHUB_URL = "https://github.com/wxhfywyovo/SmsBridge";

    private Prefs prefs;
    private ExecutorService io;
    private MascotView mascot;
    private TextView statusPill;
    private TextView pcTitle;
    private TextView pcDetail;
    private TextView logLine;
    private TextView permissionText;
    private LinearLayout permissionRow;
    private TextView notificationAccessLine;
    private Button notificationAccessButton;
    private Button scopeButton;
    private EditText hostField;
    private EditText portField;
    private EditText pinField;
    private Button pairButton;
    private Button discoverButton;
    private Switch syncSwitch;
    private Switch bootSwitch;
    private Switch hideSwitch;
    private LinearLayout recentList;
    private boolean updatingSwitch;
    private boolean suppressAutoHide;

    private int ink;
    private int muted;
    private int mintDark;
    private int mintSoft;
    private int lavenderSoft;
    private int peachSoft;
    private int danger;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        io = Executors.newSingleThreadExecutor();
        ink = getColor(R.color.ink);
        muted = getColor(R.color.muted);
        mintDark = getColor(R.color.mint_dark);
        mintSoft = getColor(R.color.mint_soft);
        lavenderSoft = getColor(R.color.lavender_soft);
        peachSoft = getColor(R.color.peach_soft);
        danger = getColor(R.color.danger);

        Window window = getWindow();
        window.setStatusBarColor(getColor(R.color.bg_top));
        window.setNavigationBarColor(getColor(R.color.bg_top));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        setContentView(buildRoot());
        applyStatus(SyncService.lastStatus());
        refreshRecent();
        updateEventLine();
        updateNotificationAccessLine();
        updatePermissionRow();
        requestRuntimePermissions();
        if (prefs.enabled() && prefs.hasPairing()) {
            startAction(SyncService.ACTION_START);
            setSyncSwitch(true);
        }
        // 第一次打开必须看一遍免责声明。
        if (!prefs.disclaimerAccepted()) {
            showDisclaimer();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        suppressAutoHide = false;
        refreshRecent();
        updateEventLine();
        updateNotificationAccessLine();
        if (prefs.enabled()) {
            maybeRequestBatteryExemption();
        }
        // 还没配对时，电脑刚点过“复制配对信息”的话，打开 App 就自动读取并连接。
        if (!prefs.hasPairing()) {
            getWindow().getDecorView().postDelayed(() -> {
                if (!isFinishing() && !isDestroyed() && !prefs.hasPairing()) {
                    readClipboardAndPair(false);
                }
            }, 600);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SyncService.addListener(this);
        applyStatus(SyncService.lastStatus());
        refreshRecent();
        updateEventLine();
    }

    @Override
    protected void onStop() {
        SyncService.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (io != null) {
            io.shutdownNow();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- layout

    private View buildRoot() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(28));
        root.setBackgroundColor(Color.TRANSPARENT);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildHeader());
        root.addView(space(16));
        root.addView(buildConnectionCard());
        root.addView(space(14));
        root.addView(buildAutoCard());
        root.addView(space(14));
        root.addView(buildRecentCard());
        root.addView(space(18));

        logLine = text("等待启动…", 10.5f, muted, false);
        logLine.setGravity(Gravity.CENTER);
        root.addView(logLine);
        TextView footer = text("SmsBridge 1.3.7 · 数据只在局域网里传输", 10, muted, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(4), 0, 0);
        root.addView(footer);

        TextView github = text("GitHub 开源仓库：github.com/wxhfywyovo/SmsBridge", 10.5f,
                mintDark, true);
        github.setGravity(Gravity.CENTER);
        github.setPadding(0, dp(4), 0, 0);
        github.setOnClickListener(v -> openGithub());
        root.addView(github);
        return scroll;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        mascot = new MascotView(this);
        mascot.setMood(false, true);
        header.addView(mascot, new LinearLayout.LayoutParams(dp(64), dp(74)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(6), 0, dp(8), 0);
        TextView title = text("短信小桥", 22, ink, true);
        TextView subtitle = text("手机短信，一秒到电脑", 11, muted, false);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusPill = text("● 未连接", 11, ink, true);
        statusPill.setPadding(dp(10), dp(6), dp(10), dp(6));
        statusPill.setBackgroundResource(R.drawable.pill_lavender);
        statusPill.setMaxWidth(dp(150));
        statusPill.setEllipsize(TextUtils.TruncateAt.END);
        statusPill.setSingleLine(true);
        header.addView(statusPill);
        return header;
    }

    private View buildConnectionCard() {
        LinearLayout card = card();

        TextView eyebrow = text("连接电脑", 12, muted, true);
        card.addView(eyebrow);

        pcTitle = text("还没有配对电脑", 19, ink, true);
        pcTitle.setPadding(0, dp(4), 0, dp(2));
        card.addView(pcTitle);

        pcDetail = text("手机和电脑连同一个 Wi-Fi，配对一次就会永远记住。", 12, muted, false);
        pcDetail.setPadding(0, 0, 0, dp(12));
        card.addView(pcDetail);

        LinearLayout serverRow = new LinearLayout(this);
        serverRow.setOrientation(LinearLayout.HORIZONTAL);
        hostField = field("电脑 IP，比如 192.168.1.5", InputType.TYPE_CLASS_TEXT);
        hostField.setText(prefs.host());
        portField = field("端口", InputType.TYPE_CLASS_NUMBER);
        portField.setText(Integer.toString(prefs.port()));
        portField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        serverRow.addView(hostField, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams portParams =
                new LinearLayout.LayoutParams(dp(82), ViewGroup.LayoutParams.WRAP_CONTENT);
        portParams.leftMargin = dp(8);
        serverRow.addView(portField, portParams);
        card.addView(serverRow);

        pinField = field("电脑上的 6 位配对码", InputType.TYPE_CLASS_NUMBER);
        pinField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        pinField.setGravity(Gravity.CENTER);
        pinField.setLetterSpacing(0.25f);
        pinField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        LinearLayout.LayoutParams pinParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pinParams.topMargin = dp(10);
        card.addView(pinField, pinParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, 0);
        pairButton = button("连接电脑", true);
        pairButton.setOnClickListener(v -> pair());
        discoverButton = button("自动发现", false);
        discoverButton.setOnClickListener(v -> discover());
        buttons.addView(pairButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams discoverParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        discoverParams.leftMargin = dp(8);
        buttons.addView(discoverButton, discoverParams);
        card.addView(buttons);

        Button clipboardButton = button("从剪贴板一键配对", false);
        clipboardButton.setOnClickListener(v -> readClipboardAndPair(true));
        LinearLayout.LayoutParams clipboardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clipboardParams.topMargin = dp(8);
        card.addView(clipboardButton, clipboardParams);

        TextView hint = text("电脑上点「复制配对信息」，手机会自动读取剪贴板里的 IP 和配对码。\n"
                + "配对成功后，离开电脑、电脑重启都会自动连回来。", 10.5f, muted, false);
        hint.setPadding(0, dp(10), 0, 0);
        hint.setLineSpacing(0, 1.2f);
        card.addView(hint);

        permissionRow = new LinearLayout(this);
        permissionRow.setOrientation(LinearLayout.HORIZONTAL);
        permissionRow.setGravity(Gravity.CENTER_VERTICAL);
        permissionRow.setBackgroundResource(R.drawable.pill_peach);
        permissionRow.setPadding(dp(12), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams permissionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        permissionParams.topMargin = dp(10);
        permissionText = text("未开启通知读取，收不到短信", 10.5f, danger, false);
        permissionRow.addView(permissionText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView grant = text("去开启", 11, mintDark, true);
        grant.setPadding(dp(8), dp(4), dp(4), dp(4));
        grant.setOnClickListener(v -> openNotificationAccess());
        permissionRow.addView(grant);
        card.addView(permissionRow, permissionParams);
        return card;
    }

    private View buildAutoCard() {
        LinearLayout card = card();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("自动同步", 16, ink, true), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(text("离开也会自己连回来", 10.5f, muted, false));
        card.addView(header);

        syncSwitch = makeSwitch();
        syncSwitch.setChecked(prefs.enabled() && prefs.hasPairing());
        syncSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (updatingSwitch) {
                return;
            }
            if (checked) {
                if (!prefs.hasPairing()) {
                    toast("先完成配对，再打开自动同步");
                    setSyncSwitch(false);
                    return;
                }
                prefs.setEnabled(true);
                startAction(SyncService.ACTION_START);
                toast("已开启后台同步");
            } else {
                prefs.setEnabled(false);
                startAction(SyncService.ACTION_STOP);
                toast("已关闭后台同步");
            }
        });
        card.addView(switchRow("后台常驻同步", "锁屏也能收短信并复制", syncSwitch));

        bootSwitch = makeSwitch();
        bootSwitch.setChecked(prefs.autoStart());
        bootSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.setAutoStart(checked);
            toast(checked ? "开机后会自动连接" : "已关闭开机自启");
        });
        card.addView(switchRow("开机自动启动", "重启手机也不用重新打开 App", bootSwitch));

        hideSwitch = makeSwitch();
        hideSwitch.setChecked(prefs.hideBackground());
        hideSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.setHideBackground(checked);
            toast(checked
                    ? "已开启：离开 App 后自动隐藏，同步继续运行"
                    : "已关闭：App 会正常显示在最近任务");
        });
        card.addView(switchRow("隐藏后台", "开启后离开 App 自动从最近任务移除；再打开会显示本页，方便关闭", hideSwitch));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(10), 0, 0);
        Button battery = button("电池优化", false);
        battery.setOnClickListener(v -> openBatterySettings());
        Button notification = button("通知设置", false);
        notification.setOnClickListener(v -> openNotificationSettings());
        actions.addView(battery, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams notifyParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        notifyParams.leftMargin = dp(8);
        actions.addView(notification, notifyParams);
        card.addView(actions);

        TextView clear = text("清除配对信息", 10.5f, danger, false);
        clear.setPadding(0, dp(12), 0, 0);
        clear.setOnClickListener(v -> confirmClearPairing());
        card.addView(clear);

        TextView diag = text("诊断信息 / 锁屏保活帮助", 10.5f, mintDark, true);
        diag.setPadding(0, dp(10), 0, 0);
        diag.setOnClickListener(v -> showDiagnostics());
        card.addView(diag);

        TextView flush = text("立即发送待同步短信", 10.5f, mintDark, true);
        flush.setPadding(0, dp(8), 0, 0);
        flush.setOnClickListener(v -> {
            startAction(SyncService.ACTION_FLUSH);
            toast("正在发送待同步队列…");
        });
        card.addView(flush);

        TextView smsLog = text("查看短信日志（含完整正文）", 10.5f, muted, false);
        smsLog.setPadding(0, dp(8), 0, 0);
        smsLog.setOnClickListener(v -> showSmsLog());
        card.addView(smsLog);

        notificationAccessLine = text("通知读取（系统短信始终读取；勾选的 App 只同步验证码）：检查中…",
                10.5f, muted, false);
        notificationAccessLine.setPadding(0, dp(8), 0, 0);
        notificationAccessLine.setOnClickListener(v -> openNotificationAccess());
        card.addView(notificationAccessLine);

        scopeButton = button("选择读取通知的 App", false);
        scopeButton.setOnClickListener(v -> showAppPicker());
        LinearLayout.LayoutParams scopeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scopeParams.topMargin = dp(8);
        card.addView(scopeButton, scopeParams);

        notificationAccessButton = button("开启通知读取", false);
        notificationAccessButton.setOnClickListener(v -> openNotificationAccess());
        LinearLayout.LayoutParams notifButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        notifButtonParams.topMargin = dp(8);
        card.addView(notificationAccessButton, notifButtonParams);
        return card;
    }

    private View buildRecentCard() {
        LinearLayout card = card();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("最近同步", 16, ink, true), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = text("清空", 11, muted, false);
        clear.setPadding(dp(8), dp(4), 0, dp(4));
        clear.setOnClickListener(v -> {
            prefs.clearRecent();
            refreshRecent();
        });
        header.addView(clear);
        card.addView(header);

        recentList = new LinearLayout(this);
        recentList.setOrientation(LinearLayout.VERTICAL);
        recentList.setPadding(0, dp(8), 0, 0);
        card.addView(recentList);
        return card;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_white);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(2);
        card.setLayoutParams(params);
        return card;
    }

    private View switchRow(String title, String subtitle, Switch toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.addView(text(title, 13.5f, ink, true));
        textColumn.addView(text(subtitle, 10.5f, muted, false));
        row.addView(textColumn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(toggle);
        return row;
    }

    private Switch makeSwitch() {
        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        ColorStateList thumb = new ColorStateList(states,
                new int[]{mintDark, Color.parseColor("#D8D1E2")});
        ColorStateList track = new ColorStateList(states,
                new int[]{Color.parseColor("#BDEEDF"), Color.parseColor("#EAE4F0")});
        toggle.setThumbTintList(thumb);
        toggle.setTrackTintList(track);
        return toggle;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return view;
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(muted);
        field.setTextColor(ink);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setBackgroundResource(R.drawable.field_bg);
        field.setPadding(dp(14), dp(11), dp(14), dp(11));
        return field;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? Color.WHITE : mintDark);
        button.setBackgroundResource(primary ? R.drawable.btn_primary : R.drawable.btn_secondary);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(14), dp(11), dp(14), dp(11));
        button.setStateListAnimator(null);
        return button;
    }

    private View space(int heightDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- actions

    private void pair() {
        String host = hostField.getText().toString().trim();
        String portText = portField.getText().toString().trim();
        String pin = pinField.getText().toString().trim();
        if (host.isEmpty()) {
            toast("先输入电脑 IP，或点「自动发现」");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            toast("端口必须是数字");
            return;
        }
        if (port < 1024 || port > 65535) {
            toast("端口范围 1024 - 65535");
            return;
        }
        prefs.setServer(host, port);
        if (!pin.isEmpty() && !pin.matches("\\d{4,8}")) {
            toast("配对码是电脑上的 4-8 位数字");
            return;
        }
        boolean hasToken = !prefs.token().isEmpty();
        if (!hasToken && pin.isEmpty()) {
            toast("请输入电脑屏幕上的 6 位配对码");
            return;
        }
        Intent intent = new Intent(this, SyncService.class);
        if (!pin.isEmpty()) {
            prefs.setPin(pin);
            prefs.setToken("");
            intent.setAction(SyncService.ACTION_PAIR)
                    .putExtra(SyncService.EXTRA_HOST, host)
                    .putExtra(SyncService.EXTRA_PORT, port)
                    .putExtra(SyncService.EXTRA_PIN, pin);
        } else {
            intent.setAction(SyncService.ACTION_START);
        }
        prefs.setEnabled(true);
        startAction(intent);
        setSyncSwitch(true);
        maybeRequestBatteryExemption();
        pcTitle.setText("正在配对…");
        pcDetail.setText("请确认电脑上的配对码，成功后会自动记住这台电脑。");
        toast("正在连接电脑…");
    }

    /** 读取剪贴板：电脑复制了 SMSBRIDGE-PAIR|... 时自动填 IP/端口/配对码并连接。 */
    private void readClipboardAndPair(boolean manual) {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager == null || !manager.hasPrimaryClip()) {
                if (manual) {
                    toast("剪贴板是空的，先在电脑上点「复制配对信息」");
                }
                return;
            }
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                if (manual) {
                    toast("剪贴板里没有文字");
                }
                return;
            }
            CharSequence raw = clip.getItemAt(0).coerceToText(this);
            PairingInfo info = PairingInfo.parse(raw == null ? "" : raw.toString());
            if (info == null) {
                if (manual) {
                    toast("剪贴板里没有配对信息。请先在电脑上点「复制配对信息」");
                }
                return;
            }
            if (!manual && !info.hasHost()) {
                // 单独一个 6 位数字太容易误触（验证码、电话号码），只允许手动使用。
                return;
            }
            if (info.hasHost()) {
                hostField.setText(info.host);
                portField.setText(Integer.toString(info.port));
                prefs.setServer(info.host, info.port);
            } else if (hostField.getText().toString().trim().isEmpty()) {
                toast("剪贴板里只有配对码，正在自动找电脑…");
                discoverThenPair(info);
                return;
            }
            pinField.setText(info.pin);
            prefs.setPin(info.pin);
            prefs.addEvent("从剪贴板读取到配对信息"
                    + (info.hasHost() ? "（含电脑地址）" : "（仅配对码）"));
            if (manual) {
                toast("已读取剪贴板，正在连接…");
            }
            pair();
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            prefs.addEvent("读取剪贴板失败：" + reason);
            if (manual) {
                toast("读取剪贴板失败：" + reason);
            }
        }
    }

    /** 剪贴板里只有配对码时，先广播找电脑，再自动配对。 */
    private void discoverThenPair(PairingInfo info) {
        discoverButton.setEnabled(false);
        discoverButton.setText("搜索中…");
        io.execute(() -> {
            List<Discovery.Host> hosts = Discovery.discover(3000);
            runOnUiThread(() -> {
                discoverButton.setEnabled(true);
                discoverButton.setText("自动发现");
                if (hosts.isEmpty()) {
                    toast("没有找到电脑，确认同一 Wi-Fi 且电脑端已打开");
                    return;
                }
                Discovery.Host host = hosts.get(0);
                hostField.setText(host.ip);
                portField.setText(Integer.toString(host.port));
                pinField.setText(info.pin);
                prefs.setServer(host.ip, host.port);
                prefs.setPin(info.pin);
                pair();
            });
        });
    }

    private void discover() {
        discoverButton.setEnabled(false);
        discoverButton.setText("搜索中…");
        io.execute(() -> {
            List<Discovery.Host> hosts = Discovery.discover(3000);
            runOnUiThread(() -> {
                discoverButton.setEnabled(true);
                discoverButton.setText("自动发现");
                if (hosts.isEmpty()) {
                    toast("没有找到电脑，确认同一 Wi-Fi 且电脑端已打开");
                    return;
                }
                Discovery.Host host = hosts.get(0);
                hostField.setText(host.ip);
                portField.setText(Integer.toString(host.port));
                toast("找到 " + host.name + "（" + host.ip + "）");
            });
        });
    }

    private void startAction(String action) {
        startAction(new Intent(this, SyncService.class).setAction(action));
    }

    private void startAction(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            toast("启动同步服务失败：" + e.getMessage());
        }
    }

    private void setSyncSwitch(boolean checked) {
        updatingSwitch = true;
        syncSwitch.setChecked(checked);
        updatingSwitch = false;
    }

    private void confirmClearPairing() {
        new AlertDialog.Builder(this)
                .setTitle("清除配对信息？")
                .setMessage("清除后需要在电脑上重新输入配对码。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (dialog, which) -> {
                    prefs.clearPairing();
                    prefs.setEnabled(false);
                    startAction(SyncService.ACTION_STOP);
                    setSyncSwitch(false);
                    pinField.setText("");
                    pcTitle.setText("还没有配对电脑");
                    pcDetail.setText("手机和电脑连同一个 Wi-Fi，配对一次就会永远记住。");
                    toast("已清除配对信息");
                })
                .show();
    }

    private void openBatterySettings() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("已经不受电池优化限制");
            return;
        }
        suppressAutoHide = true;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                toast("请在系统设置里允许后台运行");
            }
        }
    }

    private void openNotificationSettings() {
        suppressAutoHide = true;
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            toast("请到系统设置里允许通知");
        }
    }

    // ---------------------------------------------------------------- state

    @Override
    public void onStatus(SyncService.Status status) {
        runOnUiThread(() -> applyStatus(status));
    }

    private void applyStatus(SyncService.Status status) {
        if (status == null) {
            return;
        }
        boolean online = status.state == SyncService.State.ONLINE;
        switch (status.state) {
            case ONLINE -> {
                statusPill.setText("● 已连接");
                statusPill.setBackgroundResource(R.drawable.pill_mint);
                statusPill.setTextColor(mintDark);
            }
            case CONNECTING -> {
                statusPill.setText("● 连接中");
                statusPill.setBackgroundResource(R.drawable.pill_lavender);
                statusPill.setTextColor(ink);
            }
            case NEED_PAIR -> {
                statusPill.setText("● 待配对");
                statusPill.setBackgroundResource(R.drawable.pill_peach);
                statusPill.setTextColor(danger);
            }
            default -> {
                statusPill.setText("● 未连接");
                statusPill.setBackgroundResource(R.drawable.pill_lavender);
                statusPill.setTextColor(ink);
            }
        }
        mascot.setMood(online, status.state != SyncService.State.ERROR);
        pcTitle.setText(online ? status.pcName : "还没有连接电脑");
        String detail = status.title;
        if (status.detail != null && !status.detail.isEmpty()) {
            detail = detail + " · " + status.detail;
        }
        pcDetail.setText(detail);
        if (online) {
            pairButton.setText("重新配对");
            setSyncSwitch(true);
        } else if (!prefs.token().isEmpty()) {
            pairButton.setText("连接电脑");
        } else {
            pairButton.setText("配对并连接");
        }
    }

    @Override
    public void onRecent(Prefs.Msg msg) {
        runOnUiThread(() -> {
            refreshRecent();
            updateEventLine();
        });
    }

    @Override
    public void onLog(String line) {
        runOnUiThread(() -> logLine.setText(line));
    }

    private void updateEventLine() {
        if (logLine == null) {
            return;
        }
        List<String> events = prefs.events();
        logLine.setText(events.isEmpty() ? "等待第一条短信…" : events.get(0));
    }

    private void refreshRecent() {
        if (recentList == null) {
            return;
        }
        recentList.removeAllViews();
        List<Prefs.Msg> recent = prefs.recent();
        if (recent.isEmpty()) {
            TextView empty = text("还没有同步记录～配对后收到的短信会出现在这里。", 11, muted, false);
            empty.setPadding(0, dp(8), 0, dp(4));
            empty.setLineSpacing(0, 1.2f);
            recentList.addView(empty);
            return;
        }
        for (int i = 0; i < recent.size() && i < 10; i++) {
            recentList.addView(recentRow(recent.get(i)));
        }
    }

    private View recentRow(Prefs.Msg msg) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.card_soft);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(7);
        row.setLayoutParams(params);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(msg.from == null || msg.from.isEmpty() ? "未知号码" : msg.from,
                12.5f, ink, true), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(text(formatTime(msg.ts), 10, muted, false));
        row.addView(top);

        if (msg.code != null && !msg.code.isEmpty()) {
            TextView chip = text("验证码 " + msg.code, 10.5f, mintDark, true);
            chip.setBackgroundResource(R.drawable.pill_mint);
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipParams.topMargin = dp(6);
            row.addView(chip, chipParams);
        }

        TextView body = text(msg.body, 11.5f, ink, false);
        body.setMaxLines(2);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setLineSpacing(0, 1.2f);
        body.setPadding(0, dp(5), 0, 0);
        row.addView(body);

        TextView state = text(msg.copied ? "电脑已复制" : "已收到，等待电脑确认",
                9.5f, msg.copied ? mintDark : getColor(R.color.peach_dark), false);
        state.setPadding(0, dp(4), 0, 0);
        row.addView(state);
        return row;
    }

    private String formatTime(long ts) {
        java.time.LocalDateTime time = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(ts), java.time.ZoneId.systemDefault());
        java.time.LocalDate today = java.time.LocalDate.now();
        if (time.toLocalDate().equals(today)) {
            return "今天 " + time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        }
        return time.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (prefs.hideBackground() && !suppressAutoHide && !isChangingConfigurations()) {
            finishAndRemoveTask();
        }
    }

    private void maybeRequestBatteryExemption() {
        if (prefs.batteryAsked()) {
            return;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
                prefs.setBatteryAsked(true);
                return;
            }
            prefs.setBatteryAsked(true);
            suppressAutoHide = true;
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("请手动在电池设置里允许后台运行，锁屏才不会断");
        }
    }

    private boolean notificationAccessGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            return false;
        }
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            ComponentName component = new ComponentName(this, SmsNotificationListener.class);
            return manager != null
                    && manager.isNotificationListenerAccessGranted(component);
        } catch (Exception e) {
            return false;
        }
    }

    private void updateNotificationAccessLine() {
        if (notificationAccessLine == null) {
            return;
        }
        boolean granted = notificationAccessGranted();
        boolean connected = SmsNotificationListener.isConnected();
        if (granted) {
            try {
                SmsNotificationListener.requestRebind(
                        new ComponentName(this, SmsNotificationListener.class));
            } catch (Exception ignored) {
                // 忽略
            }
        }
        String scope = notificationScopeLabel();
        notificationAccessLine.setText(granted
                ? (connected
                ? "通知读取（" + scope + "）：已开启 · 服务已连接"
                : "通知读取（" + scope + "）：已开启 · 等待连接")
                : "通知读取（" + scope + "）：未开启，点这里开启");
        notificationAccessLine.setTextColor(granted ? mintDark : danger);
        if (scopeButton != null) {
            scopeButton.setText(prefs.notifPackages().isEmpty()
                    ? "选择读取通知的 App（系统短信始终读取）"
                    : "选择读取通知的 App（已选 "
                    + prefs.notifPackages().size() + " 个）");
        }
        if (notificationAccessButton != null) {
            notificationAccessButton.setVisibility(granted ? View.GONE : View.VISIBLE);
        }
    }

    private String notificationScopeLabel() {
        Set<String> selected = prefs.notifPackages();
        return selected.isEmpty()
                ? "系统短信始终读取"
                : "系统短信始终读取 + " + selected.size() + " 个勾选的 App";
    }

    private void openNotificationAccess() {
        ComponentName component = new ComponentName(this, SmsNotificationListener.class);
        suppressAutoHide = true;
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                component.flattenToString());
            } else {
                intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            }
            startActivity(intent);
            toast("请允许「短信小桥通知读取」");
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                toast("请允许「短信小桥通知读取」");
            } catch (Exception ignored) {
                toast("请在系统设置 → 通知 → 通知使用权里开启");
            }
        }
    }

    // ------------------------------------------------- 免责声明 / 开源仓库

    private void showDisclaimer() {
        new AlertDialog.Builder(this)
                .setTitle("免责声明")
                .setMessage("本工具把你手机收到的短信通知转发给你自己配对的电脑，"
                        + "只在你自己的局域网里传输，不经过任何服务器。\n\n"
                        + "仅供个人使用，禁止商业用途；短信内容包含隐私，"
                        + "请确认手机和电脑所在网络可信。\n\n"
                        + "软件由 AI 生成，可能存在 Bug，作者不保证及时修复，使用后果自负。")
                .setCancelable(false)
                .setPositiveButton("我已知晓",
                        (dialog, which) -> prefs.setDisclaimerAccepted(true))
                .setNeutralButton("GitHub 开源仓库", (dialog, which) -> openGithub())
                .setNegativeButton("不同意，退出", (dialog, which) -> {
                    dialog.dismiss();
                    finishAffinity();
                })
                .show();
    }

    private void openGithub() {
        suppressAutoHide = true;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL));
            List<ResolveInfo> matches = getPackageManager().queryIntentActivities(intent, 0);
            if (matches.size() == 1) {
                intent.setPackage(matches.get(0).activityInfo.packageName);
                startActivity(intent);
            } else {
                startActivity(Intent.createChooser(intent, "打开 GitHub 开源仓库"));
            }
        } catch (Exception e) {
            toast("请用浏览器打开 github.com/wxhfywyovo/SmsBridge");
        }
    }

    // ------------------------------------------------- 选择读取通知的 App

    private void showAppPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> pairs = new ArrayList<>();
        for (ApplicationInfo info : pm.getInstalledApplications(0)) {
            String pkg = info.packageName;
            if (pkg == null || pkg.equals(getPackageName())) {
                continue;
            }
            // 不能只按「有没有启动图标」过滤：很多机型的系统短信 App 恰好没有启动图标
            // （比如 vivo 的 com.vivo.mms），会被过滤掉导致选不到。
            // 收集规则：有启动图标 → 收；是系统短信 App（默认短信应用或已知短信包名）→ 也收。
            boolean hasLauncher = pm.getLaunchIntentForPackage(pkg) != null;
            boolean isSms = SmsNotificationListener.isSmsPackage(this, pkg);
            if (!hasLauncher && !isSms) {
                continue;
            }
            String label = pkg;
            try {
                CharSequence shown = pm.getApplicationLabel(info);
                if (shown != null && shown.length() > 0) {
                    label = shown.toString();
                }
            } catch (Exception ignored) {
                // 拿不到名称时保留包名
            }
            if (isSms) {
                label = label + "（系统短信）";
            }
            pairs.add(new String[]{label, pkg});
        }
        pairs.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));

        final List<String> allLabels = new ArrayList<>();
        final List<String> allPkgs = new ArrayList<>();
        for (String[] pair : pairs) {
            allLabels.add(pair[0]);
            allPkgs.add(pair[1]);
        }

        Set<String> checked = new LinkedHashSet<>(prefs.notifPackages());
        // 系统短信始终在读取范围内，不需要默认勾选，也不要求用户勾选。

        final List<String> labels = new ArrayList<>();
        final List<String> pkgs = new ArrayList<>();
        EditText search = field("搜索 App 名称，比如「信息」", InputType.TYPE_CLASS_TEXT);
        TextView hint = text("已勾选 " + checked.size() + " 个",
                10.5f, muted, false);
        hint.setPadding(0, dp(8), 0, dp(4));
        Runnable refreshHint = () -> hint.setText("已勾选 " + checked.size() + " 个");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                AppRowView row = convertView instanceof AppRowView
                        ? (AppRowView) convertView : new AppRowView();
                row.label.setText(labels.get(position));
                row.pkg.setText(pkgs.get(position));
                row.check.setChecked(checked.contains(pkgs.get(position)));
                return row;
            }

            @Override
            public int getCount() {
                return labels.size();
            }

            @Override
            public String getItem(int position) {
                return labels.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }
        };

        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setDividerHeight(0);
        list.setOnItemClickListener((parent, view, position, id) -> {
            String pkg = pkgs.get(position);
            if (checked.contains(pkg)) {
                checked.remove(pkg);
            } else {
                checked.add(pkg);
            }
            refreshHint.run();
            adapter.notifyDataSetChanged();
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence sequence, int start, int count,
                    int after) {
            }

            @Override
            public void onTextChanged(CharSequence sequence, int start, int before,
                    int count) {
                String key = sequence.toString().trim().toLowerCase();
                labels.clear();
                pkgs.clear();
                for (int i = 0; i < allLabels.size(); i++) {
                    String label = allLabels.get(i);
                    String pkg = allPkgs.get(i);
                    if (key.isEmpty() || label.toLowerCase().contains(key)
                            || pkg.toLowerCase().contains(key)) {
                        labels.add(label);
                        pkgs.add(pkg);
                    }
                }
                adapter.notifyDataSetChanged();
                list.setSelection(0);
            }

            @Override
            public void afterTextChanged(Editable sequence) {
            }
        });

        TextView selectAll = text("全选当前结果", 10.5f, mintDark, true);
        selectAll.setPadding(0, dp(4), 0, dp(8));
        selectAll.setOnClickListener(v -> {
            for (String pkg : pkgs) {
                checked.add(pkg);
            }
            refreshHint.run();
            adapter.notifyDataSetChanged();
        });

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(10));
        body.addView(search);
        body.addView(hint);
        body.addView(selectAll);
        body.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

        TextView githubLink = text("GitHub 开源仓库：github.com/wxhfywyovo/SmsBridge",
                10f, mintDark, true);
        githubLink.setGravity(Gravity.CENTER);
        githubLink.setPadding(0, dp(10), 0, dp(2));
        githubLink.setOnClickListener(v -> openGithub());
        body.addView(githubLink);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择读取通知的 App")
                .setView(body)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String defaultSms = null;
            try {
                defaultSms = Telephony.Sms.getDefaultSmsPackage(this);
            } catch (Exception ignored) {
                // 忽略
            }
            boolean smsChecked = defaultSms != null && checked.contains(defaultSms);
            prefs.setNotifPackages(checked);
            dialog.dismiss();
            updateNotificationAccessLine();
            if (checked.isEmpty()) {
                toast("已保存：一个都没选，只读取系统短信");
            } else if (!smsChecked) {
                // 系统短信永远在读取范围内，不需要勾选；这里提示避免用户误以为要勾。
                toast("已保存，读取勾选的 " + checked.size()
                        + " 个 App；系统短信始终会读取，不用勾选");
            } else {
                toast("已保存，只读取勾选的 " + checked.size() + " 个 App");
            }
        });
    }

    private void showDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android：").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            sb.append("App 版本：").append(info.versionName)
                    .append("（").append(info.versionCode).append("）\n");
        } catch (Exception ignored) {
            // 忽略
        }
        sb.append("待发送队列：").append(prefs.queue().size()).append(" 条\n");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sb.append("通知权限：").append(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED ? "已授予" : "未授予").append('\n');
        }
        sb.append("通知读取：").append(notificationAccessGranted()
                ? "已开启" : "未开启").append('\n');
        sb.append("通知读取服务：").append(SmsNotificationListener.isConnected()
                ? "已连接" : "未连接").append('\n');
        sb.append("通知范围：").append(notificationScopeLabel())
                .append("（勾选的 App 只有正文含验证码才同步）\n");
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        sb.append("电池优化：").append(pm != null
                && pm.isIgnoringBatteryOptimizations(getPackageName())
                ? "已忽略（锁屏更稳）" : "未忽略（锁屏可能断）").append('\n');
        SyncService.Status status = SyncService.lastStatus();
        sb.append("服务：").append(status.title).append(" · ").append(status.detail).append('\n');
        sb.append("同步开关：").append(prefs.enabled() ? "开" : "关")
                .append("，配对：").append(prefs.hasPairing() ? "是" : "否").append('\n');
        sb.append("电脑：").append(prefs.pcName()).append(' ')
                .append(prefs.host()).append(':').append(prefs.port()).append('\n');
        sb.append("\n最近事件：\n");
        List<String> events = prefs.events();
        if (events.isEmpty()) {
            sb.append("（还没有事件）\n");
        } else {
            for (String event : events) {
                sb.append(event).append('\n');
            }
        }
        sb.append("\n收不到验证码怎么办：\n")
                .append("1. 「通知读取」已开启且服务已连接；\n")
                .append("2. 短信 App 不需要出现在「选择读取通知的 App」里（系统短信始终读取）；\n")
                .append("3. 如果「收到短信」但没同步：查看下面的事件里是否有「丢弃通知」记录，\n")
                .append("   那说明通知进来了但被跳过了，把事件复制出来给作者看。\n");
        if (prefs.queue().size() > 0) {
            sb.append("4. 队列还有 " + prefs.queue().size() + " 条未发送：检查电脑是否已连接。\n");
        }

        TextView content = text(sb.toString(), 11, ink, false);
        content.setTextIsSelectable(true);
        content.setPadding(dp(18), dp(8), dp(18), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        new AlertDialog.Builder(this)
                .setTitle("诊断信息")
                .setView(scroll)
                .setPositiveButton("复制", (dialog, which) -> copyDiagnostic(sb.toString()))
                .setNeutralButton("测试同步", (dialog, which) -> sendTestSms())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void copyDiagnostic(String text) {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("SmsBridge 诊断", text));
            toast("诊断信息已复制");
        }
    }

    private void showSmsLog() {
        String log = SmsLogger.read(this);
        if (log.isEmpty()) {
            log = "还没有短信日志。\n收到短信后，这里会记录完整正文。";
        }
        final String logText = log;
        String header = "文件：" + SmsLogger.path(this)
                + "\n（包含完整短信正文，排查后请点「清空」）\n\n";
        TextView content = text(header + logText, 9.5f, ink, false);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        content.setPadding(dp(18), dp(8), dp(18), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        new AlertDialog.Builder(this)
                .setTitle("短信日志")
                .setView(scroll)
                .setPositiveButton("复制", (dialog, which) -> {
                    ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (manager != null) {
                        manager.setPrimaryClip(ClipData.newPlainText("SmsBridge SMS 日志", logText));
                        toast("短信日志已复制");
                    }
                })
                .setNeutralButton("分享", (dialog, which) -> {
                    Intent send = new Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "SmsBridge 短信日志")
                            .putExtra(Intent.EXTRA_TEXT, header + logText);
                    suppressAutoHide = true;
                    startActivity(Intent.createChooser(send, "分享短信日志"));
                })
                .setNegativeButton("清空", (dialog, which) -> confirmClearSmsLog())
                .show();
    }

    private void confirmClearSmsLog() {
        new AlertDialog.Builder(this)
                .setTitle("清空短信日志？")
                .setMessage("会删除包含完整短信正文的本地日志。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    SmsLogger.clear(this);
                    toast("短信日志已清空");
                })
                .show();
    }

    private void sendTestSms() {
        if (!prefs.enabled()) {
            toast("先打开「后台常驻同步」");
            return;
        }
        String body = "【SmsBridge 测试】验证码：123456，仅用于测试。";
        String id = "test-" + System.currentTimeMillis();
        Prefs.Msg msg = new Prefs.Msg(id, "SmsBridge 测试", body, "123456",
                System.currentTimeMillis(), false);
        prefs.enqueue(msg);
        prefs.addRecent(msg);
        SyncService.publishLocalSms(msg);
        Intent intent = new Intent(this, SyncService.class)
                .setAction(SyncService.ACTION_SMS)
                .putExtra(SyncService.EXTRA_FROM, msg.from)
                .putExtra(SyncService.EXTRA_BODY, body)
                .putExtra(SyncService.EXTRA_TS, msg.ts)
                .putExtra(SyncService.EXTRA_ID, id);
        startAction(intent);
        toast("已发送测试短信；电脑若已连接会自动复制 123456");
    }

    // ---------------------------------------------------------------- permission

    private void requestRuntimePermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            suppressAutoHide = true;
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            suppressAutoHide = false;
            updatePermissionRow();
        }
    }

    private void updatePermissionRow() {
        if (permissionRow == null) {
            return;
        }
        // 短信通过「通知读取」获取，这里检查的其实是通知使用权是否开启。
        if (notificationAccessGranted()) {
            permissionRow.setVisibility(View.GONE);
            return;
        }
        permissionRow.setVisibility(View.VISIBLE);
        permissionText.setText("未开启通知读取，收不到短信");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** 选择 App 列表的一行：勾选框 + 名称 + 包名。 */
    private final class AppRowView extends LinearLayout {
        final CheckBox check = new CheckBox(MainActivity.this);
        final TextView label = text("", 13.5f, ink, false);
        final TextView pkg = text("", 10f, muted, false);

        AppRowView() {
            super(MainActivity.this);
            setOrientation(LinearLayout.HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(10), dp(16), dp(10));
            addView(check, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout column = new LinearLayout(MainActivity.this);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colParams.leftMargin = dp(12);
            column.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            column.addView(pkg, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(column, colParams);
        }
    }
}
