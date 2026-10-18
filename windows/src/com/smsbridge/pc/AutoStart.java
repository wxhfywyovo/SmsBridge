package com.smsbridge.pc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** 通过 HKCU Run 键实现 Windows 开机自动启动，不需要管理员权限。 */
public final class AutoStart {
    private static final String RUN_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE = "SmsBridge";

    private AutoStart() {
    }

    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static boolean isEnabled() {
        if (!supported()) {
            return false;
        }
        try {
            Process p = new ProcessBuilder("reg", "query", RUN_KEY, "/v", VALUE)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(4, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    public static boolean set(boolean enabled) {
        if (!supported()) {
            return false;
        }
        String key = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
        ProcessBuilder pb;
        if (enabled) {
            pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command",
                    "Set-ItemProperty -Path '" + key + "' -Name 'SmsBridge' -Value $env:SMSBRIDGE_RUN");
            pb.environment().put("SMSBRIDGE_RUN", commandLine());
        } else {
            pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command",
                    "Remove-ItemProperty -Path '" + key + "' -Name 'SmsBridge' "
                            + "-ErrorAction SilentlyContinue");
        }
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    public static String commandLine() {
        String command = ProcessHandle.current().info().command().orElse("javaw.exe");
        String lower = command.toLowerCase();
        if (lower.endsWith("java.exe") || lower.endsWith("javaw.exe")) {
            String javaw = command.substring(0, command.length() - "java.exe".length())
                    + "javaw.exe";
            Path jar = jarPath();
            if (jar != null && jar.toString().toLowerCase().endsWith(".jar")) {
                return "\"" + javaw + "\" -jar \"" + jar + "\" --tray";
            }
            return "\"" + javaw + "\" -cp \"" + System.getProperty("java.class.path")
                    + "\" com.smsbridge.pc.Main --tray";
        }
        return "\"" + command + "\" --tray";
    }

    private static Path jarPath() {
        try {
            var source = Main.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            return Path.of(source.getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }
}
