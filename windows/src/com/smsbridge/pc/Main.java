package com.smsbridge.pc;

import com.smsbridge.pc.ui.Ui;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javax.swing.SwingUtilities;

/** 程序入口：默认打开可爱主界面，也支持 --tray / --headless / --screenshot。 */
public final class Main {
    public static final String VERSION = "1.0.0";

    private Main() {
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        System.setProperty("sun.java2d.uiScale.enabled", "true");

        Path home = defaultHome();
        boolean trayOnly = false;
        boolean headless = false;
        Path screenshot = null;
        Integer port = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--tray".equals(arg)) {
                trayOnly = true;
            } else if ("--headless".equals(arg)) {
                headless = true;
            } else if ("--screenshot".equals(arg) && i + 1 < args.length) {
                screenshot = Path.of(args[++i]);
            } else if ("--home".equals(arg) && i + 1 < args.length) {
                home = Path.of(args[++i]);
            } else if (arg.startsWith("--home=")) {
                home = Path.of(arg.substring("--home=".length()));
            } else if ("--port".equals(arg) && i + 1 < args.length) {
                try {
                    port = Integer.parseInt(args[++i]);
                } catch (NumberFormatException ignored) {
                    // 忽略无效端口
                }
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp();
                return;
            }
        }

        Config config = new Config(home);
        if (port != null && port >= 1024 && port <= 65535) {
            config.setPort(port);
        }

        // Windows 单实例：已有实例时只通知它显示窗口，不再创建第二个窗口/服务。
        SingleInstance singleInstance = null;
        if (screenshot == null && !headless) {
            if (SingleInstance.notifyExisting(!trayOnly)) {
                return;
            }
            singleInstance = SingleInstance.acquire();
            if (singleInstance == null) {
                // 可能是启动竞争：再确认一次；不是 SmsBridge 的话退回普通启动。
                if (SingleInstance.notifyExisting(!trayOnly)) {
                    return;
                }
                System.err.println("单实例端口被其他程序占用，继续以普通模式启动。");
            }
        }
        final SingleInstance instance = singleInstance;

        if (screenshot != null) {
            runScreenshot(config, screenshot);
            return;
        }

        HistoryStore history = new HistoryStore(config.historyFile());
        BridgeService service = new BridgeService(config, history);

        if (headless) {
            runHeadless(config, service);
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("当前环境没有图形界面，无法打开主窗口。");
            System.err.println("可以先用 --headless 启动同步服务，或到有桌面的环境运行。");
            System.exit(2);
        }

        Ui ui = new Ui(service, config);
        service.setListener(ui);
        if (instance != null) {
            instance.setShowHandler(() -> SwingUtilities.invokeLater(ui::showWindow));
            Runtime.getRuntime().addShutdownHook(
                    new Thread(instance::close, "smsbridge-single-instance-close"));
        }
        final boolean startHidden = trayOnly;
        Runnable show = () -> {
            ui.showWindow();
            if (startHidden) {
                ui.startTrayOnly();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
        Thread starter = new Thread(service::start, "smsbridge-start");
        starter.setDaemon(true);
        starter.start();
        Runtime.getRuntime().addShutdownHook(new Thread(service::stop, "smsbridge-shutdown"));
    }

    private static void runHeadless(Config config, BridgeService service) {
        System.out.println("SmsBridge " + VERSION + " headless");
        System.out.println("home=" + config.dir());
        System.out.println("port=" + config.port());
        System.out.println("pin=" + config.pin());
        service.setListener(new BridgeService.Listener() {
            @Override
            public void onServerStatus(boolean running, String error) {
                System.out.println("server=" + running + (error == null || error.isEmpty()
                        ? "" : " error=" + error));
            }

            @Override
            public void onConnectionChanged(boolean online, String deviceName) {
                System.out.println("connected=" + online + (deviceName.isEmpty()
                        ? "" : " device=" + deviceName));
            }

            @Override
            public void onMessage(HistoryStore.Entry entry) {
                System.out.println("sms=" + entry.from() + " code=" + entry.code()
                        + " copied=" + entry.copied());
            }

            @Override
            public void onDevicesChanged() {
            }

            @Override
            public void onLog(String line) {
                System.out.println("log=" + line);
            }
        });
        service.start();
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            service.stop();
            latch.countDown();
        }));
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runScreenshot(Config config, Path target) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("无图形环境，无法截图");
            System.exit(2);
        }
        try {
            Ui ui = new Ui(null, config);
            System.out.println("home=" + config.dir());
            ui.demo();
            ui.showWindow();
            ui.capture(target);
            System.out.println("screenshot=" + target.toAbsolutePath());
            ui.exitApp();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Path defaultHome() {
        String override = System.getProperty("smsbridge.home");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        List<Path> candidates = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            candidates.add(Path.of(appData, "SmsBridge"));
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(Path.of(localAppData, "SmsBridge"));
        }
        candidates.add(Path.of(System.getProperty("user.home"), ".smsbridge"));
        String exe = ProcessHandle.current().info().command().orElse("");
        if (!exe.isBlank()) {
            try {
                Path parent = Path.of(exe).toAbsolutePath().getParent();
                if (parent != null) {
                    candidates.add(parent.resolve("SmsBridgeData"));
                }
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }
        candidates.add(Path.of(System.getProperty("java.io.tmpdir"), "SmsBridge"));
        for (Path candidate : candidates) {
            if (writable(candidate)) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private static boolean writable(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = dir.resolve(".smsbridge-write-test");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void printHelp() {
        System.out.println("""
                短信小桥 SmsBridge %s

                用法:
                  java -jar SmsBridge.jar            打开主界面并同步
                  java -jar SmsBridge.jar --tray     最小化到托盘启动
                  java -jar SmsBridge.jar --headless 仅启动同步服务（无界面，调试用）
                  java -jar SmsBridge.jar --port 47823
                  java -jar SmsBridge.jar --home D:\\SmsBridgeData
                  java -jar SmsBridge.jar --screenshot out.png
                """.formatted(VERSION));
    }
}
