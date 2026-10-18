package com.smsbridge.pc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Windows 端单实例锁。
 *
 * 第一个实例绑定一个本地回环端口并监听；之后再次启动时，新进程连上这个端口，
 * 通知已有实例显示主窗口，然后自己退出。这样双击多少次都不会出现多个窗口/多个服务。
 */
public final class SingleInstance implements AutoCloseable {
    private static final String HELLO = "SMSBRIDGE-1";
    private static final int FIRST_PORT = 47825;
    private static final int LAST_PORT = 47839;

    private final ServerSocket server;
    private final int port;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile Runnable showHandler;
    private volatile boolean showPending;

    private SingleInstance(ServerSocket server, int port) {
        this.server = server;
        this.port = port;
        this.thread = new Thread(this::acceptLoop, "smsbridge-single-instance");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** 尝试成为唯一实例；如果已有 SmsBridge 实例或所有候选端口都被占用，返回 null。 */
    public static SingleInstance acquire() {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            try {
                ServerSocket socket = new ServerSocket(port, 1, loopback);
                return new SingleInstance(socket, port);
            } catch (IOException ignored) {
                // 端口被占用：如果对面是 SmsBridge，说明已有实例；否则继续试下一个端口。
                if (handshake(port, "PING")) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 通知已运行的实例。
     *
     * @param show true 显示主窗口，false 只确认已有实例（用于 --tray 自动启动）
     * @return true 表示找到了 SmsBridge 实例并通知成功
     */
    public static boolean notifyExisting(boolean show) {
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            if (handshake(port, show ? "SHOW" : "PING")) {
                return true;
            }
        }
        return false;
    }

    /** 向指定端口发送一次握手；只有 SmsBridge 会返回 OK。 */
    private static boolean handshake(int port, String command) {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(loopback, port), 700);
            socket.setSoTimeout(700);
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            out.write(HELLO);
            out.write('\n');
            out.write(command);
            out.write('\n');
            out.flush();
            String reply = in.readLine();
            return reply != null && reply.startsWith("OK");
        } catch (IOException ignored) {
            return false;
        }
    }

    /** UI 创建完成后再设置；如果期间已经收到 SHOW，会立即补触发。 */
    public void setShowHandler(Runnable handler) {
        this.showHandler = handler;
        if (showPending) {
            showPending = false;
            Runnable current = this.showHandler;
            if (current != null) {
                current.run();
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(1200);
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8));
                String hello = in.readLine();
                if (!HELLO.equals(hello)) {
                    out.write("NO\n");
                    out.flush();
                    continue;
                }
                String command = in.readLine();
                if ("SHOW".equals(command)) {
                    requestShow();
                }
                out.write("OK\n");
                out.flush();
            } catch (IOException ignored) {
                // accept 超时/关闭继续
            }
        }
    }

    private void requestShow() {
        Runnable handler = showHandler;
        if (handler == null) {
            showPending = true;
            return;
        }
        handler.run();
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
