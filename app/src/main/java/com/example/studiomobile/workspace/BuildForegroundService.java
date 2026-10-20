package com.example.studiomobile.workspace;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.studiomobile.MainActivity;
import com.example.studiomobile.R;

/**
 * Keeps a project build running when the app is left or the screen turns off.
 *
 * <p>The build itself lives in {@link ProjectBuildService} on its own worker thread; this service only makes Android treat the process as
 * "doing something the user asked for": an ongoing notification that shows the current step, a progress bar, the elapsed time and a Stop
 * button, plus a partial wake lock while the build runs. When the build ends the ongoing notification is replaced by a result notification
 * and the service stops itself. Nothing here starts, changes or depends on the Activity.
 */
public final class BuildForegroundService extends Service {
    private static final String ACTION_START = "com.example.studiomobile.build.START";
    private static final String ACTION_CANCEL = "com.example.studiomobile.build.CANCEL";
    private static final String CHANNEL_PROGRESS = "build_progress";
    private static final String CHANNEL_RESULT = "build_result";
    private static final int NOTIFICATION_PROGRESS = 4121;
    private static final int NOTIFICATION_RESULT = 4122;
    /** The last line of the log (for example the library being downloaded) is refreshed this often. */
    private static final long TICK_MILLIS = 2000;
    /**
     * Safety net: a wake lock that is never released would drain the battery. The lock is renewed on every progress tick, so it
     * lasts as long as the build keeps running, and expires by itself 30 minutes after the service stops ticking.
     */
    private static final long WAKE_LOCK_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    /** Starts the service for a build that just began. Never throws: if Android refuses, the build still runs, only without protection. */
    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, BuildForegroundService.class).setAction(ACTION_START));
        } catch (RuntimeException refused) {
            // For example ForegroundServiceStartNotAllowedException when the app is in the background; the build is not affected.
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private ProjectBuildService build;
    private boolean finished;
    private int lastStartId;

    private final ProjectBuildService.Listener listener = new ProjectBuildService.Listener() {
        @Override public void onLogAppended(String line) { handler.post(refresh); }
        @Override public void onStateChanged(ProjectBuildService.State state) {
            if (state != ProjectBuildService.State.RUNNING) handler.post(() -> finish(state));
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            handler.removeCallbacks(this);
            if (finished || build == null || !build.isBuilding()) return;
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_PROGRESS, progressNotification());
            renewWakeLock();
            handler.postDelayed(this, TICK_MILLIS);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        build = ProjectBuildService.get(this);
        lastStartId = startId;
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            build.cancel();
            if (!build.isBuilding()) stopSelf();
            return START_NOT_STICKY;
        }

        // Android requires startForeground soon after startForegroundService, even if the build has already ended.
        goForeground();
        if (!build.isBuilding()) {                  // restarted by the system after the process died, or the build was very short
            finish(build.getState());
            return START_NOT_STICKY;
        }
        finished = false;
        acquireWakeLock();
        build.addListener(listener);
        handler.post(refresh);
        return START_NOT_STICKY;                    // a killed process has lost its build; there is nothing to restart
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (build != null) build.removeListener(listener);
        releaseWakeLock();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ ending

    private void finish(ProjectBuildService.State state) {
        if (finished || (build != null && build.isBuilding())) return;   // already handled, or a stale message about an earlier build
        finished = true;
        handler.removeCallbacksAndMessages(null);
        if (build != null) build.removeListener(listener);
        releaseWakeLock();
        stopForeground(true);                       // removes the ongoing notification
        if (state != ProjectBuildService.State.IDLE && state != ProjectBuildService.State.RUNNING) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_RESULT, resultNotification(state));
        }
        stopSelf(lastStartId);                      // only if no newer start command is waiting
    }

    private Notification resultNotification(ProjectBuildService.State state) {
        String title, text;
        switch (state) {
            case SUCCESS:   title = "Build successful"; text = build.getLastMessage().isEmpty() ? build.getProjectName() : build.getProjectName() + " · " + shorten(build.getLastMessage(), 120); break;
            case CANCELLED: title = "Build cancelled"; text = build.getProjectName(); break;
            default:        title = "Build failed"; text = shorten(build.getLastMessage().isEmpty() ? build.getProjectName() : build.getLastMessage(), 160); break;
        }
        return new NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_planet)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .build();
    }

    // ------------------------------------------------------------------ ongoing notification

    private void goForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel progress = new NotificationChannel(CHANNEL_PROGRESS, "Build progress", NotificationManager.IMPORTANCE_LOW);
            progress.setShowBadge(false);
            manager.createNotificationChannel(progress);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_RESULT, "Build result", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Notification notification = progressNotification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_PROGRESS, notification);
    }

    private Notification progressNotification() {
        int steps = build == null ? 1 : build.getProgressStepCount();
        int reached = build == null ? -1 : build.getProgressStep();
        String step = build == null ? BuildProgressTranslator.PREPARING : build.getProgressLabel();
        String name = build == null || build.getProjectName().isEmpty() ? "project" : build.getProjectName();
        String detail = build == null ? "" : shorten(build.getLiveStatus(), 160);
        long startedAt = build == null || build.getStartedAtMillis() == 0 ? System.currentTimeMillis() : build.getStartedAtMillis();

        int immutable = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, BuildForegroundService.class).setAction(ACTION_CANCEL), immutable);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_planet)
            .setContentTitle("Building " + name)
            .setContentText(step)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(detail.isEmpty() ? step : step + "\n" + detail))
            .setContentIntent(openApp())
            .addAction(0, "Stop build", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(startedAt)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        if (reached < 0) builder.setProgress(0, 0, true);          // nothing reported yet: moving bar without a number
        else builder.setProgress(steps, Math.min(steps, reached + 1), false);
        return builder.build();
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static String shorten(String text, int max) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').trim();
        return flat.length() > max ? flat.substring(0, max - 1) + "…" : flat;
    }

    // ------------------------------------------------------------------ wake lock

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power == null) return;
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "studiomobile:build");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS);
    }

    /** Pushes the wake lock's expiry forward; called while the build is still running. */
    private void renewWakeLock() {
        PowerManager.WakeLock lock = wakeLock;
        if (lock != null && lock.isHeld()) lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }
}
