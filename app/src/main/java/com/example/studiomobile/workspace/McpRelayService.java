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
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.studiomobile.MainActivity;
import com.example.studiomobile.R;
import com.example.studiomobile.build.stages.ApkSigningStage;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the phone connected to the user's MCP relay while the app is in the background.
 *
 * <p>Android stops idle network use of background apps, so the connection lives in a foreground service
 * with an ongoing notification (and a partial wake lock while connected). The screen only starts and stops it
 * and reads the static state below; the work itself never depends on the Activity.
 */
public final class McpRelayService extends Service {

    /** Called on an arbitrary thread whenever the connection state changes. */
    public interface Listener { void onRelayChanged(); }

    private static final String ACTION_START = "com.example.studiomobile.mcp.START";
    private static final String ACTION_STOP = "com.example.studiomobile.mcp.STOP";
    private static final String CHANNEL_ID = "mcp_relay";
    private static final int NOTIFICATION_ID = 4107;

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean running;
    private static volatile McpRelayClient.State currentState = McpRelayClient.State.STOPPED;
    private static volatile String currentDetail = "";

    public static void start(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, McpRelayService.class).setAction(ACTION_START));
    }

    public static void stop(Context context) {
        new McpSettings(context).setRelayEnabled(false);
        context.stopService(new Intent(context, McpRelayService.class));
    }

    /** True while the service is running, including while it retries after a lost connection. */
    public static boolean isRunning() { return running; }
    public static McpRelayClient.State getState() { return currentState; }
    public static String getDetail() { return currentDetail; }
    public static void addListener(Listener listener) { LISTENERS.addIfAbsent(listener); }
    public static void removeListener(Listener listener) { LISTENERS.remove(listener); }

    private static void publish(McpRelayClient.State state, String detail) {
        currentState = state;
        currentDetail = detail == null ? "" : detail;
        for (Listener listener : LISTENERS) listener.onRelayChanged();
    }

    private final AtomicBoolean toolchainReady = new AtomicBoolean(false);
    /** Same blocked path twice (an attempt to leave the project) reconnects the relay session. Lives as long as the service. */
    private final PathViolationGuard pathGuard = new PathViolationGuard(this::reconnectAfterViolation);
    private McpRelayClient client;
    private PowerManager.WakeLock wakeLock;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            new McpSettings(this).setRelayEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Android requires startForeground soon after startForegroundService, even if we stop right away.
        goForeground(getString(R.string.mcp_status_connecting));

        McpSettings settings = new McpSettings(this);
        boolean restartedBySystem = intent == null;
        if (restartedBySystem && !settings.isRelayEnabled()) { stopSelf(); return START_NOT_STICKY; }

        String base = McpRelayUrl.normalize(settings.getRelayUrl());
        if (base == null) {
            settings.setRelayEnabled(false);
            publish(McpRelayClient.State.FAILED, getString(R.string.mcp_relay_url_invalid));
            stopSelf();
            return START_NOT_STICKY;
        }

        settings.setRelayEnabled(true);
        connect(settings, base);
        return START_STICKY;
    }

    /** (Re)starts the connection to the relay URL. */
    private synchronized void connect(McpSettings settings, String base) {
        if (client != null) client.stop();
        acquireWakeLock();
        client = new McpRelayClient(base, settings.getRelayToken(), this::answer, this::onClientState);
        client.start();
    }

    /**
     * Security reaction: drop the current relay session and connect again. Runs after a short delay so the
     * "blocked" answer that triggered it is still delivered to the AI first.
     */
    private void reconnectAfterViolation() {
        Thread thread = new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException interrupted) { return; }
            McpSettings settings = new McpSettings(this);
            if (!running || !settings.isRelayEnabled()) return;
            String base = McpRelayUrl.normalize(settings.getRelayUrl());
            if (base != null) connect(settings, base);
        }, "mcp-security-reconnect");
        thread.setDaemon(true);
        thread.start();
    }

    @Override public void onDestroy() {
        if (client != null) client.stop();
        client = null;
        releaseWakeLock();
        running = false;
        if (currentState != McpRelayClient.State.FAILED) publish(McpRelayClient.State.STOPPED, "");
        else for (Listener listener : LISTENERS) listener.onRelayChanged();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ requests from the AI

    /** Builds the handler per request so read-only mode changes apply immediately. */
    private String answer(String body) {
        ensureToolchain();
        File files = getFilesDir();
        File projects = StudioStorage.projectsDir(this);
        McpSettings settings = new McpSettings(this);
        McpToolRegistry registry = new McpToolRegistry(new ProjectFileOperations(projects), settings, ProjectBuildService.get(this), new File(files, "build-tools"), pathGuard, new DebugModeSettings(this));
        // check_syntax and get_toolchain_info need the folder that holds the bundled Python and Dart executables.
        registry.setNativeLibraryDirectory(new File(getApplicationInfo().nativeLibraryDir));
        return new McpRequestHandler(registry, settings, versionName()).handle(body);
    }

    /** The screen prepares the toolchain at launch; do the same when the service runs without the screen. */
    private void ensureToolchain() {
        if (!toolchainReady.compareAndSet(false, true)) return;
        try {
            com.example.studiomobile.build.LocalLibsInstaller.install(this);
            ApkSigningStage.ensureDebugKeyStore(new File(getFilesDir(), "build-tools"), null);
        } catch (Exception ignored) {
            // The build reports missing tools itself.
        }
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "0.1.0"; }
    }

    private void onClientState(McpRelayClient.State state, String detail) {
        publish(state, detail);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && state != McpRelayClient.State.STOPPED) manager.notify(NOTIFICATION_ID, buildNotification(notificationText(state, detail)));
        if (state == McpRelayClient.State.FAILED) {
            new McpSettings(this).setRelayEnabled(false);
            stopSelf();
        }
    }

    // ------------------------------------------------------------------ notification and wake lock

    private String notificationText(McpRelayClient.State state, String detail) {
        switch (state) {
            case CONNECTED: return getString(R.string.mcp_status_connected);
            case RECONNECTING: return getString(R.string.mcp_status_reconnecting);
            case FAILED: return getString(R.string.mcp_status_failed);
            default: return getString(R.string.mcp_status_connecting);
        }
    }

    private void goForeground(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, getString(R.string.mcp_notification_channel), NotificationManager.IMPORTANCE_LOW));
        }
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String text) {
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), immutable | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, McpRelayService.class).setAction(ACTION_STOP), immutable | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_planet)
            .setContentTitle(getString(R.string.mcp_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.mcp_disconnect), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power == null) return;
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "studiomobile:mcp-relay");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }
}
