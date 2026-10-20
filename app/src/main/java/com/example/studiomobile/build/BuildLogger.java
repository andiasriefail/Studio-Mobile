package com.example.studiomobile.build;

/** Receives build output without coupling the build engine to Android UI classes. */
public interface BuildLogger {
    void onInfo(String message);
    void onWarning(String message);
    void onError(String message, Throwable cause);
}
