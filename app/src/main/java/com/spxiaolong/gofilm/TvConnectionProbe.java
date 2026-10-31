package com.spxiaolong.gofilm;

import java.io.InputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** User-initiated, read-only probes of the website; results stay on this device. */
final class TvConnectionProbe implements AutoCloseable {
    private static final String HOST = "film.spxiaolong.com";
    private volatile boolean closed;
    private volatile Socket active;
    private volatile FutureTask<InetAddress[]> dns;

    void run(Consumer<String> output) {
        if (closed) return;
        output.accept("开始检测 " + HOST + "\n（每种地址只测试一个，未覆盖全部服务器地址）\n");
        dns = new FutureTask<>(() -> InetAddress.getAllByName(HOST));
        Thread resolver = new Thread(dns, "tv-dns-probe");
        resolver.setDaemon(true);
        resolver.start();
        try {
            InetAddress[] addresses = dns.get(6, TimeUnit.SECONDS);
            if (closed) return;
            InetAddress ipv4 = null, ipv6 = null;
            for (InetAddress address : addresses) {
                output.accept("DNS: " + address.getHostAddress() + "\n");
                if (address instanceof Inet4Address && ipv4 == null) ipv4 = address;
                if (address instanceof Inet6Address && ipv6 == null) ipv6 = address;
            }
            if (ipv4 == null) output.accept("未解析到 IPv4\n");
            else {
                check(ipv4, false, "IPv4 / 默认 TLS", output);
                check(ipv4, true, "IPv4 / TLS 1.2", output);
            }
            if (ipv6 == null) output.accept("\n未解析到 IPv6（本身不代表故障）\n");
            else check(ipv6, false, "IPv6 / 默认 TLS", output);
            if (!closed) output.accept("\n检测完成，请拍照；内容较长可向下滚动。");
        } catch (Exception failure) {
            if (!closed) output.accept("DNS 检测失败: " + describe(failure) + "\n检测结束。");
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            dns.cancel(true);
        }
    }

    private void check(InetAddress address, boolean tls12, String label, Consumer<String> output) {
        if (closed || Thread.currentThread().isInterrupted()) return;
        output.accept("\n" + label + "\n");
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        String stage = "TCP";
        try (Socket plain = new Socket()) {
            active = plain;
            ScheduledFuture<?> deadline = timer.schedule(() -> {
                try { plain.close(); } catch (IOException ignored) { }
            }, 8, TimeUnit.SECONDS);
            try {
                if (closed) return;
                plain.connect(new InetSocketAddress(address, 443), 5000);
                plain.setSoTimeout(5000);
                output.accept("TCP: 成功\n");
                stage = "TLS";
                // Retain the real hostname, SNI, default CA trust and HTTPS identity checks.
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (SSLSocket secure = (SSLSocket) factory.createSocket(plain, HOST, 443, true)) {
                    secure.setSoTimeout(5000);
                    SSLParameters parameters = secure.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    parameters.setServerNames(Collections.singletonList(new SNIHostName(HOST)));
                    if (tls12) parameters.setProtocols(new String[]{"TLSv1.2"});
                    secure.setSSLParameters(parameters);
                    secure.startHandshake();
                    output.accept("TLS: 成功 " + secure.getSession().getProtocol() + "\n");
                    stage = "HTTP";
                    String request = "GET /api/config/basic HTTP/1.1\r\nHost: " + HOST
                            + "\r\nUser-Agent: GoFilm-TV-Diagnostic/0.6.2\r\nConnection: close\r\n\r\n";
                    secure.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                    secure.getOutputStream().flush();
                    InputStream input = secure.getInputStream();
                    StringBuilder line = new StringBuilder();
                    for (int i = 0; i < 256; i++) {
                        int value = input.read();
                        if (value == -1 || value == '\n') break;
                        if (value >= 32 && value < 127) line.append((char) value);
                    }
                    if (!line.toString().startsWith("HTTP/"))
                        throw new IOException("未收到有效 HTTP 状态行");
                    output.accept("HTTP: " + line + "\n");
                }
            } finally { deadline.cancel(false); }
        } catch (Exception failure) {
            if (!closed) output.accept(stage + ": 失败 " + describe(failure) + "\n");
        } finally {
            active = null;
            timer.shutdownNow();
        }
    }

    private static String describe(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; failure != null && i < 3; i++) {
            if (i > 0) result.append(" → ");
            result.append(failure.getClass().getSimpleName());
            String message = failure.getMessage();
            if (message != null) {
                message = message.replaceAll("https?://[^\\s]+", "[网址]")
                        .replaceAll("[\\r\\n\\t]+", " ");
                result.append(": ").append(message.length() > 180 ? message.substring(0, 180) : message);
            }
            Throwable next = failure.getCause();
            if (next == failure) break;
            failure = next;
        }
        return result.toString();
    }

    @Override public void close() {
        closed = true;
        FutureTask<InetAddress[]> lookup = dns;
        if (lookup != null) lookup.cancel(true);
        Socket socket = active;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
