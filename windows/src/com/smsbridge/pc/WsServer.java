package com.smsbridge.pc;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 手写的最小 WebSocket 服务端（RFC 6455）：只支持文本帧、分片、ping/pong、close，
 * 不依赖任何第三方库。局域网里够用，也方便打成单个 jar。
 */
public final class WsServer implements Closeable {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 1_000_000;
    private static final int MAX_HEADER = 16 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 70_000;

    public interface Listener {
        void onOpen(Client client);

        void onMessage(Client client, String text);

        void onClose(Client client);
    }

    public static final class Client {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final String remoteIp;
        private final Object writeLock = new Object();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicLong lastPong = new AtomicLong(System.currentTimeMillis());
        private final String connectionId;
        private volatile String deviceId = "";
        private volatile String deviceName = "";
        private volatile boolean authed;

        Client(Socket socket, InputStream in, OutputStream out, String connectionId) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.connectionId = connectionId;
            this.remoteIp = socket.getInetAddress() == null
                    ? "?" : socket.getInetAddress().getHostAddress();
        }

        public String connectionId() {
            return connectionId;
        }

        public String remoteIp() {
            return remoteIp;
        }

        public String deviceId() {
            return deviceId;
        }

        void setDevice(String id, String name) {
            this.deviceId = id == null ? "" : id;
            this.deviceName = name == null ? "" : name;
        }

        public String deviceName() {
            return deviceName;
        }

        public boolean isAuthed() {
            return authed;
        }

        void setAuthed(boolean value) {
            this.authed = value;
        }

        public boolean isOpen() {
            return open.get() && !socket.isClosed();
        }

        long lastPong() {
            return lastPong.get();
        }

        void sawPong() {
            lastPong.set(System.currentTimeMillis());
        }

        public void sendText(String text) {
            sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        void sendPing(byte[] payload) {
            sendFrame(0x9, payload);
        }

        private void sendPong(byte[] payload) {
            sendFrame(0xA, payload);
        }

        private void sendClose(byte[] payload) {
            sendFrame(0x8, payload);
        }

        private void sendFrame(int opcode, byte[] payload) {
            if (!isOpen()) {
                return;
            }
            try {
                synchronized (writeLock) {
                    out.write(0x80 | opcode);
                    int length = payload.length;
                    if (length < 126) {
                        out.write(length);
                    } else if (length <= 0xFFFF) {
                        out.write(126);
                        out.write((length >>> 8) & 0xFF);
                        out.write(length & 0xFF);
                    } else {
                        out.write(127);
                        long len = length;
                        for (int shift = 56; shift >= 0; shift -= 8) {
                            out.write((int) ((len >>> shift) & 0xFF));
                        }
                    }
                    out.write(payload);
                    out.flush();
                }
            } catch (IOException e) {
                closeQuietly();
            }
        }

        public void close(int code, String reason) {
            if (open.get()) {
                byte[] r = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
                byte[] payload = new byte[2 + r.length];
                payload[0] = (byte) ((code >>> 8) & 0xFF);
                payload[1] = (byte) (code & 0xFF);
                System.arraycopy(r, 0, payload, 2, r.length);
                sendClose(payload);
            }
            closeQuietly();
        }

        void closeQuietly() {
            open.set(false);
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    private final int port;
    private final Listener listener;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean running;

    public WsServer(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    public int port() {
        return port;
    }

    public void start() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress(port), 32);
        serverSocket = ss;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "smsbridge-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                workers.submit(() -> handle(socket));
            } catch (IOException e) {
                if (running) {
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void handle(Socket socket) {
        Client client = null;
        try {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            if (!handshake(in, out)) {
                return;
            }
            client = new Client(socket, in, out, Long.toHexString(System.nanoTime()));
            clients.add(client);
            notifyOpen(client);
            readLoop(client);
        } catch (IOException | RuntimeException ignored) {
            // 客户端断开属于正常情况
        } finally {
            if (client != null) {
                clients.remove(client);
                client.closeQuietly();
                notifyClose(client);
            } else {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }
    }

    private boolean handshake(InputStream in, OutputStream out) throws IOException {
        String requestLine = readHttpLine(in);
        if (requestLine == null) {
            return false;
        }
        Map<String, String> headers = new HashMap<>();
        int total = 0;
        while (true) {
            String line = readHttpLine(in);
            if (line == null) {
                return false;
            }
            if (line.isEmpty()) {
                break;
            }
            total += line.length();
            if (total > MAX_HEADER) {
                return false;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        String upgrade = headers.getOrDefault("upgrade", "").toLowerCase(Locale.ROOT);
        String key = headers.get("sec-websocket-key");
        if (!requestLine.startsWith("GET ") || !upgrade.contains("websocket") || key == null) {
            out.write(("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n"
                    + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return false;
        }
        String accept;
        try {
            accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                            .digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 不可用", e);
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return true;
    }

    private static String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        while (true) {
            int b = in.read();
            if (b < 0) {
                return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
            }
            if (b == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
            buf.write(b);
            if (buf.size() > 8192) {
                throw new IOException("HTTP 头部过长");
            }
        }
    }

    private void readLoop(Client client) throws IOException {
        InputStream in = client.in;
        ByteArrayOutputStream fragments = null;
        while (client.isOpen()) {
            int first = in.read();
            if (first < 0) {
                return;
            }
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0F;
            int second = in.read();
            if (second < 0) {
                return;
            }
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126) {
                length = ((long) readByte(in) << 8) | readByte(in);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | readByte(in);
                }
                if (length < 0) {
                    throw new IOException("帧长度无效");
                }
            }
            if (length > MAX_MESSAGE) {
                client.close(1009, "message too big");
                throw new IOException("消息过大");
            }
            byte[] mask = null;
            if (masked) {
                mask = readN(in, 4);
            }
            byte[] payload = readN(in, (int) length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }

            switch (opcode) {
                case 0x0 -> {
                    if (fragments == null) {
                        throw new IOException("意外的续帧");
                    }
                    fragments.write(payload);
                    if (fragments.size() > MAX_MESSAGE) {
                        throw new IOException("消息过大");
                    }
                    if (fin) {
                        String text = fragments.toString(StandardCharsets.UTF_8);
                        fragments = null;
                        deliver(client, text);
                    }
                }
                case 0x1 -> {
                    if (fragments != null) {
                        throw new IOException("分片未结束");
                    }
                    if (fin) {
                        deliver(client, new String(payload, StandardCharsets.UTF_8));
                    } else {
                        fragments = new ByteArrayOutputStream(Math.max(64, payload.length * 2));
                        fragments.write(payload);
                    }
                }
                case 0x2 -> {
                    // 协议只用文本帧，二进制帧直接忽略
                    if (!fin) {
                        fragments = new ByteArrayOutputStream();
                        fragments.write(payload);
                    }
                }
                case 0x8 -> {
                    client.close(1000, "");
                    return;
                }
                case 0x9 -> client.sendPong(payload);
                case 0xA -> client.sawPong();
                default -> throw new IOException("未知 opcode " + opcode);
            }
        }
    }

    private void deliver(Client client, String text) {
        try {
            listener.onMessage(client, text);
        } catch (RuntimeException ignored) {
            // 单条消息异常不能打断连接
        }
    }

    private void notifyOpen(Client client) {
        try {
            listener.onOpen(client);
        } catch (RuntimeException ignored) {
            // 忽略
        }
    }

    private void notifyClose(Client client) {
        try {
            listener.onClose(client);
        } catch (RuntimeException ignored) {
            // 忽略
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private static byte[] readN(InputStream in, int count) throws IOException {
        byte[] buf = new byte[count];
        int read = 0;
        while (read < count) {
            int n = in.read(buf, read, count - read);
            if (n < 0) {
                throw new EOFException();
            }
            read += n;
        }
        return buf;
    }

    public List<Client> clients() {
        return new ArrayList<>(clients);
    }

    public void pingAll() {
        byte[] payload = new byte[]{(byte) 0xC0, (byte) 0x0F};
        for (Client client : clients) {
            if (client.isOpen()) {
                client.sendPing(payload);
            }
        }
    }

    public void pruneIdle(long timeoutMillis) {
        long now = System.currentTimeMillis();
        for (Client client : clients) {
            if (client.isOpen() && now - client.lastPong() > timeoutMillis) {
                client.close(1001, "heartbeat timeout");
            }
        }
    }

    @Override
    public void close() {
        running = false;
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
        for (Client client : clients) {
            client.closeQuietly();
        }
        clients.clear();
        workers.shutdownNow();
    }
}
