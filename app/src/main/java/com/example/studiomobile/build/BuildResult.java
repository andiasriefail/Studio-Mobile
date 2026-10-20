package com.example.studiomobile.build;

import java.io.File;

/** Immutable summary returned when a local build finishes. */
public final class BuildResult {
    public enum Status { SUCCESS, FAILED, CANCELLED }

    private final Status status;
    private final File apkFile;
    private final String message;

    public BuildResult(Status status, File apkFile, String message) {
        if (status == null) throw new IllegalArgumentException("status must not be null");
        this.status = status;
        this.apkFile = apkFile;
        this.message = message == null ? "" : message;
    }

    public Status getStatus() { return status; }
    public File getApkFile() { return apkFile; }
    public String getMessage() { return message; }
    public boolean isSuccessful() { return status == Status.SUCCESS && apkFile != null && apkFile.isFile(); }
}
