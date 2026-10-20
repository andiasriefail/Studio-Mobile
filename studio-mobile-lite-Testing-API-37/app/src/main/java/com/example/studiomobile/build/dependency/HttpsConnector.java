package com.example.studiomobile.build.dependency;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A small HTTPS GET client that finds its servers with {@link ResilientDns} (Google DNS, then Cloudflare DNS) instead of the phone's own DNS.
 *
 * <p>It connects to the IP address it was given, but the TLS handshake is made for the real host name: the host name is sent as SNI and the
 * server certificate must be valid for it (the handshake itself checks it, "HTTPS" endpoint identification, and on Android the platform's
 * host name verifier checks it once more). A certificate that is only good for the IP, or for another name, is refused. When no DNS answer
 * exists at all, {@link ResilientDns.DnsFailedException} is thrown and the caller stops.
 *
 * <p>Only what library downloads need is implemented: GET over HTTP/1.1 with extra request headers (Range), Content-Length and chunked bodies,
 * and keep-alive, so the many small POM/checksum requests of one build share a few connections. Redirects are for the caller to follow.
 */
public final class HttpsConnector {
    private HttpsConnector() { }

    private static final boolean ANDROID = "The Android Project".equals(System.getProperty("java.vendor"));
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final long IDLE_MILLIS = 15_000;
    private static final int IDLE_PER_HOST = 2;
    private static final Map<String, Deque<Connection>> IDLE = new HashMap<>();

    /** One response. The body is read through {@link #body()}; close() hands the connection back for reuse when the body was read completely. */
    public static final class Response implements Closeable {
        private final int status;
        private final Map<String, String> headers;
        private final Body body;
        private final Connection connection;
        private boolean closed;

        Response(int status, Map<String, String> headers, Body body, Connection connection) { this.status = status; this.headers = headers; this.body = body; this.connection = connection; }

        public int status() { return status; }
        /** Header value (names are case-insensitive), or null. */
        public String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
        /** Length announced by the server, or -1 when it did not say. */
        public long contentLength() { try { String v = header("content-length"); return v == null ? -1 : Long.parseLong(v.trim()); } catch (NumberFormatException bad) { return -1; } }
        public InputStream body() { return body; }

        /** Stops now (for example Cancel): the connection is dropped, never reused. Safe to call from another thread. */
        public void abort() { connection.close(); }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (body.complete && connection.reusable) release(connection); else connection.close();
        }
    }

    /** Sends a GET. The caller reads the response and must close() it. */
    public static Response get(URL url, int connectTimeoutMillis, int readTimeoutMillis, Map<String, String> headers) throws IOException {
        return get(ResilientDns.standard(), url, connectTimeoutMillis, readTimeoutMillis, headers);
    }

    static Response get(ResilientDns dns, URL url, int connectTimeoutMillis, int readTimeoutMillis, Map<String, String> headers) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("Refusing non-HTTPS URL: " + url);
        String host = url.getHost();
        int port = url.getPort() == -1 ? 443 : url.getPort();
        String key = host.toLowerCase(Locale.ROOT) + ":" + port;
        String request = requestText(url, host, port, headers);

        // A connection kept from an earlier request may have been closed by the server meanwhile; GET can simply be repeated on a fresh one.
        Connection idle;
        while ((idle = takeIdle(key)) != null) {
            try { idle.socket.setSoTimeout(readTimeoutMillis); return send(idle, request); }
            catch (IOException stale) { idle.close(); }
        }

        List<InetAddress> addresses = dns.resolve(host);     // DnsFailedException = stop, nothing else is tried
        IOException lastFailure = null;
        for (InetAddress address : addresses.subList(0, Math.min(addresses.size(), 3))) {
            Connection connection = null;
            try {
                connection = connect(address, host, port, key, connectTimeoutMillis, readTimeoutMillis);
                return send(connection, request);
            } catch (IOException failure) {
                if (connection != null) connection.close();
                lastFailure = failure;
                if (failure instanceof SSLPeerUnverifiedException || failure instanceof javax.net.ssl.SSLHandshakeException) break;   // another address will not fix a wrong certificate
            }
        }
        throw new IOException("Could not connect to " + host + " (" + addresses + "): " + (lastFailure == null ? "no address" : lastFailure.getMessage()), lastFailure);
    }

    // ------------------------------------------------------------------ connection

    private static final class Connection {
        final Socket socket; final BufferedInputStream in; final OutputStream out; final String key;
        boolean reusable = true; long idleSince;
        Connection(Socket socket, String key) throws IOException {
            this.socket = socket; this.key = key; this.in = new BufferedInputStream(socket.getInputStream(), 16 * 1024); this.out = socket.getOutputStream();
        }
        void close() { try { socket.close(); } catch (IOException ignored) { } }
    }

    private static Connection connect(InetAddress address, String host, int port, String key, int connectTimeout, int readTimeout) throws IOException {
        Socket plain = new Socket();
        SSLSocket tls = null;
        try {
            plain.connect(new InetSocketAddress(address, port), connectTimeout);
            plain.setSoTimeout(readTimeout);
            plain.setTcpNoDelay(true);
            // The socket was opened to the IP address, but TLS is told the real host name: that is what SNI carries and what the certificate is checked against.
            tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, host, port, true);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            if (ANDROID && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.getSession())) throw new SSLPeerUnverifiedException("Certificate is not valid for " + host);
            return new Connection(tls, key);
        } catch (IOException | RuntimeException failure) {
            try { (tls != null ? tls : plain).close(); } catch (IOException ignored) { }
            throw failure instanceof IOException ? (IOException) failure : new IOException(failure);
        }
    }

    private static Connection takeIdle(String key) {
        synchronized (IDLE) {
            Deque<Connection> queue = IDLE.get(key);
            long now = System.currentTimeMillis();
            while (queue != null && !queue.isEmpty()) {
                Connection connection = queue.pollFirst();
                if (now - connection.idleSince <= IDLE_MILLIS && !connection.socket.isClosed()) return connection;
                connection.close();
            }
            return null;
        }
    }

    private static void release(Connection connection) {
        connection.idleSince = System.currentTimeMillis();
        synchronized (IDLE) {
            Deque<Connection> queue = IDLE.get(connection.key);
            if (queue == null) { queue = new ArrayDeque<>(); IDLE.put(connection.key, queue); }
            queue.addFirst(connection);
            while (queue.size() > IDLE_PER_HOST) queue.pollLast().close();
        }
    }

    // ------------------------------------------------------------------ request and response

    private static String requestText(URL url, String host, int port, Map<String, String> headers) {
        String file = url.getFile();
        StringBuilder request = new StringBuilder("GET ").append(file.isEmpty() ? "/" : file).append(" HTTP/1.1\r\n")
            .append("Host: ").append(port == 443 ? host : host + ":" + port).append("\r\n")
            .append("User-Agent: StudioMobile\r\n")
            .append("Accept-Encoding: identity\r\n")
            .append("Connection: keep-alive\r\n");
        if (headers != null) for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey().indexOf('\r') >= 0 || header.getKey().indexOf('\n') >= 0 || header.getValue().indexOf('\r') >= 0 || header.getValue().indexOf('\n') >= 0) throw new IllegalArgumentException("Illegal header");
            request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        return request.append("\r\n").toString();
    }

    private static Response send(Connection connection, String request) throws IOException {
        connection.out.write(request.getBytes(StandardCharsets.ISO_8859_1));
        connection.out.flush();
        String statusLine = readLine(connection.in, MAX_HEADER_BYTES);
        if (statusLine == null) throw new IOException("Connection closed without an answer");
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) throw new IOException("Unreadable answer: " + statusLine);
        int status;
        try { status = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException bad) { throw new IOException("Unreadable answer: " + statusLine); }
        boolean http10 = parts[0].equals("HTTP/1.0");

        Map<String, String> headers = new LinkedHashMap<>();
        int budget = MAX_HEADER_BYTES;
        while (true) {
            String line = readLine(connection.in, budget);
            if (line == null) throw new IOException("Connection closed inside the headers");
            if (line.isEmpty()) break;
            budget -= line.length() + 2;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = line.substring(colon + 1).trim();
            String earlier = headers.get(name);
            headers.put(name, earlier == null ? value : earlier + ", " + value);
        }
        String connectionHeader = headers.get("connection");
        connection.reusable = !http10 && !(connectionHeader != null && connectionHeader.toLowerCase(Locale.ROOT).contains("close"));
        if (status == 100 || (status >= 101 && status < 200)) throw new IOException("Unexpected interim answer " + status);

        Body body;
        String transfer = headers.get("transfer-encoding");
        String length = headers.get("content-length");
        if (status == 204 || status == 304) body = Body.empty();
        else if (transfer != null && transfer.toLowerCase(Locale.ROOT).contains("chunked")) body = Body.chunked(connection.in);
        else if (length != null) {
            long n;
            try { n = Long.parseLong(length.trim()); } catch (NumberFormatException bad) { throw new IOException("Bad Content-Length: " + length); }
            body = n == 0 ? Body.empty() : Body.length(connection.in, n);
        } else { body = Body.untilClose(connection.in); connection.reusable = false; }
        return new Response(status, headers, body, connection);
    }

    private static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') { byte[] bytes = line.toByteArray(); int n = bytes.length; if (n > 0 && bytes[n - 1] == '\r') n--; return new String(bytes, 0, n, StandardCharsets.ISO_8859_1); }
            line.write(c);
            if (line.size() > max) throw new IOException("Header too large");
        }
        return line.size() == 0 ? null : new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------ bodies

    /** The response body. A body that ends before the announced length is an error (so a broken download is never mistaken for a short file). */
    static final class Body extends InputStream {
        private static final int LENGTH = 0, CHUNKED = 1, UNTIL_CLOSE = 2;
        private final InputStream in; private final int mode;
        private long remaining;          // LENGTH: bytes left; CHUNKED: bytes left in the current chunk
        boolean complete;                // the whole body has been read

        private Body(InputStream in, int mode, long remaining) { this.in = in; this.mode = mode; this.remaining = remaining; }
        static Body empty() { Body b = new Body(null, LENGTH, 0); b.complete = true; return b; }
        static Body length(InputStream in, long n) { return new Body(in, LENGTH, n); }
        static Body chunked(InputStream in) { return new Body(in, CHUNKED, 0); }
        static Body untilClose(InputStream in) { return new Body(in, UNTIL_CLOSE, 0); }

        @Override public int read() throws IOException { byte[] one = new byte[1]; int n = read(one, 0, 1); return n == -1 ? -1 : one[0] & 0xff; }

        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            if (count == 0) return 0;
            if (complete) return -1;
            switch (mode) {
                case LENGTH: {
                    int n = in.read(buffer, offset, (int) Math.min(count, remaining));
                    if (n == -1) throw new IOException("Connection closed before the whole file arrived");
                    remaining -= n;
                    if (remaining == 0) complete = true;
                    return n;
                }
                case CHUNKED: {
                    if (remaining == 0) {
                        String sizeLine = readLine(in, 1024);
                        if (sizeLine == null) throw new IOException("Connection closed inside a chunked body");
                        int semicolon = sizeLine.indexOf(';');
                        try { remaining = Long.parseLong((semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim(), 16); }
                        catch (NumberFormatException bad) { throw new IOException("Bad chunk size: " + sizeLine); }
                        if (remaining == 0) {                         // last chunk: skip trailers up to the blank line
                            String trailer;
                            while ((trailer = readLine(in, MAX_HEADER_BYTES)) != null && !trailer.isEmpty()) { /* ignored */ }
                            complete = true;
                            return -1;
                        }
                    }
                    int n = in.read(buffer, offset, (int) Math.min(count, remaining));
                    if (n == -1) throw new IOException("Connection closed inside a chunk");
                    remaining -= n;
                    if (remaining == 0) { String end = readLine(in, 16); if (end == null || !end.isEmpty()) throw new IOException("Malformed chunk end"); }
                    return n;
                }
                default: {
                    int n = in.read(buffer, offset, count);
                    if (n == -1) complete = true;
                    return n;
                }
            }
        }

        /** An announced length that was read in full counts as complete even before a final read() returned -1. */
        @Override public int available() throws IOException { return complete || in == null ? 0 : in.available(); }
    }
}
