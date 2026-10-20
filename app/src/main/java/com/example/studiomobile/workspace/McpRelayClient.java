package com.example.studiomobile.workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connects the phone to a user-owned MCP relay and answers the requests the relay forwards.
 *
 * <p>A phone has no public address, so the phone calls out instead: it long-polls
 * {@code GET <relay>/phone/poll}. When an AI sends an MCP request to {@code <relay>/mcp}, the relay hands the
 * JSON-RPC text to the next poll (header {@code X-Relay-Id} carries the correlation id, the body is the raw
 * message). The phone runs it through a {@link Handler} and sends the answer to
 * {@code POST <relay>/phone/reply/<id>}; an empty body means "no answer is due" (a notification).
 * Only the JDK is used, so this class is testable without Android.
 */
public final class McpRelayClient {

    public enum State { STOPPED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    /** Receives state changes on an arbitrary thread. */
    public interface Listener { void onStateChanged(State state, String detail); }

    /** Answers one MCP request body. Returns the response text, or null when none is due. */
    public interface Handler { String handle(String body); }

    private static final int POLL_WAIT_SECONDS = 25;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int POLL_READ_TIMEOUT_MS = (POLL_WAIT_SECONDS + 15) * 1000;
    private static final int REPLY_TIMEOUT_MS = 30_000;
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final long FIRST_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final int PARALLEL_REQUESTS = 4;
    private static final String INTERNAL_ERROR = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error on the phone.\"}}";

    private final String base;
    private final String token;
    private final Handler handler;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger handled = new AtomicInteger();

    private volatile Thread pollThread;
    private volatile HttpURLConnection activePoll;
    private volatile ExecutorService workers;
    private State state = State.STOPPED;
    private String detail = "";

    /**
     * @param base  normalized relay address from {@link McpRelayUrl#normalize(String)}
     * @param token optional access token, sent as {@code Authorization: Bearer}; null or empty to send none
     */
    public McpRelayClient(String base, String token, Handler handler, Listener listener) {
        this.base = base;
        this.token = token == null ? "" : token.trim();
        this.handler = handler;
        this.listener = listener;
    }

    public synchronized State getState() { return state; }
    public synchronized String getDetail() { return detail; }
    public int getHandledCount() { return handled.get(); }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        workers = Executors.newFixedThreadPool(PARALLEL_REQUESTS, runnable -> {
            Thread thread = new Thread(runnable, "mcp-relay-worker");
            thread.setDaemon(true);
            return thread;
        });
        setState(State.CONNECTING, "");
        Thread thread = new Thread(this::pollLoop, "mcp-relay-poll");
        thread.setDaemon(true);
        pollThread = thread;
        thread.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        Thread thread = pollThread;
        if (thread != null) thread.interrupt();
        HttpURLConnection poll = activePoll;
        if (poll != null) poll.disconnect();
        ExecutorService pool = workers;
        if (pool != null) pool.shutdownNow();
        setState(State.STOPPED, "");
    }

    // ------------------------------------------------------------------ polling

    private void pollLoop() {
        long backoff = FIRST_BACKOFF_MS;
        while (running.get()) {
            try {
                Pending next = poll();
                backoff = FIRST_BACKOFF_MS;
                if (!running.get()) break;
                if (getState() != State.CONNECTED) setState(State.CONNECTED, handledText());
                if (next != null) dispatch(next);
            } catch (FatalException fatal) {
                if (running.compareAndSet(true, false)) {
                    ExecutorService pool = workers;
                    if (pool != null) pool.shutdownNow();
                    setState(State.FAILED, fatal.getMessage());
                }
                return;
            } catch (IOException error) {
                if (!running.get()) break;
                setState(State.RECONNECTING, describe(error) + " Retrying in " + (backoff / 1000) + " s.");
                try { Thread.sleep(backoff); } catch (InterruptedException interrupted) { break; }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    private Pending poll() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/phone/poll?wait=" + POLL_WAIT_SECONDS).openConnection();
        activePoll = connection;
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(POLL_READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "StudioMobile-Relay/1");
            authorize(connection);
            int code = connection.getResponseCode();
            if (code == 204) { drain(connection.getInputStream()); return null; }
            if (code == 200) {
                String id = connection.getHeaderField("X-Relay-Id");
                String body = readText(connection.getInputStream());
                if (id == null || id.isEmpty()) throw new IOException("The relay sent a request without an id.");
                return new Pending(id, body);
            }
            if (code == 401) throw new FatalException(token.isEmpty()
                ? "The relay wants an access token (HTTP 401). Enter the phone token."
                : "The relay refused the phone token (HTTP 401). Check the token.");
            if (code == 403) throw new FatalException("The relay refused this address (HTTP 403). Check the secret in the URL.");
            if (code == 404) throw new FatalException("The relay does not know this address (HTTP 404). Check the URL, including the secret part.");
            throw new IOException("The relay answered HTTP " + code + ".");
        } finally {
            activePoll = null;
            connection.disconnect();
        }
    }

    private void dispatch(Pending pending) {
        ExecutorService pool = workers;
        if (pool == null) return;
        try {
            pool.execute(() -> {
                String response;
                try { response = handler.handle(pending.body); }
                catch (RuntimeException error) { response = INTERNAL_ERROR; }
                sendReply(pending.id, response);
                handled.incrementAndGet();
                if (running.get() && getState() == State.CONNECTED) setState(State.CONNECTED, handledText());
            });
        } catch (RuntimeException rejected) {
            // Pool already shut down: the client is stopping.
        }
    }

    private void sendReply(String id, String response) {
        byte[] payload = response == null ? new byte[0] : response.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 1; attempt <= 3 && running.get(); attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(base + "/phone/reply/" + URLEncoder.encode(id, "UTF-8")).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(REPLY_TIMEOUT_MS);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("User-Agent", "StudioMobile-Relay/1");
                authorize(connection);
                try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
                int code = connection.getResponseCode();
                drain(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
                // 404 means the AI already gave up on this request; retrying cannot help.
                if (code < 500) return;
            } catch (IOException ignored) {
                // fall through to retry
            } finally {
                if (connection != null) connection.disconnect();
            }
            try { Thread.sleep(500L * attempt); } catch (InterruptedException interrupted) { return; }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void authorize(HttpURLConnection connection) {
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
    }

    private String handledText() {
        int count = handled.get();
        return count == 0 ? "Waiting for your AI." : "Requests answered: " + count;
    }

    private synchronized void setState(State next, String text) {
        if (state == next && detail.equals(text)) return;
        state = next;
        detail = text;
        if (listener != null) listener.onStateChanged(next, text);
    }

    private static String describe(IOException error) {
        String message = error.getMessage();
        if (error instanceof java.net.UnknownHostException) return "Cannot find the relay host.";
        if (error instanceof java.net.SocketTimeoutException) return "The relay did not answer.";
        return message == null || message.isEmpty() ? "Connection lost." : message;
    }

    private static String readText(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BODY_BYTES) throw new IOException("The relay sent a request larger than 8 MB.");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void drain(InputStream in) {
        if (in == null) return;
        try (InputStream stream = in) {
            byte[] buffer = new byte[1024];
            while (stream.read(buffer) != -1) { /* discard */ }
        } catch (IOException ignored) { }
    }

    private static final class Pending {
        final String id; final String body;
        Pending(String id, String body) { this.id = id; this.body = body; }
    }

    /** An answer that retrying will not change, such as a wrong URL. */
    private static final class FatalException extends IOException {
        FatalException(String message) { super(message); }
    }
}
