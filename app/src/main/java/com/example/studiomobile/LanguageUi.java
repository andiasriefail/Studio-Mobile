package com.example.studiomobile;

import java.util.Locale;

/** All English text that MainActivity shows from Java (toasts, dialogs, log headings, formatted values). */
public final class LanguageUi {
    private LanguageUi() { }

    // Validation and generic messages
    public static final String ENTER_VALID_NAME = "Enter a valid name";
    public static final String NAME_EXISTS = "An item with this name already exists";
    public static final String PROJECT_NAME_REQUIRED = "Enter a project name";
    public static final String PACKAGE_INVALID = "Enter a valid package name, for example com.example.app";
    public static final String PROJECT_EXISTS = "A project with this name already exists";
    public static final String NO_PROJECT = "No project is open";

    // Projects
    public static final String PROJECT_CREATED = "Project created";
    public static final String CREATE_FAILED = "Could not create project: ";
    public static final String IMPORTING = "Importing…";
    public static final String PROJECT_IMPORTED = "Project imported";
    public static final String IMPORT_FAILED = "Import failed: ";
    public static final String RENAMED = "Renamed";
    public static final String RENAME_FAILED = "Rename failed";
    public static final String DELETED = "Deleted";
    public static final String DELETE_FAILED = "Delete failed";
    public static final String DELETE_MESSAGE = "This removes the selected item. This action cannot be undone.";
    public static String deleteTitle(String name) { return "Delete " + name + "?"; }
    public static final String ICON_FAILED = "Could not read the selected image";

    // Structure
    public static final String UPLOAD_FILE = "Upload file";
    public static final String ADD_FILE = "Add file";
    public static final String ADD_FOLDER = "Add folder";
    public static final String NEW_FILE = "New file";
    public static final String NEW_FOLDER = "New folder";
    public static final String FILE_NAME = "File name";
    public static final String FOLDER_NAME = "Folder name";
    public static final String NEW_NAME = "New name";
    public static final String CREATE = "Create";
    public static final String UPLOADED = "Uploaded";
    public static final String UPLOAD_FAILED = "Upload failed: ";
    public static final String CANNOT_EDIT = "This file type cannot be edited";

    // Editor
    public static final String SAVED = "File saved";
    public static final String CHANGED_BY_MCP_DIRTY = "MCP changed this file while you have unsaved edits. Your edits were kept; saving will overwrite MCP's version.";
    public static final String REMOVED_BY_MCP = "MCP removed or moved a file that was open.";
    public static final String UNSAVED_TITLE = "Changes Not Saved";
    public static final String UNSAVED_MESSAGE = "Do you want to save these changes?";
    public static final String UNSAVED_CANCEL = "Batal";
    public static final String UNSAVED_CONFIRM = "Yes & Quit";
    public static final String SAVE_FAILED = "Save failed: ";
    public static final String READ_FAILED = "Could not read file: ";

    // Build
    public static final String BUILD_BUSY = "A build is already running";
    public static final String BATTERY_TITLE = "Keep builds running with the screen off?";
    public static final String BATTERY_MESSAGE = "Large projects can take a long time to build. Some phones stop apps that run in the background, and the build is lost. "
        + "Allow Studio Mobile to ignore battery optimization so a build can finish while the screen is off. You can change this any time in the phone's battery settings.";
    public static final String BATTERY_ALLOW = "Allow";
    public static final String BATTERY_LATER = "Not now";
    public static final String BUILD_PREPARING = "Preparing your project…";
    public static final String BUILD_TOOLCHAIN = "Toolchain install:";
    public static final String BUILD_SUCCESS = "BUILD SUCCESS: ";
    public static final String BUILD_COPY_FAILED = "ERROR: Could not prepare the project copy: ";
    public static final String APK_BUILT_TITLE = "APK built successfully";
    public static final String APK_BUILT_MESSAGE = "Save APK keeps a copy in Studio Mobile/My Apk. Install APK only installs it.";
    public static final String SAVE_APK = "Save APK…";
    public static final String CLOSE = "Close";
    public static final String COPY_PIN_LINE = "Copy pin line";
    public static final String COPY_SHA256 = "Copy SHA-256";
    public static final String PIN_LINE_COPIED = "Copied. Check it against the release page before you add it to gradle/checksums.properties.";
    public static final String INSTALL_APK = "Install APK";
    public static final String APK_MISSING = "The APK is no longer available. Build again.";
    public static final String INSTALL_PERMISSION = "Allow Studio Mobile to install apps. The installation continues when you come back.";
    public static final String INSTALL_PERMISSION_DENIED = "Permission not granted, so the APK was not installed. Press Install APK to try again.";
    public static final String INSTALL_FAILED = "Could not open the installer: ";
    public static final String EXPORT_AAB = "Export AAB";
    public static final String AAB_DONE_TITLE = "AAB exported";
    public static String aabDoneMessage(String path) { return "Saved to:\n" + path; }
    public static final String AAB_KEY_TITLE = "Create your own key";
    public static final String AAB_KEY_MESSAGE = "This app still uses the Studio Mobile default key. Google Play needs your own key, so fill in the form first.";
    public static final String AAB_READY_TITLE = "Key changed";
    public static final String AAB_READY_MESSAGE = "Import another key, or export the AAB with the new key.";
    public static final String APK_SAVED = "APK saved to ";
    public static final String APK_SAVE_FAILED = "Could not save APK: ";
    public static final String LOG_COPIED = "Log copied";
    public static final String TOOLCHAIN_TITLE = "Build toolchain";
    public static final String TOOLCHAIN_OK = "All required build tools were found and AAPT2 starts on this device.";
    public static final String TOOLCHAIN_MISSING = "Missing or not working:\n\n- ";

    // Keystore
    public static final String DEBUG_KEY = "Debug key (local)";
    public static final String DEBUG_ALIAS = "studio-mobile-debug";
    public static final String KEY_EXPORTED = "Keystore exported";
    public static final String KEY_EXPORT_FAILED = "Could not export keystore: ";
    public static final String KEY_NO_FILE = "No keystore file yet. Build once to create the debug key.";
    public static final String KEY_IMPORTED = "Keystore imported for this app";
    public static final String KEY_IMPORT_FAILED = "Could not import keystore: ";
    public static final String KEY_CHANGED = "Signing key changed for this app";
    public static final String KEY_IMPORT_TITLE = "Import keystore";
    public static final String KEY_CHANGE_TITLE = "Change key";
    public static final String IMPORT = "Import";
    public static final String ALIAS_REQUIRED = "Enter the alias";
    public static final String PASSWORD_REQUIRED = "Enter the password";
    public static final String KEY_EXPORT_TITLE = "Export keystore";
    public static final String KEY_EXPORT_ACTION = "Export";
    public static final String KEY_EXPORT_MESSAGE = "Enter the alias and password of the active key to confirm the export.";
    public static final String KEY_EXPORT_MESSAGE_DEBUG = "Default debug key. Alias: " + com.example.studiomobile.build.stages.ApkSigningStage.DEBUG_KEY_ALIAS
        + "\nPassword: " + com.example.studiomobile.build.stages.ApkSigningStage.DEBUG_STORE_PASSWORD;
    public static final String KEY_VERIFY_FAILED = "Alias or password is incorrect";
    public static final String KEY_IMPORT_MESSAGE = "Enter the alias and password of the keystore (.p12) you want to import. Then choose the file.";
    public static final String CHOOSE_FILE = "Choose file";
    public static final String KEY_DEFAULT_FAILED = "Could not create the default keystore: ";
    public static final String KEY_CHANGE_FORM_MESSAGE = "Creates a new key for this app. An installed version signed with the old key cannot be updated by the new one.";
    public static final String KEY_CHANGE_ACTION = "Change Key";
    public static final String KEY_READ_FAILED = "Could not read the key: ";
    public static final String KEY_COPIED = "Keystore copied";
    public static final String KEY_TYPE_DEFAULT = "Studio Mobile default";
    public static final String KEY_TYPE_CUSTOM = "Own key";
    public static final String SHOW = "Show";
    public static final String HIDE = "Hide";
    public static final String PASSWORD_SHORT = "Use at least 6 characters";
    public static final String PASSWORD_MISMATCH = "Passwords do not match";
    public static final String COMPANY_REQUIRED = "Enter the company or developer name";
    public static final String ORGANIZATION_REQUIRED = "Enter the organization";
    public static final String COUNTRY_INVALID = "Use a 2-letter country code, for example ID";
    public static final String KEY_CREATING = "Creating key…";
    public static final String KEY_CREATE_FAILED = "Could not create the key: ";

    // Project root notes
    public static final String UPLOAD_ROOT_TITLE = "Upload to project root";
    public static final String CHOOSE_FILES = "Choose files";
    public static final String ROOT_NOTE = "This is the project root, at the same level as build.gradle.kts and settings.gradle.kts (outside the app folder). To add items inside app, use the ⋮ menu on that folder.";

    // Formatted values
    public static String sdkRange(String min, String target) { return "SDK " + min + "–" + target; }
    public static String sdkSummary(int min, int target) { return "Min " + min + " · Target " + target; }
    public static String sdkSummaryLong(int min, int target) { return "SDK " + min + " to " + target; }
    public static String versionSummary(String name, String code) { return name + " (" + code + ")"; }
    public static String versionShort(String name, int code) { return "v" + name + " (" + code + ")"; }
    public static String projectCount(int count) { return count + (count == 1 ? " project" : " projects"); }
    public static String stepOf(int step, int total) { return "Step " + step + " of " + total; }
    public static String sdkLabel(int api) { return api + " · Android " + androidName(api); }

    public static String androidName(int api) {
        switch (api) {
            case 21: return "5.0";
            case 22: return "5.1";
            case 23: return "6.0";
            case 24: return "7.0";
            case 25: return "7.1";
            case 26: return "8.0";
            case 27: return "8.1";
            case 28: return "9";
            case 29: return "10";
            case 30: return "11";
            case 31: return "12";
            case 32: return "12L";
            case 33: return "13";
            case 34: return "14";
            case 35: return "15";
            case 36: return "16";
            case 37: return "17";
            default: return String.valueOf(api);
        }
    }

    public static String timeAgo(long millis) {
        long diff = Math.max(0, System.currentTimeMillis() - millis);
        long minutes = diff / 60000L, hours = minutes / 60, days = hours / 24;
        if (minutes < 1) return "Just now";
        if (minutes < 60) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        if (days < 2) return "Yesterday";
        return String.format(Locale.US, "%d days ago", days);
    }

    // ---- welcome dialog (first launch, until file access is allowed)
    public static final String WELCOME_TITLE = "Welcome to Studio Mobile";
    public static final String WELCOME_MESSAGE =
        "Build Android apps right from your phone. Studio Mobile brings together a code editor, live previews, debugging tools and MCP support in one place.\n\n"
        + "To get started, please allow file access so your projects can be saved and opened.";
    public static final String WELCOME_CANCEL = "Cancel";
    public static final String WELCOME_CONTINUE = "Continue";
}
