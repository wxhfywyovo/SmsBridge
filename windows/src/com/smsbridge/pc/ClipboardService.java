package com.smsbridge.pc;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** 把文本写进 Windows 剪贴板，AWT 失败时退回 PowerShell。 */
public final class ClipboardService {
    public boolean copy(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            if (copyWithAwt(text)) {
                return true;
            }
            sleep(80);
        }
        return copyWithPowerShell(text);
    }

    private boolean copyWithAwt(String text) {
        AtomicReference<Boolean> ok = new AtomicReference<>(Boolean.FALSE);
        Runnable task = () -> {
            try {
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(new StringSelection(text), null);
                ok.set(Boolean.TRUE);
            } catch (Throwable ignored) {
                ok.set(Boolean.FALSE);
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                task.run();
            } else {
                SwingUtilities.invokeAndWait(task);
            }
        } catch (Throwable ignored) {
            return false;
        }
        return Boolean.TRUE.equals(ok.get());
    }

    private boolean copyWithPowerShell(String text) {
        String script = "[Console]::InputEncoding=[System.Text.Encoding]::UTF8;"
                + "$t=[Console]::In.ReadToEnd(); Set-Clipboard -Value $t";
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile",
                "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            try (OutputStream in = process.getOutputStream()) {
                in.write(text.getBytes(StandardCharsets.UTF_8));
                in.flush();
            }
            if (!process.waitFor(6, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
