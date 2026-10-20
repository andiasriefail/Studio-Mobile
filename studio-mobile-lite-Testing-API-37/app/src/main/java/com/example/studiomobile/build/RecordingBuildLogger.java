package com.example.studiomobile.build;

/** Records build output from the first stage through the final result while forwarding it to the UI. */
public final class RecordingBuildLogger implements BuildLogger {
    private final BuildLogger delegate;
    private final StringBuilder transcript = new StringBuilder();
    public RecordingBuildLogger(BuildLogger delegate) { this.delegate = delegate; }
    private synchronized void append(String level, String message, Throwable cause) {
        transcript.append('[').append(level).append("] ").append(message == null ? "(null)" : message).append('\n');
        if (cause != null) {
            transcript.append("Cause: ");
            transcript.append(cause.getClass().getName()).append(": ").append(cause.getMessage()).append('\n');
            for (Throwable t = cause.getCause(); t != null; t = t.getCause())
                transcript.append("Caused by: ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
    }
    public synchronized String getTranscript() { return transcript.toString(); }
    @Override public void onInfo(String message) { append("INFO", message, null); if (delegate != null) delegate.onInfo(message); }
    @Override public void onWarning(String message) { append("WARN", message, null); if (delegate != null) delegate.onWarning(message); }
    @Override public void onError(String message, Throwable cause) { append("ERROR", message, cause); if (delegate != null) delegate.onError(message, cause); }
}
