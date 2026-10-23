package com.example.studiomobile.workspace;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import java.io.File;

/**
 * Where Studio Mobile keeps the user's files, in shared storage so they survive an uninstall and can be reached from a file manager:
 *
 * <pre>
 * Studio Mobile/
 *   My Project/          every project
 *   My Application/      APKs saved with "Save APK"
 *   Export AAB/          App Bundles exported for Google Play
 *   Icon Google Play Console/<project name>/   the 512x512 store icon of each project (ic_launcher-playstore.png)
 *   Local/local_libs/    downloaded libraries, reused by later builds
 *   Build Error Logs/    one build error log per app (<app name>-error-logs.txt)
 * </pre>
 *
 * Shared storage needs "All files access" (Android 11+) or the storage permission (Android 8-9). Until it is granted, and on
 * Android 10 where neither is available to an app that targets Android 11+, the same layout is created inside the app's own
 * storage so the app keeps working.
 */
public final class StudioStorage {
    public static final String ROOT_NAME = "Studio Mobile";
    public static final String PROJECTS = "My Project";
    public static final String APKS = "My Application";
    public static final String LOCAL_LIBS = "Local/local_libs";
    public static final String EXPORT_AAB = "Export AAB";
    /** One sub-folder per project holding the 512x512 icon that Google Play Console asks for (it is not part of the APK). */
    public static final String PLAY_ICONS = "Icon Google Play Console";
    public static final String PLAY_ICON_FILE = "ic_launcher-playstore.png";
    public static final String ERROR_LOGS = "Build Error Logs";
    /** Crash and freeze reports of Studio Mobile itself. Kept apart from ERROR_LOGS, which only holds build error logs. */
    public static final String APP_CRASH_LOGS = "App Crash Logs";
    public static final int REQUEST_LEGACY_STORAGE = 4101;

    private StudioStorage() { }

    /** True when the shared "Studio Mobile" folder can be used. */
    public static boolean hasAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false;
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Opens the Android screen where the user allows file access. Android 8-9 shows the normal permission prompt instead. */
    public static void requestAccess(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + activity.getPackageName()));
            try { activity.startActivity(intent); }
            catch (Exception unavailable) { activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_LEGACY_STORAGE);
        }
    }

    public static File root(Context context) {
        if (hasAccess(context)) return new File(Environment.getExternalStorageDirectory(), ROOT_NAME);
        File external = context.getExternalFilesDir(null);
        return new File(external != null ? external : context.getFilesDir(), ROOT_NAME);
    }

    public static File projectsDir(Context context) { return dir(root(context), PROJECTS); }
    public static File apkDir(Context context) { return dir(root(context), APKS); }
    /** Studio Mobile/Export AAB, created together with the other folders. */
    public static File exportAabDir(Context context) { return dir(root(context), EXPORT_AAB); }
    /** The Studio Mobile folder itself, created when first needed. */
    public static File rootDir(Context context) { File root = root(context); if (!root.isDirectory()) root.mkdirs(); return root; }
    public static File playIconsDir(Context context) { return dir(root(context), PLAY_ICONS); }
    /** Studio Mobile/Icon Google Play Console/&lt;project name&gt;. Only a path: nothing is created. */
    public static File playIconDir(Context context, String projectName) { return new File(playIconsDir(context), projectName); }
    public static File localLibsDir(Context context) { return dir(root(context), LOCAL_LIBS); }
    public static File errorLogsDir(Context context) { return dir(root(context), ERROR_LOGS); }
    public static File appCrashLogsDir(Context context) { return dir(root(context), APP_CRASH_LOGS); }

    /** Creates the whole folder layout. Safe to call repeatedly. */
    public static void ensureFolders(Context context) {
        projectsDir(context); apkDir(context); exportAabDir(context); localLibsDir(context); errorLogsDir(context); playIconsDir(context);
    }

    private static File dir(File parent, String relative) {
        File dir = new File(parent, relative);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }
}
