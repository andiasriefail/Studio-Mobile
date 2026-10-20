package com.example.studiomobile.build;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Looper;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asks "Download X?" before Python, the Dart SDK or the NDK is downloaded. Called from a background thread right before the download
 * starts; it shows one plain dialog on the activity that is on screen and waits for the answer.
 *
 * <p>Plain JVM unit tests never attach this class, so there it simply allows the download. On a phone it declines when no screen is
 * showing (nobody can answer) or when nobody answers for five minutes, and the person can run again from the app.
 */
public final class DownloadPrompt {
    private static final long WAIT_MINUTES = 5;
    private static volatile boolean attached;
    private static volatile WeakReference<Activity> visible = new WeakReference<>(null);

    private DownloadPrompt() { }

    /** Called once from the Application class; follows which activity is on screen. */
    public static void attach(Application application) {
        attached = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) { visible = new WeakReference<>(activity); }
            @Override public void onActivityPaused(Activity activity) { if (visible.get() == activity) visible = new WeakReference<>(null); }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    /** @param what for example "Python", "Dart SDK" or "Android NDK". @return true when the download may start. */
    public static boolean allow(String what) {
        if (!attached) return true;
        if (Looper.myLooper() == Looper.getMainLooper()) return true; // would block the screen that has to show the dialog
        Activity activity = visible.get();
        if (activity == null || activity.isFinishing()) return false;
        CountDownLatch answered = new CountDownLatch(1);
        AtomicBoolean yes = new AtomicBoolean(false);
        activity.runOnUiThread(() -> {
            try {
                new MaterialAlertDialogBuilder(activity)
                    .setTitle("Download " + what + "?")
                    .setMessage(what + " is not installed yet. Download it now?")
                    .setCancelable(false)
                    .setNegativeButton("Cancel", (dialog, which) -> answered.countDown())
                    .setPositiveButton("Download", (dialog, which) -> { yes.set(true); answered.countDown(); })
                    .show();
            } catch (RuntimeException windowGone) {
                answered.countDown();
            }
        });
        try {
            return answered.await(WAIT_MINUTES, TimeUnit.MINUTES) && yes.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
