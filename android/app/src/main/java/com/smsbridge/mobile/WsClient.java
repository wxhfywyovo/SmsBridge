package com.smsbridge.mobile;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 手机端的阻塞式 WebSocket 客户端。只依赖 java.net，因此也能在桌面上编译测试。
 * 支持握手、掩码文本帧、分片、ping/pong 和 close。
 */
public final class WsClient implements Closeable {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean open = true;

    private WsClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    public static WsClient connect(String host, int port, int timeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(45_000);
        WsClient client = new WsClient(socket);
        try {
            client.handshake(host, port);
        } catch (IOException e) {
            client.close();
            throw e;
        }
        return client;
    }

    private void handshake(String host, int port) throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String request = "GET /smsbridge HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: SmsBridge-Android/1.0\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String status = readLine();
        if (status == null || !status.contains(" 101 ")) {
            throw new IOException("握手失败: " + status);
        }
        String accept = null;
        while (true) {
            String line = readLine();
            if (line == null) {
                throw new IOException("握手响应不完整");
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim()
                    .equalsIgnoreCase("Sec-WebSocket-Accept")) {
                accept = line.substring(colon + 1).trim();
            }
        }
        String expected;
        try {
            expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                            .digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 不可用", e);
        }
        if (accept == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                accept.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("Sec-WebSocket-Accept 校验失败");
        }
    }

    public void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        synchronized (writeLock) {
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            out.write(0x80 | opcode);
            int length = payload.length;
            if (length < 126) {
                out.write(0x80 | length);
            } else if (length <= 0xFFFF) {
                out.write(0x80 | 126);
                out.write((length >>> 8) & 0xFF);
                out.write(length & 0xFF);
            } else {
                out.write(0x80 | 127);
                long len = length;
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) ((len >>> shift) & 0xFF));
                }
            }
            out.write(mask);
            byte[] masked = new byte[payload.length];
            for (int i = 0; i < payload.length; i++) {
                masked[i] = (byte) (payload[i] ^ mask[i & 3]);
            }
            out.write(masked);
            out.flush();
        }
    }

    /** 读取一条文本消息；收到 close 返回 null，超时抛 SocketTimeoutException。 */
    public String readText() throws IOException {
        ByteArrayOutputStream fragments = null;
        while (open) {
            int first = readByte();
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0F;
            int second = readByte();
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126) {
                length = ((long) readByte() << 8) | readByte();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | readByte();
                }
                if (length < 0) {
                    throw new IOException("帧长度无效");
                }
            }
            if (length > 1_000_000) {
                throw new IOException("消息过大");
            }
            byte[] mask = masked ? readN(4) : null;
            byte[] payload = readN((int) length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }
            switch (opcode) {
                case 0x0 -> {
                    if (fragments != null) {
                        fragments.write(payload);
                        if (fin) {
                            return fragments.toString(StandardCharsets.UTF_8);
                        }
                    }
                }
                case 0x1 -> {
                    if (fin) {
                        return new String(payload, StandardCharsets.UTF_8);
                    }
                    fragments = new ByteArrayOutputStream();
                    fragments.write(payload);
                }
                case 0x2 -> {
                    if (!fin) {
                        fragments = new ByteArrayOutputStream();
                    }
                }
                case 0x8 -> {
                    close();
                    return null;
                }
                case 0x9 -> sendFrame(0xA, payload);
                case 0xA -> {
                    // pong，继续读
                }
                default -> throw new IOException("未知 opcode " + opcode);
            }
        }
        return null;
    }

    public boolean isOpen() {
        return open && !socket.isClosed();
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private byte[] readN(int count) throws IOException {
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

    private String readLine() throws IOException {
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
        }
    }

    @Override
    public void close() {
        open = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
