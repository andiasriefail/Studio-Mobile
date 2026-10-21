package com.example.studiomobile;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.Layout;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.drawerlayout.widget.DrawerLayout;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.ManifestMerger;
import com.example.studiomobile.build.dependency.ChecksumFailure;
import com.example.studiomobile.build.stages.ApkSigningStage;
import com.example.studiomobile.workspace.AbortController;
import com.example.studiomobile.workspace.DebugModeSettings;
import com.example.studiomobile.workspace.McpRelayClient;
import com.example.studiomobile.workspace.McpRelayService;
import com.example.studiomobile.workspace.McpRelayUrl;
import com.example.studiomobile.workspace.McpRequestHandler;
import com.example.studiomobile.workspace.McpSettings;
import com.example.studiomobile.workspace.McpToolRegistry;
import com.example.studiomobile.workspace.PreviewRenderer;
import com.example.studiomobile.workspace.ProjectChangeBus;
import com.example.studiomobile.workspace.ProjectBuildService;
import com.example.studiomobile.workspace.ProjectKeys;
import com.example.studiomobile.workspace.StudioStorage;
import com.example.studiomobile.workspace.ProjectFileOperations;
import com.example.studiomobile.workspace.ProjectPreview;
import com.example.studiomobile.workspace.ProjectScaffoldGenerator;
import com.example.studiomobile.workspace.ProjectZipImporter;
import com.example.studiomobile.workspace.StudioStorage;
import com.example.studiomobile.workspace.WallpaperSettings;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private enum Screen { HOME, CREATE, EDITOR, PREVIEW, BUILD, KEYSTORE, MCP, SETTINGS, WALLPAPER }

    private static final class SheetAction {
        final String label; final int icon; final boolean danger; final Runnable run;
        SheetAction(String label, int icon, boolean danger, Runnable run) { this.label = label; this.icon = icon; this.danger = danger; this.run = run; }
    }

    private static final Pattern KEYWORDS = Pattern.compile("\\b(abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|return|short|static|super|switch|synchronized|this|throw|throws|try|void|volatile|while|fun|val|var|object|when|is|in|null|true|false|override|data|sealed|companion|lateinit|by|plugins|dependencies|android|defaultConfig)\\b");
    private static final Pattern C_KEYWORDS = Pattern.compile("\\b(auto|break|case|char|const|continue|default|do|double|else|enum|extern|float|for|goto|if|inline|int|long|register|restrict|return|short|signed|sizeof|static|struct|switch|typedef|union|unsigned|void|volatile|while|bool|class|namespace|template|typename|using|new|delete|public|private|protected|virtual|nullptr|true|false|NULL|uint8_t|uint16_t|uint32_t|uint64_t|int8_t|int16_t|int32_t|int64_t|size_t|jint|jlong|jboolean|jstring|jobject|jclass|jbyte|jchar|jshort|jfloat|jdouble|jsize|JNIEnv|jbyteArray|jintArray|jobjectArray|JNIEXPORT|JNICALL)\\b");
    private static final Pattern C_PREPROCESSOR = Pattern.compile("^[ \\t]*#[ \\t]*\\w+", Pattern.MULTILINE);
    private static final Pattern JS_KEYWORDS = Pattern.compile("\\b(var|let|const|function|return|if|else|for|while|do|switch|case|break|continue|new|delete|typeof|instanceof|in|of|this|class|extends|super|import|export|default|try|catch|finally|throw|async|await|yield|null|undefined|true|false|console|interface|type|enum|namespace|implements|public|private|protected|readonly|abstract|declare|keyof|as|string|number|boolean|any|unknown|never|void)\\b");
    private static final Pattern CSS_PROPERTY = Pattern.compile("[a-zA-Z-]+(?=\\s*:)");
    private static final Pattern CSS_SELECTOR = Pattern.compile("[.#][a-zA-Z_][\\w-]*|@[a-zA-Z-]+");
    private static final Pattern JSX_TAG = Pattern.compile("</?[A-Za-z][\\w.]*|/?>");
    private static final Pattern RUST_KEYWORDS = Pattern.compile("\\b(as|async|await|break|const|continue|crate|dyn|else|enum|extern|false|fn|for|if|impl|in|let|loop|match|mod|move|mut|pub|ref|return|self|Self|static|struct|super|trait|true|type|unsafe|use|where|while|i8|i16|i32|i64|i128|isize|u8|u16|u32|u64|u128|usize|f32|f64|bool|char|str|String|Vec|Option|Some|None|Result|Ok|Err|Box|println|print|vec|format|panic)\\b");
    private static final Pattern RUST_LIFETIME = Pattern.compile("'[a-z_]+\\b|#!?\\[[^\\]]*\\]");
    private static final Pattern LUA_KEYWORDS = Pattern.compile("\\b(and|break|do|else|elseif|end|false|for|function|goto|if|in|local|nil|not|or|repeat|return|then|true|until|while|print|require|pairs|ipairs|table|string|math|os|io|self)\\b");
    private static final Pattern LUA_COMMENT = Pattern.compile("--\\[\\[.*?\\]\\]|--[^\\n]*", Pattern.DOTALL);
    private static final Pattern DART_KEYWORDS = Pattern.compile("\\b(abstract|as|assert|async|await|break|case|catch|class|const|continue|covariant|default|deferred|do|dynamic|else|enum|export|extends|extension|external|factory|false|final|finally|for|Function|get|hide|if|implements|import|in|interface|is|late|library|mixin|new|null|of|on|operator|part|required|rethrow|return|sealed|set|show|static|super|switch|sync|this|throw|true|try|typedef|var|void|when|while|with|yield|int|double|String|bool|num|List|Map|Set|Future|Stream|print|main)\\b");
    private static final Pattern PY_KEYWORDS = Pattern.compile("\\b(and|as|assert|async|await|break|class|continue|def|del|elif|else|except|finally|for|from|global|if|import|in|is|lambda|nonlocal|not|or|pass|raise|return|try|while|with|yield|None|True|False|self|print|match|case)\\b");
    private static final Pattern CMAKE_KEYWORDS = Pattern.compile("\\b(?i:cmake_minimum_required|project|add_library|add_executable|target_link_libraries|target_include_directories|target_compile_options|target_compile_definitions|include_directories|add_compile_options|add_definitions|find_library|set|if|else|elseif|endif|foreach|endforeach|file|include|message|option|SHARED|STATIC|PUBLIC|PRIVATE|INTERFACE)\\b");
    private static final Pattern ANNOTATION = Pattern.compile("@\\w+");
    private static final Pattern CODE_NUMBER = Pattern.compile("\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?[fFlLdD]?)\\b");
    private static final Pattern CODE_TYPE = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b");
    private static final Pattern CODE_STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'");
    private static final Pattern CODE_COMMENT = Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|$)");
    private static final Pattern XML_COMMENT = Pattern.compile("<!--[\\s\\S]*?(?:-->|$)");
    private static final Pattern XML_TAG = Pattern.compile("</?[A-Za-z_][\\w.:-]*");
    private static final Pattern XML_ATTR = Pattern.compile("[A-Za-z_][\\w.-]*(?::[A-Za-z_][\\w.-]*)?(?==)");
    private static final Pattern XML_STRING = Pattern.compile("\"[^\"]*\"");
    private static final Pattern HASH_COMMENT = Pattern.compile("#[^\\n]*");
    private static final Pattern PACKAGE_NAME = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");
    private static final int MIN_SDK_LOWEST = 21, SDK_HIGHEST = 37;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private FrameLayout container;
    private View screenView;
    private Screen screen = Screen.HOME;
    private int insetTop;

    // Projects and editor
    private File currentProject;
    private File openedFile;
    private static final int MAX_TABS = 5;
    /** Open files in tab order (oldest first). The active one is {@link #openedFile}. */
    private final List<File> openTabs = new ArrayList<>();
    private File uploadTarget;
    private boolean wrapOn = true, dirty, loadingText;
    private EditText codeEditor;
    private TextView codeGutter;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable highlightRunnable = () -> { if (codeEditor != null) highlight(codeEditor.getText()); };
    private final Set<String> expanded = new HashSet<>();

    // Create wizard
    private int wizardStep, versionCode = 1, minSdk = 23, targetSdk = 35;
    private Bitmap iconBitmap;
    /** Decoded wallpaper, kept while its stamp is unchanged so reopening the editor does not decode it again. */
    private Bitmap wallpaperBitmap;
    private long wallpaperStamp = -1;

    // Preview
    private int previewToken;
    private File previewOpened;
    private final AbortController previewAbort = new AbortController();
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();

    // Live sync: the AI changes the project through MCP while this screen stays open
    private static final long LIVE_DEBOUNCE_MS = 300, LIVE_MAX_WAIT_MS = 1500;
    private final AbortController syncAbort = new AbortController();
    private final ExecutorService liveExecutor = Executors.newSingleThreadExecutor();
    private final Runnable liveSyncRunnable = this::runLiveSync;
    private final ProjectChangeBus.Listener changeListener = (project, structure) -> runOnUiThread(() -> onProjectChanged(project, structure));
    private boolean pendingStructure;
    private long pendingSince;
    private PreviewRenderer.PhoneView previewPhone;

    // Build
    private ProjectBuildService buildService;
    private final McpRelayService.Listener relayListener = () -> runOnUiThread(this::renderMcpRelay);
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });
    private final ProjectBuildService.Listener buildListener = new ProjectBuildService.Listener() {
        @Override public void onLogAppended(String line) { runOnUiThread(() -> scheduleBuildRender()); }
        @Override public void onStateChanged(ProjectBuildService.State state) { runOnUiThread(() -> onBuildStateChanged(state)); }
    };

    // Keystore
    private File pendingExportSource;
    private File keyProject;                 // the project whose key is shown, imported or replaced
    private Runnable afterKeyImport;
    private boolean bundleNext;              // the next build is an Export AAB build
    private ProjectKeys.Details shownKey;
    private boolean keyPasswordVisible;
    private String pendingImportAlias = "", pendingImportStorePassword = "", pendingImportKeyPassword = "";

    private final ActivityResultLauncher<String[]> pickZip = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) importZip(uri); });
    private final ActivityResultLauncher<Uri> pickFolder = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> { if (uri != null) importFolder(uri); });
    private final ActivityResultLauncher<String[]> pickIcon = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) loadIcon(uri); });
    private final ActivityResultLauncher<String[]> pickUploads = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> { if (uris != null && !uris.isEmpty() && uploadTarget != null) uploadFiles(uris, uploadTarget); });
    private final ActivityResultLauncher<String[]> pickKeystore = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) importKeystore(uri, pendingImportAlias, pendingImportStorePassword, pendingImportKeyPassword); });
    /** The wallpaper is chosen in the gallery (images only), not in the file manager that OpenDocument shows. */
    private final ActivityResultLauncher<Void> pickWallpaper = registerForActivityResult(new androidx.activity.result.contract.ActivityResultContract<Void, Uri>() {
        @Override public android.content.Intent createIntent(Context context, Void input) {
            return new android.content.Intent(android.content.Intent.ACTION_PICK).setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        }
        @Override public Uri parseResult(int resultCode, android.content.Intent data) { return resultCode == RESULT_OK && data != null ? data.getData() : null; }
    }, uri -> { if (uri != null) importWallpaper(uri); });
    /** Used only when the phone has no gallery app that handles the request above. */
    private final ActivityResultLauncher<String[]> pickWallpaperFromFiles = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) importWallpaper(uri); });
    private final ActivityResultLauncher<String> exportKeystoreLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/x-pkcs12"), uri -> { if (uri != null) exportKeystoreTo(uri); });

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        container = findViewById(R.id.screen_container);
        ViewCompat.setOnApplyWindowInsetsListener(container, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            insetTop = bars.top;
            // The preview does not move for the keyboard: the phone inside it shrinks its own content above the keyboard, like a real window.
            boolean inPreview = screen == Screen.PREVIEW;
            view.setPadding(bars.left, 0, bars.right, inPreview ? bars.bottom : Math.max(bars.bottom, ime.bottom));
            if (previewPhone != null) previewPhone.setKeyboardTop(ime.bottom > 0 ? view.getRootView().getHeight() - ime.bottom : -1);
            applyHeaderInset(screenView);
            return WindowInsetsCompat.CONSUMED;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { goBack(); }
        });
        io.execute(() -> {
            StudioStorage.ensureFolders(this);
            com.example.studiomobile.build.LocalLibsInstaller.install(this);
            try { ApkSigningStage.ensureDebugKeyStore(toolsDir(), null); } catch (Exception ignored) { }
        });
        buildService = ProjectBuildService.get(this);
        buildService.addListener(buildListener);
        // Temporary build folders that older versions never deleted. Skipped while a build is running (this Activity may have been
        // re-created in the middle of one).
        io.execute(() -> com.example.studiomobile.build.BuildCacheCleaner.cleanLeftovers(getCacheDir(), buildService::isBuilding));
        showHome();
        resumeRunningBuild();
        if (!StudioStorage.hasAccess(this)) showWelcomeDialog();
    }

    /** True when the Build screen was opened again by {@link #resumeRunningBuild()}, so Back knows there is no editor behind it yet. */
    private boolean recoveredBuildScreen;

    /**
     * The system can destroy and re-create this screen while a build keeps running in the background (the build lives in a service).
     * Instead of landing on Home with no sign of the build, go back to its Build screen.
     */
    private void resumeRunningBuild() {
        if (buildService == null || !buildService.isBuilding()) return;
        String name = buildService.getProjectName();
        if (name == null || name.isEmpty()) return;
        currentProject = new File(projectsDir(), name);   // only a path: nothing is read from disk here
        recoveredBuildScreen = true;
        showBuild(false);
    }

    // ------------------------------------------------------------------ welcome + file access

    private AlertDialog welcomeDialog;

    private void showWelcomeDialog() {
        if (welcomeDialog != null && welcomeDialog.isShowing()) return;
        welcomeDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(LanguageUi.WELCOME_TITLE)
            .setMessage(LanguageUi.WELCOME_MESSAGE)
            .setCancelable(false)
            .setNegativeButton(LanguageUi.WELCOME_CANCEL, null)
            .setPositiveButton(LanguageUi.WELCOME_CONTINUE, (d, w) -> StudioStorage.requestAccess(this))
            .show();
    }

    /** Returning from the Android settings screen (or the Android 8-9 prompt): create the folders and reload the project list. */
    private void onStorageAccessChanged() {
        if (!StudioStorage.hasAccess(this)) return;
        try { projectsExecutor.execute(() -> StudioStorage.ensureFolders(this)); } catch (RuntimeException rejected) { /* closing */ }   // disk work stays off the UI thread
        refreshProjects();
    }

    @Override protected void onResume() { super.onResume(); onStorageAccessChanged(); continueInstallAfterPermission(); }

    /** The APK waiting for the "install unknown apps" permission. Set when the permission screen is opened, cleared when the user comes back. */
    private File pendingInstallApk;

    /** Back from the permission screen: if the permission was granted, open the installer for the APK that was waiting, without asking again. */
    private void continueInstallAfterPermission() {
        File apk = pendingInstallApk;
        if (apk == null) return;
        pendingInstallApk = null;
        if (getPackageManager().canRequestPackageInstalls()) uiHandler.post(() -> installApk(apk));
        else toast(LanguageUi.INSTALL_PERMISSION_DENIED);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == StudioStorage.REQUEST_LEGACY_STORAGE) onStorageAccessChanged();
    }

    @Override protected void onStart() {
        super.onStart();
        McpRelayService.addListener(relayListener); renderMcpRelay();
        ProjectChangeBus.addListener(changeListener);
        // Changes made while the screen was off or the app in the background: catch up with what is on disk now.
        if (currentProject != null) onProjectChanged(currentProject, true);
        // A build that failed its checksum check while the app was in the background: show the dialog now.
        if (buildService.getState() == ProjectBuildService.State.FAILED) showChecksumFailure(buildService.takeChecksumFailure());
    }
    @Override protected void onStop() { super.onStop(); McpRelayService.removeListener(relayListener); ProjectChangeBus.removeListener(changeListener); }
    @Override protected void onPause() { super.onPause(); if (screen == Screen.EDITOR) saveCurrent(false); }
    @Override protected void onDestroy() { super.onDestroy(); if (screen == Screen.PREVIEW && screenView != null) PreviewRenderer.release(screenView); uiHandler.removeCallbacksAndMessages(null); if (buildService != null) buildService.removeListener(buildListener); previewAbort.abort(); syncAbort.abort(); previewExecutor.shutdownNow(); liveExecutor.shutdownNow(); io.shutdown(); projectsExecutor.shutdownNow(); }

    // ------------------------------------------------------------------ navigation helpers

    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int color(int res) { return ContextCompat.getColor(this, res); }

    private View show(Screen next, int layout, boolean lightBars, int backgroundColor) {
        if (screen == Screen.PREVIEW && screenView != null) PreviewRenderer.release(screenView);
        previewPhone = null;
        if (next != Screen.PREVIEW) previewAbort.abort();
        screen = next;
        if (next != Screen.BUILD) getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // only the build page keeps the screen on
        // The keystore page shows passwords: no screenshots and no screen recording there.
        if (next == Screen.KEYSTORE) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        container.removeAllViews();
        container.setBackgroundColor(color(backgroundColor));
        screenView = getLayoutInflater().inflate(layout, container, false);
        container.addView(screenView);
        applyHeaderInset(screenView);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), container);
        controller.setAppearanceLightStatusBars(lightBars);
        controller.setAppearanceLightNavigationBars(lightBars);
        // The preview draws its own status and navigation bars, so the real ones are hidden there (swipe from the edge still shows them briefly).
        if (next == Screen.PREVIEW) {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else controller.show(WindowInsetsCompat.Type.systemBars());
        return screenView;
    }

    private void applyHeaderInset(View root) {
        if (root == null) return;
        int[] ids = {R.id.screen_header, R.id.drawer_header};
        for (int id : ids) {
            View header = root.findViewById(id);
            if (header == null) continue;
            if (header.getTag() == null) header.setTag(header.getPaddingTop());
            int base = (Integer) header.getTag();
            header.setPadding(header.getPaddingLeft(), base + insetTop, header.getPaddingRight(), header.getPaddingBottom());
        }
    }

    private void goBack() {
        switch (screen) {
            case HOME: finish(); break;
            case CREATE: if (wizardStep > 0) { wizardStep--; refreshWizard(); } else showHome(); break;
            case EDITOR: {
                DrawerLayout drawer = screenView.findViewById(R.id.editor_drawer);
                if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) { drawer.closeDrawer(GravityCompat.START); }
                else confirmLeave(this::showHome);
                break;
            }
            case PREVIEW: if (previewPhone == null || !previewPhone.goBack()) showEditor(); break;   // first the preview's own screens, then the preview itself
            case BUILD:
                if (recoveredBuildScreen) {
                    recoveredBuildScreen = false;
                    if (currentProject != null && currentProject.isDirectory()) openProject(currentProject); else showHome();
                } else showEditor();
                break;
            case KEYSTORE: showHome(); break;
            case MCP: showHome(); break;
            case SETTINGS: showHome(); break;
            case WALLPAPER: showSettings(); break;
        }
    }

    /** True while the open file has edits that are not on disk yet. */
    private boolean hasUnsavedChanges() {
        return screen == Screen.EDITOR && openedFile != null && codeEditor != null && dirty;
    }

    /**
     * Guard for leaving the editor (Preview, Build, back to home). With unsaved edits it asks first, so Preview and Build
     * can never run on stale code. "Yes & Quit" saves and then continues; Batal stays in the editor.
     */
    private void confirmLeave(Runnable proceed) {
        if (!hasUnsavedChanges()) { proceed.run(); return; }
        new MaterialAlertDialogBuilder(this)
            .setTitle(LanguageUi.UNSAVED_TITLE).setMessage(LanguageUi.UNSAVED_MESSAGE)
            .setNegativeButton(LanguageUi.UNSAVED_CANCEL, null)
            .setPositiveButton(LanguageUi.UNSAVED_CONFIRM, (d, w) -> { if (saveCurrent(false)) proceed.run(); })
            .show();
    }

    private void click(int id, Runnable action) { screenView.findViewById(id).setOnClickListener(v -> action.run()); }

    // ------------------------------------------------------------------ home

    private File projectsDir() {
        return StudioStorage.projectsDir(this);
    }

    private void showHome() {
        recoveredBuildScreen = false;
        currentProject = null; openedFile = null; openTabs.clear(); codeEditor = null; dirty = false;
        show(Screen.HOME, R.layout.screen_home, false, R.color.cosmic_white);
        click(R.id.home_create, this::showCreate);
        click(R.id.home_import_zip, () -> pickZip.launch(new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"}));
        click(R.id.home_import_folder, () -> pickFolder.launch(null));
        click(R.id.home_mcp, this::showMcp);
        click(R.id.home_settings, this::showSettings);
        refreshProjects();
    }

    // ------------------------------------------------------------------ settings + wallpaper

    private void showSettings() {
        show(Screen.SETTINGS, R.layout.screen_settings, true, R.color.cosmic_white);
        click(R.id.settings_back, this::goBack);
        click(R.id.settings_wallpaper, this::showWallpaper);
    }

    private void showWallpaper() {
        show(Screen.WALLPAPER, R.layout.screen_wallpaper, true, R.color.cosmic_white);
        click(R.id.wallpaper_back, this::goBack);
        click(R.id.wallpaper_choose, () -> {
            try { pickWallpaper.launch(null); }
            catch (android.content.ActivityNotFoundException noGallery) { pickWallpaperFromFiles.launch(new String[]{"image/*"}); }
        });
        click(R.id.wallpaper_remove, () -> {
            WallpaperSettings stored = new WallpaperSettings(this);
            if (!stored.isSet()) return;
            stored.clear();
            wallpaperBitmap = null;
            toast(getString(R.string.wallpaper_removed));
            refreshWallpaperPage();
        });
        screenView.findViewById(R.id.wallpaper_preview_card).setClipToOutline(true);
        final WallpaperSettings settings = new WallpaperSettings(this);
        SeekBar seek = screenView.findViewById(R.id.wallpaper_dim_seek);
        seek.setMax(WallpaperSettings.MAX_DIM_PERCENT);
        seek.setProgress(settings.dimPercent());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) settings.setDimPercent(progress);
                showWallpaperDim(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        refreshWallpaperPage();
    }

    private void showWallpaperDim(int percent) {
        ((TextView) screenView.findViewById(R.id.wallpaper_dim_label)).setText(getString(R.string.wallpaper_dim, percent));
        screenView.findViewById(R.id.wallpaper_preview_shade).setBackgroundColor(WallpaperSettings.dimColor(percent));
    }

    /** Brings the wallpaper page in line with what is stored: preview, darkness slider and the Remove button. */
    private void refreshWallpaperPage() {
        if (screen != Screen.WALLPAPER) return;
        WallpaperSettings settings = new WallpaperSettings(this);
        boolean set = settings.isSet();
        screenView.findViewById(R.id.wallpaper_preview_image).setVisibility(set ? View.VISIBLE : View.GONE);
        screenView.findViewById(R.id.wallpaper_preview_shade).setVisibility(set ? View.VISIBLE : View.GONE);
        screenView.findViewById(R.id.wallpaper_preview_empty).setVisibility(set ? View.GONE : View.VISIBLE);
        screenView.findViewById(R.id.wallpaper_dim_seek).setEnabled(set);
        View remove = screenView.findViewById(R.id.wallpaper_remove);
        remove.setEnabled(set);
        remove.setAlpha(set ? 1f : 0.4f);
        showWallpaperDim(settings.dimPercent());
        if (!set) return;
        withWallpaper(bitmap -> {
            if (screen != Screen.WALLPAPER) return;
            ((ImageView) screenView.findViewById(R.id.wallpaper_preview_image)).setImageBitmap(bitmap);
        });
    }

    private void importWallpaper(Uri uri) {
        io.execute(() -> {
            boolean ok;
            try { new WallpaperSettings(this).importFrom(getContentResolver(), uri); ok = true; }
            catch (Exception | OutOfMemoryError e) { ok = false; }
            final boolean saved = ok;
            runOnUiThread(() -> {
                if (!saved) { toast(getString(R.string.wallpaper_failed)); return; }
                wallpaperBitmap = null;
                toast(getString(R.string.wallpaper_saved));
                refreshWallpaperPage();
            });
        });
    }

    /** Hands over the decoded wallpaper on the UI thread: from the cache when it is current, otherwise after decoding it in the background. */
    private void withWallpaper(Consumer<Bitmap> ready) {
        final WallpaperSettings settings = new WallpaperSettings(this);
        final long stamp = settings.stamp();
        if (wallpaperBitmap != null && wallpaperStamp == stamp) { ready.accept(wallpaperBitmap); return; }
        io.execute(() -> {
            Bitmap bitmap = settings.load();
            runOnUiThread(() -> {
                if (bitmap == null) return;
                wallpaperBitmap = bitmap;
                wallpaperStamp = stamp;
                ready.accept(bitmap);
            });
        });
    }

    /** Shows the user's wallpaper (with its darkness) behind the code area, or nothing when none is set. */
    private void applyEditorWallpaper() {
        ImageView image = screenView.findViewById(R.id.editor_wallpaper);
        View shade = screenView.findViewById(R.id.editor_wallpaper_shade);
        if (image == null || shade == null) return;
        WallpaperSettings settings = new WallpaperSettings(this);
        boolean set = settings.isSet();
        image.setVisibility(set ? View.VISIBLE : View.GONE);
        shade.setVisibility(set ? View.VISIBLE : View.GONE);
        if (!set) { image.setImageDrawable(null); return; }
        shade.setBackgroundColor(WallpaperSettings.dimColor(settings.dimPercent()));
        withWallpaper(bitmap -> {
            if (screen != Screen.EDITOR) return;
            ImageView current = screenView.findViewById(R.id.editor_wallpaper);
            if (current != null) current.setImageBitmap(bitmap);
        });
    }

    /** Everything the project list needs from disk, read off the UI thread (shared storage can be very slow while a build is writing). */
    private static final class ProjectRow {
        final File dir; final String applicationId; final String sdk; final long modified;
        ProjectRow(File dir, String applicationId, String sdk, long modified) { this.dir = dir; this.applicationId = applicationId; this.sdk = sdk; this.modified = modified; }
    }

    private final ExecutorService projectsExecutor = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean projectsLoading = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean projectsReloadQueued;

    /**
     * Reloads the project list. The folder listing and the manifest/Gradle reads happen on a background thread; only drawing the rows runs on
     * the UI thread. Calls that arrive while a load is running are merged into one more load afterwards. (Doing this on the UI thread from
     * onResume froze the app for minutes while a build was using the disk.)
     */
    private void refreshProjects() {
        if (screen != Screen.HOME) return;
        if (!projectsLoading.compareAndSet(false, true)) { projectsReloadQueued = true; return; }
        try {
            projectsExecutor.execute(() -> {
                List<ProjectRow> rows = new ArrayList<>();
                try {
                    File[] dirs = projectsDir().listFiles(File::isDirectory);
                    if (dirs != null) for (File dir : dirs) {
                        rows.add(new ProjectRow(dir, projectPackage(dir), LanguageUi.sdkRange(
                            BuildProjectFiles.readNumericSdkValue(dir, true, "?"), BuildProjectFiles.readNumericSdkValue(dir, false, "?")), dir.lastModified()));
                    }
                    Collections.sort(rows, (a, b) -> Long.compare(b.modified, a.modified));
                } catch (RuntimeException unreadable) { /* show what was read so far */ }
                runOnUiThread(() -> {
                    projectsLoading.set(false);
                    if (!isFinishing() && screen == Screen.HOME) renderProjects(rows);
                    if (projectsReloadQueued) { projectsReloadQueued = false; refreshProjects(); }
                });
            });
        } catch (RuntimeException rejected) { projectsLoading.set(false); }
    }

    private void renderProjects(List<ProjectRow> projects) {
        LinearLayout list = screenView.findViewById(R.id.project_list);
        list.removeAllViews();
        ((TextView) screenView.findViewById(R.id.home_count)).setText(LanguageUi.projectCount(projects.size()));
        int[] avatarBg = {R.drawable.bg_avatar_peach, R.drawable.bg_avatar_lilac, R.drawable.bg_avatar_blue};
        int[] avatarInk = {R.color.cosmic_peach_ink, R.color.cosmic_primary, R.color.cosmic_blue_ink};
        for (int i = 0; i < projects.size(); i++) {
            ProjectRow row = projects.get(i);
            File project = row.dir;
            View item = getLayoutInflater().inflate(R.layout.item_project, list, false);
            item.findViewById(R.id.project_avatar).setBackgroundResource(avatarBg[i % 3]);
            TextView initials = item.findViewById(R.id.project_initials);
            initials.setText(initialsOf(project.getName()));
            initials.setTextColor(color(avatarInk[i % 3]));
            ((TextView) item.findViewById(R.id.project_name)).setText(project.getName());
            ((TextView) item.findViewById(R.id.project_package)).setText(row.applicationId);
            ((TextView) item.findViewById(R.id.project_sdk)).setText(row.sdk);
            ((TextView) item.findViewById(R.id.project_time)).setText(LanguageUi.timeAgo(row.modified));
            item.setOnClickListener(v -> openProject(project));
            item.findViewById(R.id.project_more).setOnClickListener(v -> showSheet(project.getName(), Arrays.asList(
                new SheetAction(getString(R.string.keystore), R.drawable.ic_key, false, () -> showKeystore(project)),
                new SheetAction(LanguageUi.EXPORT_AAB, R.drawable.ic_upload, false, () -> exportAabFlow(project)),
                new SheetAction(getString(R.string.rename), R.drawable.ic_edit, false, () -> renameProject(project)),
                new SheetAction(getString(R.string.delete), R.drawable.ic_delete, true, () -> confirmDelete(project, () -> { new ProjectKeys(getFilesDir()).clear(project.getName()); refreshProjects(); })))));
            list.addView(item);
        }
    }

    private String initialsOf(String name) {
        String letters = name.replaceAll("[^A-Za-z0-9]", "");
        if (letters.isEmpty()) return "?";
        return letters.substring(0, Math.min(2, letters.length())).toUpperCase(Locale.ROOT);
    }

    private String projectPackage(File project) {
        String id = BuildProjectFiles.readApplicationId(project, null);
        if (id == null) {
            try { id = ManifestMerger.readPackage(BuildProjectFiles.manifest(project)); } catch (Exception ignored) { }
        }
        return id == null ? project.getName() : id;
    }

    private String projectVersion(File project) {
        File module = BuildProjectFiles.moduleDirectory(project);
        File gradle = new File(module, "build.gradle");
        if (!gradle.isFile()) gradle = new File(module, "build.gradle.kts");
        String name = "?", code = "?";
        try {
            String text = new String(java.nio.file.Files.readAllBytes(gradle.toPath()), StandardCharsets.UTF_8);
            Matcher n = Pattern.compile("versionName\\s*(?:=\\s*)?[\"']([^\"']+)[\"']").matcher(text);
            if (n.find()) name = n.group(1);
            Matcher c = Pattern.compile("versionCode\\s*(?:=\\s*)?(\\d+)").matcher(text);
            if (c.find()) code = c.group(1);
        } catch (Exception ignored) { }
        return LanguageUi.versionSummary(name, code);
    }

    private void showSheet(String title, List<SheetAction> actions) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = getLayoutInflater().inflate(R.layout.sheet_menu, null);
        ((TextView) sheet.findViewById(R.id.sheet_title)).setText(title);
        LinearLayout items = sheet.findViewById(R.id.sheet_items);
        for (SheetAction action : actions) {
            View row = getLayoutInflater().inflate(R.layout.item_sheet_action, items, false);
            int tint = color(action.danger ? R.color.cosmic_danger : R.color.cosmic_ink);
            ImageView icon = row.findViewById(R.id.action_icon);
            icon.setImageResource(action.icon);
            icon.setImageTintList(ColorStateList.valueOf(action.danger ? tint : color(R.color.cosmic_primary)));
            TextView label = row.findViewById(R.id.action_label);
            label.setText(action.label);
            label.setTextColor(tint);
            row.setOnClickListener(v -> { dialog.dismiss(); action.run.run(); });
            items.addView(row);
        }
        dialog.setContentView(sheet);
        dialog.show();
    }

    private void inputDialog(String title, String hint, String value, String positive, Consumer<String> onOk) {
        inputDialog(title, null, hint, value, positive, onOk);
    }

    private void inputDialog(String title, String message, String hint, String value, String positive, Consumer<String> onOk) {
        View view = getLayoutInflater().inflate(R.layout.dialog_input, null);
        EditText input = view.findViewById(R.id.dialog_input);
        input.setHint(hint);
        input.setText(value);
        input.setSelection(input.getText().length());
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this).setTitle(title).setView(view);
        if (message != null) builder.setMessage(message);
        builder.setNegativeButton(R.string.cancel, null)
            .setPositiveButton(positive, (d, w) -> onOk.accept(input.getText().toString().trim())).show();
    }

    private boolean validName(String value) {
        return value != null && !value.isEmpty() && !value.equals(".") && !value.equals("..") && !value.matches(".*[\\\\/:*?\"<>|].*");
    }

    private void renameProject(File project) {
        inputDialog(getString(R.string.rename), LanguageUi.NEW_NAME, project.getName(), getString(R.string.rename), value -> {
            if (!validName(value)) { toast(LanguageUi.ENTER_VALID_NAME); return; }
            File target = new File(projectsDir(), value);
            if (target.exists()) { toast(LanguageUi.PROJECT_EXISTS); return; }
            if (project.renameTo(target)) { new ProjectKeys(getFilesDir()).rename(project.getName(), value); toast(LanguageUi.RENAMED); refreshProjects(); } else toast(LanguageUi.RENAME_FAILED);
        });
    }

    private void confirmDelete(File item, Runnable afterDelete) {
        new MaterialAlertDialogBuilder(this).setTitle(LanguageUi.deleteTitle(item.getName())).setMessage(LanguageUi.DELETE_MESSAGE)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete, (d, w) -> io.execute(() -> {
                deleteRecursively(item);
                runOnUiThread(() -> {
                    if (item.exists()) { toast(LanguageUi.DELETE_FAILED); return; }
                    toast(LanguageUi.DELETED);
                    afterDelete.run();
                });
            })).show();
    }

    // ------------------------------------------------------------------ import

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "project" : last;
    }

    private File uniqueFile(File parent, String base) {
        File candidate = new File(parent, base);
        for (int suffix = 2; candidate.exists(); suffix++) candidate = new File(parent, base + "-" + suffix);
        return candidate;
    }

    private String safeName(String raw, String fallback) {
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return validName(cleaned) ? cleaned : fallback;
    }

    private void importZip(Uri zipUri) {
        String zipName = displayName(zipUri);
        String base = zipName.toLowerCase(Locale.ROOT).endsWith(".zip") ? zipName.substring(0, zipName.length() - 4) : zipName;
        final String projectName = safeName(base, "project");
        toast(LanguageUi.IMPORTING);
        io.execute(() -> {
            File dest = uniqueFile(projectsDir(), projectName);
            try {
                if (!dest.mkdirs()) throw new IOException("Could not create " + dest.getName());
                ProjectZipImporter.importZip(
                    () -> { InputStream in = getContentResolver().openInputStream(zipUri); if (in == null) throw new IOException("Could not open the ZIP."); return in; },
                    new ProjectZipImporter.Sink() {
                        @Override public void ensureDirectory(String relativePath) throws IOException {
                            File dir = new File(dest, relativePath);
                            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create folder: " + relativePath);
                        }
                        @Override public OutputStream openFile(String relativePath) throws IOException {
                            File file = new File(dest, relativePath);
                            File parent = file.getParentFile();
                            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create folder for: " + relativePath);
                            return new FileOutputStream(file);
                        }
                    },
                    (written, total) -> { });
                runOnUiThread(() -> { toast(LanguageUi.PROJECT_IMPORTED); refreshProjects(); });
            } catch (Exception e) {
                deleteRecursively(dest);
                runOnUiThread(() -> toast(LanguageUi.IMPORT_FAILED + e.getMessage()));
            }
        });
    }

    private void importFolder(Uri treeUri) {
        DocumentFile tree = DocumentFile.fromTreeUri(this, treeUri);
        if (tree == null || !tree.isDirectory()) { toast(LanguageUi.IMPORT_FAILED + "folder not available"); return; }
        final String projectName = safeName(tree.getName() == null ? "project" : tree.getName(), "project");
        toast(LanguageUi.IMPORTING);
        io.execute(() -> {
            File dest = uniqueFile(projectsDir(), projectName);
            try {
                copyDocumentTree(tree, dest);
                runOnUiThread(() -> { toast(LanguageUi.PROJECT_IMPORTED); refreshProjects(); });
            } catch (Exception e) {
                deleteRecursively(dest);
                runOnUiThread(() -> toast(LanguageUi.IMPORT_FAILED + e.getMessage()));
            }
        });
    }

    private boolean skipWhenCopying(String name) {
        return name.equals(".git") || name.equals(".gradle") || name.equals("build") || name.equals("outputs") || name.equals(".idea");
    }

    private void copyDocumentTree(DocumentFile source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) throw new IOException("Could not create directory: " + destination.getName());
            for (DocumentFile child : source.listFiles()) {
                String childName = child.getName();
                if (childName == null || skipWhenCopying(childName)) continue;
                File target = new File(destination, childName);
                if (!target.getCanonicalPath().startsWith(destination.getCanonicalPath() + File.separator)) throw new IOException("Unsafe file name in folder.");
                copyDocumentTree(child, target);
            }
            return;
        }
        try (InputStream input = getContentResolver().openInputStream(source.getUri()); OutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("Could not read " + source.getName());
            copyStream(input, output);
        }
    }

    private void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32768]; int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteRecursively(child); }
        file.delete();
    }

    // ------------------------------------------------------------------ create project wizard

    private void showCreate() {
        wizardStep = 0; versionCode = 1; minSdk = 23; targetSdk = 35; iconBitmap = null;
        show(Screen.CREATE, R.layout.screen_create, true, R.color.cosmic_white);
        click(R.id.wizard_back_icon, this::goBack);
        click(R.id.btn_back, this::goBack);
        click(R.id.btn_next, this::wizardNext);
        click(R.id.btn_create, this::createProject);
        click(R.id.btn_choose_icon, () -> pickIcon.launch(new String[]{"image/*"}));
        click(R.id.version_code_minus, () -> { if (versionCode > 1) versionCode--; refreshWizard(); });
        click(R.id.version_code_plus, () -> { if (versionCode < 2100000000) versionCode++; refreshWizard(); });
        click(R.id.min_sdk_minus, () -> { if (minSdk > MIN_SDK_LOWEST) minSdk--; refreshWizard(); });
        click(R.id.min_sdk_plus, () -> { if (minSdk < targetSdk) minSdk++; refreshWizard(); });
        click(R.id.target_sdk_minus, () -> { if (targetSdk > minSdk) targetSdk--; refreshWizard(); });
        click(R.id.target_sdk_plus, () -> { if (targetSdk < SDK_HIGHEST) targetSdk++; refreshWizard(); });
        refreshWizard();
    }

    private String text(int id) { return ((TextView) screenView.findViewById(id)).getText().toString().trim(); }
    private String wizardPackage() {
        String pkg = text(R.id.input_package);
        if (!pkg.isEmpty()) return pkg;
        String slug = text(R.id.input_project).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (slug.isEmpty() || Character.isDigit(slug.charAt(0))) slug = "app" + slug;
        return "com.example." + slug;
    }
    private String wizardAppName() { String app = text(R.id.input_app); return app.isEmpty() ? text(R.id.input_project) : app; }
    private String wizardVersionName() { String v = text(R.id.input_version_name); return v.isEmpty() ? "1.0" : v; }

    private void refreshWizard() {
        int[] titles = {R.string.step_app_icon, R.string.step_identity, R.string.step_version, R.string.step_android_versions};
        int[] pages = {R.id.wizard_page_1, R.id.wizard_page_2, R.id.wizard_page_3, R.id.wizard_page_4};
        int[] bars = {R.id.wizard_bar_1, R.id.wizard_bar_2, R.id.wizard_bar_3, R.id.wizard_bar_4};
        ((TextView) screenView.findViewById(R.id.wizard_title)).setText(titles[wizardStep]);
        ((TextView) screenView.findViewById(R.id.wizard_step)).setText(LanguageUi.stepOf(wizardStep + 1, 4));
        for (int i = 0; i < 4; i++) {
            screenView.findViewById(pages[i]).setVisibility(i == wizardStep ? View.VISIBLE : View.GONE);
            screenView.findViewById(bars[i]).setBackgroundResource(i <= wizardStep ? R.drawable.bg_step_bar_on : R.drawable.bg_step_bar_off);
        }
        screenView.findViewById(R.id.btn_back).setVisibility(wizardStep == 0 ? View.GONE : View.VISIBLE);
        screenView.findViewById(R.id.btn_next).setVisibility(wizardStep < 3 ? View.VISIBLE : View.GONE);
        screenView.findViewById(R.id.btn_create).setVisibility(wizardStep == 3 ? View.VISIBLE : View.GONE);
        ((TextView) screenView.findViewById(R.id.version_code_text)).setText(String.valueOf(versionCode));
        ((TextView) screenView.findViewById(R.id.min_sdk_text)).setText(LanguageUi.sdkLabel(minSdk));
        ((TextView) screenView.findViewById(R.id.target_sdk_text)).setText(LanguageUi.sdkLabel(targetSdk));
        ((TextView) screenView.findViewById(R.id.summary_identity)).setText(text(R.id.input_project) + " · " + wizardPackage());
        ((TextView) screenView.findViewById(R.id.summary_version)).setText(LanguageUi.versionShort(wizardVersionName(), versionCode));
        ((TextView) screenView.findViewById(R.id.summary_sdk)).setText(LanguageUi.sdkSummaryLong(minSdk, targetSdk));
    }

    private void wizardNext() {
        if (wizardStep == 1) {
            String name = text(R.id.input_project);
            if (!validName(name)) { toast(LanguageUi.PROJECT_NAME_REQUIRED); return; }
            if (new File(projectsDir(), name).exists()) { toast(LanguageUi.PROJECT_EXISTS); return; }
            if (!PACKAGE_NAME.matcher(wizardPackage()).matches()) { toast(LanguageUi.PACKAGE_INVALID); return; }
        }
        if (wizardStep < 3) { wizardStep++; refreshWizard(); }
    }

    private void loadIcon(Uri uri) {
        io.execute(() -> {
            Bitmap square = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
                int sample = 1;
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                Bitmap decoded;
                try (InputStream in = getContentResolver().openInputStream(uri)) { decoded = BitmapFactory.decodeStream(in, null, options); }
                if (decoded != null) {
                    int side = Math.min(decoded.getWidth(), decoded.getHeight());
                    square = Bitmap.createBitmap(decoded, (decoded.getWidth() - side) / 2, (decoded.getHeight() - side) / 2, side, side);
                }
            } catch (Exception ignored) { }
            final Bitmap result = square;
            runOnUiThread(() -> {
                if (result == null) { toast(LanguageUi.ICON_FAILED); return; }
                if (screen != Screen.CREATE) return;
                iconBitmap = result;
                ImageView preview = screenView.findViewById(R.id.icon_preview);
                preview.setImageTintList(null);
                preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                ViewGroup.LayoutParams params = preview.getLayoutParams();
                params.width = dp(176); params.height = dp(176);
                preview.setLayoutParams(params);
                preview.setImageBitmap(result);
                int[] status = {R.id.icon_status_1, R.id.icon_status_2, R.id.icon_status_3, R.id.icon_status_4};
                for (int id : status) {
                    ImageView mark = screenView.findViewById(id);
                    mark.setImageResource(R.drawable.ic_check_circle);
                    mark.setImageTintList(ColorStateList.valueOf(color(R.color.cosmic_primary)));
                }
            });
        });
    }

    /** Creates the project folder and writes the starter Gradle, manifest, resource and source files. */
    private void createProject() {
        final String name = text(R.id.input_project), pkg = wizardPackage();
        if (!validName(name)) { toast(LanguageUi.PROJECT_NAME_REQUIRED); return; }
        if (!PACKAGE_NAME.matcher(pkg).matches()) { toast(LanguageUi.PACKAGE_INVALID); return; }
        final File dir = new File(projectsDir(), name);
        if (dir.exists()) { toast(LanguageUi.PROJECT_EXISTS); return; }
        final String label = wizardAppName();
        io.execute(() -> {
            if (!dir.mkdirs()) { runOnUiThread(() -> toast(LanguageUi.CREATE_FAILED + "could not create the project folder")); return; }
            try {
                ProjectScaffoldGenerator.generate(dir, name, label, pkg);
                runOnUiThread(() -> { toast(LanguageUi.PROJECT_CREATED); openProject(dir); });
            } catch (IOException | RuntimeException error) {
                dir.delete();
                runOnUiThread(() -> toast(LanguageUi.CREATE_FAILED + error.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------------ editor

    private void openProject(File project) {
        recoveredBuildScreen = false;
        currentProject = project; openedFile = null; openTabs.clear(); expanded.clear();
        File module = BuildProjectFiles.moduleDirectory(project);
        String moduleRel = relativePath(module);
        String base = moduleRel.isEmpty() ? "" : moduleRel + "/";
        if (!moduleRel.isEmpty()) expanded.add(moduleRel);
        expanded.add(base + "src");
        expanded.add(base + "src/main/java");
        expanded.add(base + "src/main/kotlin");
        showEditor();
    }

    private String relativePath(File file) {
        String root = currentProject.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.equals(root)) return "";
        return path.startsWith(root + File.separator) ? path.substring(root.length() + 1).replace(File.separatorChar, '/') : path;
    }

    /** Bug icon next to Build: opens a small dialog with one checkbox that turns the crash catcher on or off for the next build. */
    private void showDebugMenu() {
        final DebugModeSettings debugSettings = new DebugModeSettings(this);
        final View content = getLayoutInflater().inflate(R.layout.dialog_debug_mode, null);
        final CheckBox checkBox = content.findViewById(R.id.debug_checkbox);
        checkBox.setChecked(debugSettings.isEnabled());
        checkBox.setOnCheckedChangeListener((button, enabled) -> {
            debugSettings.setEnabled(enabled);
            refreshDebugIcon();
        });
        content.findViewById(R.id.debug_row).setOnClickListener(v -> checkBox.toggle());
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.debug_mode_menu)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    /** The bug icon turns red while Debug Mode is on, so it is not forgotten before sharing an app. */
    private void refreshDebugIcon() {
        if (screenView == null) return;
        ImageView icon = screenView.findViewById(R.id.editor_debug);
        if (icon == null) return;
        boolean on = new DebugModeSettings(this).isEnabled();
        icon.setImageTintList(ColorStateList.valueOf(on ? 0xFFD32F2F : ContextCompat.getColor(this, R.color.cosmic_primary_dark)));
    }

    private void showEditor() {
        if (currentProject == null) { showHome(); return; }
        show(Screen.EDITOR, R.layout.screen_editor, false, R.color.night_bg);
        ((TextView) screenView.findViewById(R.id.drawer_name)).setText(currentProject.getName());
        ((TextView) screenView.findViewById(R.id.drawer_package)).setText(projectPackage(currentProject));
        ((TextView) screenView.findViewById(R.id.drawer_initials)).setText(initialsOf(currentProject.getName()));
        codeEditor = screenView.findViewById(R.id.code_editor);
        codeGutter = screenView.findViewById(R.id.code_gutter);
        applyEditorWallpaper();
        DrawerLayout drawer = screenView.findViewById(R.id.editor_drawer);
        click(R.id.editor_menu, () -> drawer.openDrawer(GravityCompat.START));
        click(R.id.editor_open_structure, () -> drawer.openDrawer(GravityCompat.START));
        click(R.id.editor_preview, () -> confirmLeave(this::showPreview));
        click(R.id.editor_build, () -> confirmLeave(() -> showBuild(true)));
        click(R.id.editor_debug, this::showDebugMenu);
        refreshDebugIcon();
        click(R.id.editor_save, () -> saveCurrent(true));
        click(R.id.editor_wrap, () -> { wrapOn = !wrapOn; applyWrap(); });
        click(R.id.drawer_all_projects, () -> confirmLeave(this::showHome));
        click(R.id.tree_upload, () -> startUpload(currentProject));
        click(R.id.tree_add_file, () -> createItem(currentProject, false));
        click(R.id.tree_add_folder, () -> createItem(currentProject, true));
        codeEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (loadingText) highlight(s);
                else { dirty = true; uiHandler.removeCallbacks(highlightRunnable); uiHandler.postDelayed(highlightRunnable, 150); }
                if (codeEditor != null) codeEditor.post(MainActivity.this::updateGutter);
            }
        });
        codeEditor.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol) updateGutter(); });
        applyWrap();
        renderTree();
        for (int i = openTabs.size() - 1; i >= 0; i--) if (!openTabs.get(i).isFile()) openTabs.remove(i);
        if (openedFile != null && !openTabs.contains(openedFile)) openedFile = null;
        if (openedFile == null && !openTabs.isEmpty()) openedFile = openTabs.get(openTabs.size() - 1);
        if (openedFile != null && openedFile.isFile()) readIntoEditor(openedFile); else showFileState(false);
    }

    private void applyWrap() {
        if (codeEditor == null) return;
        HorizontalScrollView hscroll = screenView.findViewById(R.id.code_hscroll);
        FrameLayout holder = screenView.findViewById(R.id.code_holder);
        ((ViewGroup) codeEditor.getParent()).removeView(codeEditor);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (wrapOn) {
            hscroll.setVisibility(View.GONE);
            codeEditor.setHorizontallyScrolling(false);
            holder.addView(codeEditor, params);
        } else {
            hscroll.setVisibility(View.VISIBLE);
            codeEditor.setHorizontallyScrolling(true);
            hscroll.addView(codeEditor, params);
        }
        View wrapButton = screenView.findViewById(R.id.editor_wrap);
        wrapButton.setBackgroundResource(wrapOn ? R.drawable.bg_icon_circle_night : 0);
        codeEditor.post(this::updateGutter);
    }

    private void updateGutter() {
        if (codeEditor == null || codeGutter == null) return;
        Layout layout = codeEditor.getLayout();
        if (layout == null) return;
        CharSequence text = codeEditor.getText();
        StringBuilder numbers = new StringBuilder();
        int lines = layout.getLineCount(), number = 0;
        for (int i = 0; i < lines; i++) {
            int start = layout.getLineStart(i);
            if (start == 0 || text.charAt(start - 1) == '\n') { number++; numbers.append(number); }
            if (i < lines - 1) numbers.append('\n');
        }
        codeGutter.setText(numbers);
    }

    private void paint(Editable text, Pattern pattern, int colorRes) {
        int value = color(colorRes);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (matcher.end() > matcher.start()) text.setSpan(new ForegroundColorSpan(value), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** Colors the visible file by type. Later calls win over earlier ones, so strings and comments are painted last. */
    private void highlight(Editable text) {
        for (ForegroundColorSpan span : text.getSpans(0, text.length(), ForegroundColorSpan.class)) text.removeSpan(span);
        if (openedFile == null || text.length() > 150000) return;
        String name = openedFile.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts") || name.endsWith(".gradle")) {
            paint(text, CODE_TYPE, R.color.code_type);
            paint(text, KEYWORDS, R.color.code_keyword);
            paint(text, CODE_NUMBER, R.color.code_number);
            paint(text, ANNOTATION, R.color.code_annotation);
            paint(text, CODE_STRING, R.color.code_string);
            paint(text, CODE_COMMENT, R.color.code_comment);
        } else if (isNativeSource(name)) {
            paint(text, CODE_NUMBER, R.color.code_number);
            paint(text, C_KEYWORDS, R.color.code_keyword);
            paint(text, C_PREPROCESSOR, R.color.code_annotation);
            paint(text, CODE_STRING, R.color.code_string);
            paint(text, CODE_COMMENT, R.color.code_comment);
        } else if (name.endsWith(".css") || name.endsWith(".scss")) {
            paint(text, CODE_NUMBER, R.color.code_number);
            paint(text, CSS_SELECTOR, R.color.code_annotation);
            paint(text, CSS_PROPERTY, R.color.code_keyword);
            paint(text, CODE_STRING, R.color.code_string);
            paint(text, CODE_COMMENT, R.color.code_comment);
        } else if (name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".jsx") || name.endsWith(".ts") || name.endsWith(".tsx")) {
            paint(text, CODE_NUMBER, R.color.code_number);
            paint(text, JS_KEYWORDS, R.color.code_keyword);
            if (name.endsWith("x")) paint(text, JSX_TAG, R.color.code_annotation);
            paint(text, CODE_STRING, R.color.code_string);
            paint(text, CODE_COMMENT, R.color.code_comment);
        } else if (name.endsWith(".rs")) {
            paint(text, CODE_NUMBER, R.color.code_number);
            paint(text, RUST_KEYWORDS, R.color.code_keyword);
            paint(text, RUST_LIFETIME, R.color.code_annotation);
            paint(text, CODE_STRING, R.color.code_string);
            paint(text, CODE_COMMENT, R.color.code_comment);
        } else if (name.endsWith(".lua")) {
            paint(text, CODE_NUMBER, R.color.code_number);
            paint(text, LUA_KEYWORDS, R.color.code_keyword);
            paint(text, CODE_STRING, R.color.code_string);
            paint(text, LUA_COMMENT, R.color.code_comment);
        } else if (name.endsWith(".dart")) {
            paint(text, CODE_NUMBER, R.color.code_number);
            paint(text, DART_KEYWORDS, R.color.code_keyword);
            paint(text, ANNOTATION, R.color.code_annotation);
            paint(text, CODE_STRING, R.color.code_string);
            paint(text, CODE_COMMENT, R.color.code_comment);
        } else if (name.endsWith(".py") || name.endsWith(".pyw")) {
            paint(text, CODE_NUMBER, R.color.code_number);
            paint(text, PY_KEYWORDS, R.color.code_keyword);
            paint(text, ANNOTATION, R.color.code_annotation);
            paint(text, CODE_STRING, R.color.code_string);
            paint(text, HASH_COMMENT, R.color.code_comment);
        } else if (name.equals("cmakelists.txt") || name.endsWith(".cmake")) {
            paint(text, CODE_NUMBER, R.color.code_number);
            paint(text, CMAKE_KEYWORDS, R.color.code_keyword);
            paint(text, CODE_STRING, R.color.code_string);
            paint(text, HASH_COMMENT, R.color.code_comment);
        } else if (name.endsWith(".xml") || name.endsWith(".html")) {
            paint(text, XML_ATTR, R.color.code_attr);
            paint(text, XML_TAG, R.color.code_keyword);
            paint(text, XML_STRING, R.color.code_string);
            paint(text, XML_COMMENT, R.color.code_comment);
        } else if (name.endsWith(".json")) {
            paint(text, CODE_NUMBER, R.color.code_number);
            paint(text, KEYWORDS, R.color.code_keyword);
            paint(text, CODE_STRING, R.color.code_string);
        } else if (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".toml") || name.endsWith(".pro")) {
            paint(text, CODE_NUMBER, R.color.code_number);
            paint(text, CODE_STRING, R.color.code_string);
            paint(text, HASH_COMMENT, R.color.code_comment);
        }
    }

    /** C/C++ sources and headers of the native (CMake) part of a project; {@code lowerCaseName} must already be lower case. */
    private static boolean isNativeSource(String lowerCaseName) {
        for (String extension : new String[] {".c", ".h", ".cc", ".cpp", ".cxx", ".hpp", ".hh", ".hxx", ".s"}) if (lowerCaseName.endsWith(extension)) return true;
        return false;
    }

    private boolean isEditable(File file) {
        String n = file.getName().toLowerCase(Locale.ROOT);
        String[] extensions = {".java", ".kt", ".xml", ".gradle", ".kts", ".properties", ".json", ".txt", ".md", ".pro", ".toml", ".yml", ".yaml", ".cfg", ".html", ".css", ".js", ".jsx", ".ts", ".tsx", ".mjs", ".scss", ".dart", ".py", ".lua", ".rs", ".toml", ".bat", ".cmd", ".sh", ".c", ".h", ".cc", ".cpp", ".cxx", ".hpp", ".hh", ".hxx", ".s", ".cmake", ".mk"};
        for (String extension : extensions) if (n.endsWith(extension)) return true;
        if (n.equals("gitignore") || n.equals(".gitignore") || n.equals("gradlew")) return true;
        return looksLikeText(file);
    }

    /** Files with other or no extension (for example gradlew) open when they are small, have no NUL bytes and start with valid UTF-8. Images and jars stay blocked. */
    private static boolean looksLikeText(File file) {
        if (!file.isFile() || file.length() > 1000000L) return false;
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[8192];
            int read = in.read(head);
            if (read < 0) return true; // empty file
            for (int i = 0; i < read; i++) if (head[i] == 0) return false;
            // endOfInput=false: a multi-byte character cut at the end of the sample is not an error.
            java.nio.charset.CoderResult result = StandardCharsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(head, 0, read), java.nio.CharBuffer.allocate(read + 1), false);
            return !result.isError();
        } catch (java.io.IOException unreadable) { return false; }
    }

    /** Opens a file from the structure drawer as a tab. With 5 tabs already open, the oldest inactive one is closed (it is already saved). */
    private void openFile(File file) {
        if (!isEditable(file)) { toast(LanguageUi.CANNOT_EDIT); return; }
        DrawerLayout drawer = screenView.findViewById(R.id.editor_drawer);
        if (drawer != null) drawer.closeDrawer(GravityCompat.START);
        if (file.equals(openedFile)) return;
        saveCurrent(false);
        if (!openTabs.contains(file)) {
            while (openTabs.size() >= MAX_TABS) {
                int victim = -1;
                for (int i = 0; i < openTabs.size(); i++) if (!openTabs.get(i).equals(openedFile)) { victim = i; break; }
                if (victim < 0) break;
                openTabs.remove(victim);
            }
            openTabs.add(file);
        }
        openedFile = file;
        renderTabs();
        readIntoEditor(file);
    }

    /** Tap on a tab: save the current file and show the other one. */
    private void activateTab(File file) {
        if (file.equals(openedFile)) return;
        saveCurrent(false);
        openedFile = file;
        renderTabs();
        readIntoEditor(file);
    }

    /** Closes one tab (its X). Closing the active tab moves to its neighbour, or to the empty state when it was the last one. */
    private void closeTab(File file) {
        int index = openTabs.indexOf(file);
        if (index < 0) return;
        if (file.equals(openedFile)) {
            saveCurrent(false);
            openTabs.remove(index);
            if (openTabs.isEmpty()) { openedFile = null; dirty = false; showFileState(false); return; }
            openedFile = openTabs.get(Math.min(index, openTabs.size() - 1));
            renderTabs();
            readIntoEditor(openedFile);
        } else {
            openTabs.remove(index);
            renderTabs();
        }
    }

    /** A file or folder was deleted: drop every tab under it and, if the active one went, fall back to the last remaining tab. */
    private void dropTabsUnder(String path) {
        boolean activeLost = false;
        for (int i = openTabs.size() - 1; i >= 0; i--) {
            String tabPath = openTabs.get(i).getAbsolutePath();
            if (tabPath.equals(path) || tabPath.startsWith(path + File.separator)) {
                if (openTabs.get(i).equals(openedFile)) activeLost = true;
                openTabs.remove(i);
            }
        }
        if (!activeLost) { renderTabs(); return; }
        dirty = false;
        if (openTabs.isEmpty()) { openedFile = null; showFileState(false); }
        else { openedFile = openTabs.get(openTabs.size() - 1); renderTabs(); readIntoEditor(openedFile); }
    }

    /** A file or folder was renamed: keep its tabs pointing at the new path. */
    private void remapTabs(String oldPath, File renamed) {
        for (int i = 0; i < openTabs.size(); i++) {
            String tabPath = openTabs.get(i).getAbsolutePath();
            if (tabPath.equals(oldPath) || tabPath.startsWith(oldPath + File.separator))
                openTabs.set(i, new File(renamed.getAbsolutePath() + tabPath.substring(oldPath.length())));
        }
    }

    private void renderTabs() {
        if (screen != Screen.EDITOR || screenView == null) return;
        LinearLayout strip = screenView.findViewById(R.id.tab_strip);
        HorizontalScrollView scroller = screenView.findViewById(R.id.editor_tab_bar);
        if (strip == null || scroller == null) return;
        strip.removeAllViews();
        View activeTab = null;
        for (File file : openTabs) {
            boolean active = file.equals(openedFile);
            View tab = getLayoutInflater().inflate(R.layout.item_editor_tab, strip, false);
            String label = file.getName();
            for (File other : openTabs) {   // two files with the same name (e.g. strings.xml): add the folder to tell them apart
                if (other != file && other.getName().equals(label) && file.getParentFile() != null) { label = file.getParentFile().getName() + "/" + label; break; }
            }
            TextView name = tab.findViewById(R.id.tab_name);
            name.setText(label);
            name.setTextColor(color(active ? R.color.night_text : R.color.night_muted));
            ImageView icon = tab.findViewById(R.id.tab_icon);
            boolean java = file.getName().endsWith(".java");
            boolean nativeCode = isNativeSource(file.getName().toLowerCase(Locale.ROOT));
            icon.setImageResource(java ? R.drawable.ic_java : nativeCode ? R.drawable.ic_code : R.drawable.ic_file);
            icon.setImageTintList(ColorStateList.valueOf(color(java ? R.color.cosmic_peach_ink : R.color.cosmic_mint_ink)));
            ((ImageView) tab.findViewById(R.id.tab_close)).setImageTintList(ColorStateList.valueOf(color(active ? R.color.night_text : R.color.night_muted)));
            tab.findViewById(R.id.tab_underline).setVisibility(active ? View.VISIBLE : View.INVISIBLE);
            tab.setBackgroundColor(color(active ? R.color.night_bg : R.color.night_tab));
            tab.setOnClickListener(v -> activateTab(file));
            tab.findViewById(R.id.tab_close).setOnClickListener(v -> closeTab(file));
            strip.addView(tab);
            if (active) activeTab = tab;
        }
        if (activeTab != null) {   // bring the active tab into view once it has been laid out
            final View target = activeTab;
            target.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    v.removeOnLayoutChangeListener(this);
                    int x = v.getLeft() - (scroller.getWidth() - v.getWidth()) / 2;
                    scroller.smoothScrollTo(Math.max(0, x), 0);
                }
            });
        }
    }

    private void readIntoEditor(File file) {
        io.execute(() -> {
            try (InputStream in = new FileInputStream(file)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                copyStream(in, bytes);
                String content = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                runOnUiThread(() -> {
                    if (screen != Screen.EDITOR || codeEditor == null || !file.equals(openedFile)) return;
                    showFileState(true);
                    loadingText = true;
                    codeEditor.setText(content);
                    loadingText = false;
                    dirty = false;
                });
            } catch (Exception e) {
                runOnUiThread(() -> { toast(LanguageUi.READ_FAILED + e.getMessage()); if (screen == Screen.EDITOR) dropTabsUnder(file.getAbsolutePath()); });
            }
        });
    }

    private void showFileState(boolean open) {
        screenView.findViewById(R.id.editor_empty).setVisibility(open ? View.GONE : View.VISIBLE);
        screenView.findViewById(R.id.editor_code_area).setVisibility(open ? View.VISIBLE : View.GONE);
        screenView.findViewById(R.id.editor_tab_bar).setVisibility(open ? View.VISIBLE : View.GONE);
        screenView.findViewById(R.id.editor_wrap).setVisibility(open ? View.VISIBLE : View.GONE);
        screenView.findViewById(R.id.editor_save).setVisibility(open ? View.VISIBLE : View.GONE);
        if (open && openedFile != null) renderTabs();
    }

    private boolean saveCurrent(boolean notify) {
        if (openedFile == null || codeEditor == null) return true;
        if (!dirty) { if (notify) toast(LanguageUi.SAVED); return true; }
        try (OutputStream out = new FileOutputStream(openedFile)) {
            out.write(codeEditor.getText().toString().getBytes(StandardCharsets.UTF_8));
            dirty = false;
            if (currentProject != null) currentProject.setLastModified(System.currentTimeMillis());
            if (notify) toast(LanguageUi.SAVED);
            return true;
        } catch (Exception e) {
            toast(LanguageUi.SAVE_FAILED + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ project structure

    private File[] visibleChildren(File dir) {
        File[] children = dir.listFiles((d, name) -> !name.equals(".git") && !name.equals(".gradle") && !name.equals(".idea"));
        if (children == null) return new File[0];
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return children;
    }

    private void renderTree() {
        if (screen != Screen.EDITOR || screenView == null || currentProject == null) return;
        LinearLayout tree = screenView.findViewById(R.id.tree_container);
        tree.removeAllViews();
        addNodes(tree, currentProject, 0);
    }

    private void addNodes(LinearLayout tree, File dir, int depth) {
        for (File child : visibleChildren(dir)) {
            if (child.isDirectory()) addFolderNode(tree, child, depth); else addFileNode(tree, child, depth);
        }
    }

    private void addFolderNode(LinearLayout tree, File top, int depth) {
        File end = top;
        String label = top.getName(), sub = null;
        boolean packageRoot = label.equals("java") || label.equals("kotlin");
        StringBuilder pkg = new StringBuilder();
        while (true) {
            File[] kids = visibleChildren(end);
            if (kids.length == 1 && kids[0].isDirectory()) {
                end = kids[0];
                if (packageRoot) { if (pkg.length() > 0) pkg.append('.'); pkg.append(end.getName()); }
                else label = label + "/" + end.getName();
            } else break;
        }
        if (packageRoot && pkg.length() > 0) sub = pkg.toString();
        final File target = end;
        final String key = relativePath(top);
        final boolean open = expanded.contains(key);

        View row = getLayoutInflater().inflate(R.layout.item_tree_node, tree, false);
        row.setPadding(dp(16 + depth * 20), 0, row.getPaddingRight(), 0);
        ImageView chevron = row.findViewById(R.id.node_chevron);
        chevron.setImageResource(open ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_right);
        ImageView icon = row.findViewById(R.id.node_icon);
        icon.setImageResource(open ? R.drawable.ic_folder_open : R.drawable.ic_folder);
        icon.setImageTintList(ColorStateList.valueOf(color(R.color.cosmic_primary)));
        ((TextView) row.findViewById(R.id.node_name)).setText(label);
        TextView subtitle = row.findViewById(R.id.node_sub);
        if (sub != null) { subtitle.setText(sub); subtitle.setVisibility(View.VISIBLE); }
        ((TextView) row.findViewById(R.id.node_count)).setText(String.valueOf(visibleChildren(end).length));
        row.setOnClickListener(v -> { if (!expanded.remove(key)) expanded.add(key); renderTree(); });
        row.findViewById(R.id.node_more).setOnClickListener(v -> showSheet(top.getName(), Arrays.asList(
            new SheetAction(LanguageUi.UPLOAD_FILE, R.drawable.ic_upload, false, () -> startUpload(target)),
            new SheetAction(LanguageUi.ADD_FILE, R.drawable.ic_note_add, false, () -> createItem(target, false)),
            new SheetAction(LanguageUi.ADD_FOLDER, R.drawable.ic_create_folder, false, () -> createItem(target, true)),
            new SheetAction(getString(R.string.rename), R.drawable.ic_edit, false, () -> renameItem(top)),
            new SheetAction(getString(R.string.delete), R.drawable.ic_delete, true, () -> deleteItem(top)))));
        tree.addView(row);
        if (open) addNodes(tree, end, depth + 1);
    }

    private void addFileNode(LinearLayout tree, File file, int depth) {
        View row = getLayoutInflater().inflate(R.layout.item_tree_node, tree, false);
        row.setPadding(dp(16 + depth * 20), 0, row.getPaddingRight(), 0);
        row.findViewById(R.id.node_chevron).setVisibility(View.INVISIBLE);
        boolean java = file.getName().endsWith(".java");
        boolean nativeCode = isNativeSource(file.getName().toLowerCase(Locale.ROOT));
        ImageView icon = row.findViewById(R.id.node_icon);
        icon.setImageResource(java ? R.drawable.ic_java : nativeCode ? R.drawable.ic_code : R.drawable.ic_file);
        icon.setImageTintList(ColorStateList.valueOf(color(java ? R.color.cosmic_peach_ink : R.color.cosmic_mint_ink)));
        ((TextView) row.findViewById(R.id.node_name)).setText(file.getName());
        row.findViewById(R.id.node_count).setVisibility(View.GONE);
        row.setOnClickListener(v -> openFile(file));
        row.findViewById(R.id.node_more).setOnClickListener(v -> {
            List<SheetAction> actions = new ArrayList<>();
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".py")) actions.add(new SheetAction("Run with Python", R.drawable.ic_play, false, () -> runPythonFile(file)));
            String lowerName = file.getName().toLowerCase(Locale.ROOT);
            if (lowerName.endsWith(".js") || lowerName.endsWith(".mjs")) actions.add(new SheetAction("Run with JavaScript", R.drawable.ic_play, false, () -> runJavaScriptFile(file)));
            if (lowerName.endsWith(".dart")) actions.add(new SheetAction("Run with Dart", R.drawable.ic_play, false, () -> runDartFile(file)));
            if (lowerName.endsWith(".java")) actions.add(new SheetAction("Run with Java", R.drawable.ic_play, false, () -> runJavaFile(file)));
            if (lowerName.endsWith(".kt")) actions.add(new SheetAction("Run with Kotlin", R.drawable.ic_play, false, () -> runJavaFile(file)));
            actions.add(new SheetAction(getString(R.string.rename), R.drawable.ic_edit, false, () -> renameItem(file)));
            actions.add(new SheetAction(getString(R.string.delete), R.drawable.ic_delete, true, () -> deleteItem(file)));
            showSheet(file.getName(), actions);
        });
        tree.addView(row);
    }

    // ---------------------------------------------------------------- Python

    private final ExecutorService pythonExecutor = Executors.newSingleThreadExecutor();

    /** A scrolling output dialog with a Stop button; the logger may be called from any thread. */
    private final class RunConsole {
        final com.example.studiomobile.build.BuildLogger logger;
        RunConsole(String title, Runnable onStop) {
            TextView output = new TextView(MainActivity.this);
            output.setTypeface(android.graphics.Typeface.MONOSPACE); output.setTextSize(12); output.setTextIsSelectable(true);
            output.setPadding(dp(16), dp(8), dp(16), dp(8)); output.setTextColor(color(R.color.cosmic_ink));
            ScrollView scroll = new ScrollView(MainActivity.this); scroll.addView(output);
            AlertDialog dialog = new MaterialAlertDialogBuilder(MainActivity.this).setTitle(title).setView(scroll)
                .setPositiveButton(LanguageUi.CLOSE, null).setNegativeButton("Stop", null).create();
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> onStop.run());
            logger = new com.example.studiomobile.build.BuildLogger() {
                private void add(String text) { runOnUiThread(() -> { if (output.length() < 200000) output.append(text + "\n"); scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); }); }
                @Override public void onInfo(String message) { add(message); }
                @Override public void onWarning(String message) { add("warning: " + message); }
                @Override public void onError(String message, Throwable cause) { add(message); }
            };
        }
    }

    /** One Run at a time: the first keeps the gate, a second request is cancelled on the spot (the runners share compile folders). */
    private final com.example.studiomobile.RunGate runGate = new com.example.studiomobile.RunGate();

    private com.example.studiomobile.RunGate.Ticket beginRun() {
        com.example.studiomobile.RunGate.Ticket ticket = runGate.tryAcquire();
        if (ticket == null) toast("Another Run is still active, so this one was cancelled. Press Stop on the first one to run again.");
        return ticket;
    }

    /** Opens the output window; if that fails the gate is freed again, otherwise Run would stay locked. */
    private RunConsole openConsole(com.example.studiomobile.RunGate.Ticket ticket, String title, Runnable onStop) {
        try { return new RunConsole(title, onStop); }
        catch (RuntimeException error) { ticket.release(); throw error; }
    }

    /** Runs a .js file with Rhino in-process (works on every ABI, including 32-bit phones). */
    private void runJavaScriptFile(File file) {
        if (dirty && file.equals(openedFile)) { toast("Save the file before running it"); return; }
        com.example.studiomobile.RunGate.Ticket ticket = beginRun();
        if (ticket == null) return;
        com.example.studiomobile.script.JavaScriptRunner runner = new com.example.studiomobile.script.JavaScriptRunner();
        // Rhino runs inside this process and cannot be killed. If the script ignores Stop (for example inside a long regular expression),
        // the gate is freed 3 s later so Run is never locked for good.
        RunConsole console = openConsole(ticket, file.getName(), () -> {
            runner.cancel();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(ticket::release, 3000);
        });
        // Own thread: a script that hangs must not block the executor the Python and Dart runs use.
        Thread jsThread = new Thread(() -> {
            try {
                String source = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                console.logger.onInfo("$ rhino " + file.getName());
                int code = runner.run(source, file.getName(), 60, console.logger);
                if (code >= 0) console.logger.onInfo("\n[finished with exit code " + code + "]");
            } catch (Throwable error) {
                console.logger.onError("Could not run JavaScript: " + error, error);
            } finally {
                ticket.release();
            }
        }, "js-run");
        jsThread.setDaemon(true);
        jsThread.start();
    }

    /** Runs a .dart file: compiled to kernel by the Dart front end, then executed on the Dart VM (64-bit devices only). */
    private void runDartFile(File file) {
        if (dirty && file.equals(openedFile)) { toast("Save the file before running it"); return; }
        com.example.studiomobile.RunGate.Ticket ticket = beginRun();
        if (ticket == null) return;
        java.util.concurrent.atomic.AtomicReference<com.example.studiomobile.dart.DartScriptRunner> running = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<com.example.studiomobile.dart.DartProvisioner> installing = new java.util.concurrent.atomic.AtomicReference<>();
        RunConsole console = openConsole(ticket, file.getName(), () -> {
            com.example.studiomobile.dart.DartScriptRunner runner = running.get(); if (runner != null) runner.cancel();
            com.example.studiomobile.dart.DartProvisioner provisioner = installing.get(); if (provisioner != null) provisioner.cancel();
        });
        com.example.studiomobile.build.BuildLogger logger = console.logger;
        pythonExecutor.execute(() -> {
            try {
                String unsupported = com.example.studiomobile.build.DeviceAbi.require64Bit("Dart", com.example.studiomobile.build.DeviceAbi.supportedAbis());
                if (unsupported != null) { logger.onError(unsupported, null); return; }
                File filesDirectory = getFilesDir(), nativeDirectory = new File(getApplicationInfo().nativeLibraryDir);
                if (!com.example.studiomobile.dart.DartRuntime.isBundled(nativeDirectory)) {
                    logger.onError("This build of Studio Mobile does not contain the Dart runtime. Run the workflow once with build_dart, then build the APK again.", null);
                    return;
                }
                if (!com.example.studiomobile.dart.DartRuntime.isSdkInstalled(new File(filesDirectory, "dart-sdk"))) {
                    com.example.studiomobile.build.dependency.BuildToolCatalog catalog;
                    try (InputStream in = getAssets().open("build-tools/catalog.json")) {
                        catalog = com.example.studiomobile.build.dependency.BuildToolCatalog.readVerified(in, com.example.studiomobile.build.dependency.BuildToolCatalog.BUNDLED_CATALOG_SHA256);
                    }
                    com.example.studiomobile.dart.DartProvisioner provisioner = new com.example.studiomobile.dart.DartProvisioner(filesDirectory, catalog, Arrays.asList(android.os.Build.SUPPORTED_ABIS));
                    installing.set(provisioner);
                    if (provisioner.install(logger) == null) return;
                }
                List<String> problems = new ArrayList<>();
                com.example.studiomobile.dart.DartRuntime runtime = com.example.studiomobile.dart.DartRuntime.locate(filesDirectory, nativeDirectory, problems);
                if (runtime == null) { for (String problem : problems) logger.onError(problem, null); return; }
                com.example.studiomobile.dart.DartScriptRunner runner = new com.example.studiomobile.dart.DartScriptRunner(runtime, new File(filesDirectory, "dart-home"), getCacheDir());
                running.set(runner);
                logger.onInfo("$ dart " + file.getName());
                int code = runner.run(file, 180, logger);
                logger.onInfo(code == -1 ? "\n[timed out or stopped]" : "\n[process finished with exit code " + code + "]");
            } catch (Exception error) {
                logger.onError("Could not run Dart: " + error, error);
            } finally {
                ticket.release();
            }
        });
    }

    /** Runs a .py file with the bundled CPython and shows its output live. The standard library is downloaded once on first use. */
    private void runPythonFile(File file) {
        if (dirty && file.equals(openedFile)) { toast("Save the file before running it"); return; }
        com.example.studiomobile.RunGate.Ticket ticket = beginRun();
        if (ticket == null) return;
        java.util.concurrent.atomic.AtomicReference<com.example.studiomobile.python.PythonScriptRunner> running = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<com.example.studiomobile.python.PythonProvisioner> installing = new java.util.concurrent.atomic.AtomicReference<>();
        RunConsole console = openConsole(ticket, file.getName(), () -> {
            com.example.studiomobile.python.PythonScriptRunner runner = running.get(); if (runner != null) runner.cancel();
            com.example.studiomobile.python.PythonProvisioner provisioner = installing.get(); if (provisioner != null) provisioner.cancel();
        });
        com.example.studiomobile.build.BuildLogger logger = console.logger;
        pythonExecutor.execute(() -> {
            try {
                File filesDirectory = getFilesDir(), nativeDirectory = new File(getApplicationInfo().nativeLibraryDir);
                String unsupported = com.example.studiomobile.build.DeviceAbi.require64Bit("Python", com.example.studiomobile.build.DeviceAbi.supportedAbis());
                if (unsupported != null) { logger.onError(unsupported, null); return; }
                if (!com.example.studiomobile.python.PythonRuntime.isInterpreterBundled(nativeDirectory)) {
                    logger.onError("This build of Studio Mobile does not contain the Python interpreter (libpythonexe.so). Build the app with the Python step of .github/workflows/build.yml.", null);
                    return;
                }
                if (com.example.studiomobile.python.PythonRuntime.findStandardLibrary(new File(filesDirectory, "python")) == null) {
                    com.example.studiomobile.build.dependency.BuildToolCatalog catalog;
                    try (InputStream in = getAssets().open("build-tools/catalog.json")) {
                        catalog = com.example.studiomobile.build.dependency.BuildToolCatalog.readVerified(in, com.example.studiomobile.build.dependency.BuildToolCatalog.BUNDLED_CATALOG_SHA256);
                    }
                    com.example.studiomobile.python.PythonProvisioner provisioner = new com.example.studiomobile.python.PythonProvisioner(filesDirectory, catalog, Arrays.asList(android.os.Build.SUPPORTED_ABIS));
                    installing.set(provisioner);
                    if (provisioner.install(logger) == null) return;
                }
                List<String> problems = new ArrayList<>();
                com.example.studiomobile.python.PythonRuntime runtime = com.example.studiomobile.python.PythonRuntime.locate(filesDirectory, nativeDirectory, problems);
                if (runtime == null) { for (String problem : problems) logger.onError(problem, null); return; }
                com.example.studiomobile.python.PythonScriptRunner runner = new com.example.studiomobile.python.PythonScriptRunner(runtime, new File(filesDirectory, "python-home"), getCacheDir());
                running.set(runner);
                logger.onInfo("$ python " + file.getName());
                int code = runner.run(file, 120, logger);
                logger.onInfo(code == -1 ? "\n[timed out after 120 s]" : "\n[process finished with exit code " + code + "]");
            } catch (Exception error) {
                logger.onError("Could not run Python: " + error, error);
            } finally {
                ticket.release();
            }
        });
    }

    private final ExecutorService javaExecutor = Executors.newSingleThreadExecutor();

    /** Compiles a .java or .kt file (in-process ECJ or Kotlin compiler, then D8) and runs its main() in a separate process, showing the output live. */
    private void runJavaFile(File file) {
        if (dirty && file.equals(openedFile)) { toast("Save the file before running it"); return; }
        final long timeoutSeconds = 120;
        com.example.studiomobile.RunGate.Ticket ticket = beginRun();
        if (ticket == null) return;
        java.util.concurrent.atomic.AtomicReference<com.example.studiomobile.javarun.JavaFileRunner> running = new java.util.concurrent.atomic.AtomicReference<>();
        RunConsole console = openConsole(ticket, file.getName(), () -> {
            com.example.studiomobile.javarun.JavaFileRunner runner = running.get(); if (runner != null) runner.cancel();
        });
        com.example.studiomobile.build.BuildLogger logger = console.logger;
        javaExecutor.execute(() -> {
            try {
                com.example.studiomobile.javarun.JavaFileRunner runner = new com.example.studiomobile.javarun.JavaFileRunner(getApplicationContext(), toolsDir());
                running.set(runner);
                int code = runner.run(file, currentProject, timeoutSeconds, logger);
                logger.onInfo(com.example.studiomobile.javarun.JavaFileRunner.describe(code, timeoutSeconds));
            } catch (Exception error) {
                logger.onError("Could not run: " + error.getMessage(), error);
            } finally {
                ticket.release();
            }
        });
    }

    private void expandAncestors(File from) {
        File node = from;
        while (node != null && !node.equals(currentProject)) { expanded.add(relativePath(node)); node = node.getParentFile(); }
    }

    private void createItem(File parent, boolean folder) {
        String note = parent.equals(currentProject) ? LanguageUi.ROOT_NOTE : null;
        inputDialog(folder ? LanguageUi.NEW_FOLDER : LanguageUi.NEW_FILE, note, folder ? LanguageUi.FOLDER_NAME : LanguageUi.FILE_NAME, "", LanguageUi.CREATE, value -> {
            if (!validName(value)) { toast(LanguageUi.ENTER_VALID_NAME); return; }
            File created = new File(parent, value);
            if (created.exists()) { toast(LanguageUi.NAME_EXISTS); return; }
            try {
                if (folder) { if (!created.mkdirs()) throw new IOException("Could not create folder"); }
                else if (!created.createNewFile()) throw new IOException("Could not create file");
            } catch (IOException e) { toast(e.getMessage()); return; }
            currentProject.setLastModified(System.currentTimeMillis());
            expandAncestors(parent);
            renderTree();
            if (!folder) openFile(created);
        });
    }

    private void startUpload(File target) {
        uploadTarget = target;
        if (target.equals(currentProject)) {
            new MaterialAlertDialogBuilder(this).setTitle(LanguageUi.UPLOAD_ROOT_TITLE).setMessage(LanguageUi.ROOT_NOTE)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(LanguageUi.CHOOSE_FILES, (d, w) -> pickUploads.launch(new String[]{"*/*"})).show();
            return;
        }
        pickUploads.launch(new String[]{"*/*"});
    }

    private void uploadFiles(List<Uri> uris, File target) {
        io.execute(() -> {
            try {
                for (Uri uri : uris) {
                    File file = uniqueFile(target, safeName(displayName(uri), "file"));
                    try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
                        if (in == null) throw new IOException("Could not read the selected file");
                        copyStream(in, out);
                    }
                }
                runOnUiThread(() -> {
                    if (currentProject != null) currentProject.setLastModified(System.currentTimeMillis());
                    expandAncestors(target);
                    renderTree();
                    toast(LanguageUi.UPLOADED);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { renderTree(); toast(LanguageUi.UPLOAD_FAILED + e.getMessage()); });
            }
        });
    }

    private void renameItem(File item) {
        inputDialog(getString(R.string.rename), LanguageUi.NEW_NAME, item.getName(), getString(R.string.rename), value -> {
            if (!validName(value)) { toast(LanguageUi.ENTER_VALID_NAME); return; }
            File renamed = new File(item.getParentFile(), value);
            if (renamed.exists()) { toast(LanguageUi.NAME_EXISTS); return; }
            saveCurrent(false);
            String oldPath = item.getAbsolutePath();
            if (!item.renameTo(renamed)) { toast(LanguageUi.RENAME_FAILED); return; }
            if (openedFile != null) {
                String openedPath = openedFile.getAbsolutePath();
                if (openedPath.equals(oldPath) || openedPath.startsWith(oldPath + File.separator))
                    openedFile = new File(renamed.getAbsolutePath() + openedPath.substring(oldPath.length()));
            }
            remapTabs(oldPath, renamed);
            renderTabs();
            expanded.remove(relativePath(item));
            currentProject.setLastModified(System.currentTimeMillis());
            renderTree();
            toast(LanguageUi.RENAMED);
        });
    }

    private void deleteItem(File item) {
        confirmDelete(item, () -> {
            dropTabsUnder(item.getAbsolutePath());
            currentProject.setLastModified(System.currentTimeMillis());
            renderTree();
        });
    }

    // ------------------------------------------------------------------ live sync (changes made by the AI through MCP)

    /** Called on the UI thread for every change MCP makes. Bursts are merged: sync runs 300 ms after the last change, and at the latest 1.5 s after the first. */
    private void onProjectChanged(File project, boolean structure) {
        if (currentProject == null || !project.getName().equals(currentProject.getName())) return;
        if (screen != Screen.EDITOR && screen != Screen.PREVIEW) return;
        pendingStructure |= structure;
        long now = System.currentTimeMillis();
        if (pendingSince == 0) pendingSince = now;
        uiHandler.removeCallbacks(liveSyncRunnable);
        uiHandler.postDelayed(liveSyncRunnable, now - pendingSince >= LIVE_MAX_WAIT_MS ? 0 : LIVE_DEBOUNCE_MS);
    }

    /**
     * The project folder can be deleted outside the app (for example in a file manager) while the editor or the preview is open.
     * There is nothing left to edit then, so go back to the project list instead of showing a stale tree. Returns true when it left.
     */
    private boolean leaveIfProjectGone() {
        if (currentProject == null || currentProject.isDirectory()) return false;
        if (screen != Screen.EDITOR && screen != Screen.PREVIEW) return false;
        toast("The project folder no longer exists. Back to the project list.");
        showHome();
        return true;
    }

    private void runLiveSync() {
        boolean structure = pendingStructure;
        pendingStructure = false; pendingSince = 0;
        if (currentProject == null || screenView == null) return;
        if (leaveIfProjectGone()) return;
        if (screen == Screen.EDITOR) syncEditor(structure);
        else if (screen == Screen.PREVIEW) loadPreview();
    }

    private void syncEditor(boolean structure) {
        if (structure) {
            boolean removed = false;
            File activeBefore = openedFile;
            for (int i = openTabs.size() - 1; i >= 0; i--) {
                File tab = openTabs.get(i);
                if (!tab.isFile()) { dropTabsUnder(tab.getAbsolutePath()); removed = true; }
            }
            renderTree();
            if (removed) toast(LanguageUi.REMOVED_BY_MCP);
            if (removed && !java.util.Objects.equals(activeBefore, openedFile)) return;   // another file was opened and is being read
        }
        reloadOpenedFromDisk();
    }

    /** Shows what MCP wrote to the open file, without touching the user's unsaved edits. */
    private void reloadOpenedFromDisk() {
        final File file = openedFile;
        if (file == null || codeEditor == null || !file.isFile()) return;
        final AbortController.Signal signal = syncAbort.renew();
        signal.attach(liveExecutor.submit(() -> {
            String disk;
            try (InputStream in = new FileInputStream(file)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                copyStream(in, bytes);
                disk = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception unreadable) { return; }   // being replaced right now; the next change event reads it again
            if (signal.isAborted()) return;
            final String content = disk;
            runOnUiThread(() -> applyDiskText(file, content, signal));
        }));
    }

    private void applyDiskText(File file, String disk, AbortController.Signal signal) {
        if (signal.isAborted() || screen != Screen.EDITOR || codeEditor == null || !file.equals(openedFile)) return;
        Editable text = codeEditor.getText();
        String current = text.toString();
        if (current.equals(disk)) return;
        if (dirty) { toast(LanguageUi.CHANGED_BY_MCP_DIRTY); return; }
        // Replace only the part that differs, so cursor, scroll position and colors elsewhere in the file stay where they are.
        int max = Math.min(current.length(), disk.length());
        int prefix = 0;
        while (prefix < max && current.charAt(prefix) == disk.charAt(prefix)) prefix++;
        int suffix = 0, maxSuffix = max - prefix;
        while (suffix < maxSuffix && current.charAt(current.length() - 1 - suffix) == disk.charAt(disk.length() - 1 - suffix)) suffix++;
        loadingText = true;
        try { text.replace(prefix, current.length() - suffix, disk, prefix, disk.length() - suffix); }
        finally { loadingText = false; }
        dirty = false;
    }

    // ------------------------------------------------------------------ preview

    /**
     * Interactive preview on its own page: a phone design whose bars follow the project's theme and code.
     * Layout XML is built with AAPT2 only (no Java, DEX or signing) and inflated with the project's own resources;
     * assets HTML is shown in a real WebView. Project Java code is not run.
     */
    private void showPreview() {
        if (currentProject == null) { showHome(); return; }
        saveCurrent(false);
        previewOpened = openedFile;
        show(Screen.PREVIEW, R.layout.screen_preview, false, R.color.night_bg);
        click(R.id.preview_back, this::goBack);
        click(R.id.preview_refresh, this::showPreview);
        click(R.id.preview_tip, () -> screenView.findViewById(R.id.preview_tip).setVisibility(View.GONE));
        loadPreview();
    }

    /**
     * Builds the preview from what is on disk now. Each call aborts the one before it, so rapid changes from MCP never pile up
     * and an outdated result is never drawn over a newer one.
     */
    private void loadPreview() {
        final File project = currentProject, opened = previewOpened;
        final int token = ++previewToken;
        final AbortController.Signal signal = previewAbort.renew();
        signal.attach(previewExecutor.submit(() -> {
            if (signal.isAborted()) return;
            com.example.studiomobile.build.LocalLibsInstaller.install(this);
            ProjectPreview.Prepared prepared;
            try { prepared = ProjectPreview.prepare(this, project, opened); }
            catch (Throwable failure) { prepared = new ProjectPreview.Prepared(); prepared.error = "Preview failed: " + failure; }
            if (signal.isAborted()) return;
            final ProjectPreview.Prepared result = prepared;
            runOnUiThread(() -> { if (!signal.isAborted() && screen == Screen.PREVIEW && token == previewToken) renderPreview(result); });
        }));
    }

    private void renderPreview(ProjectPreview.Prepared prepared) {
        FrameLayout stage = screenView.findViewById(R.id.preview_stage);
        screenView.findViewById(R.id.preview_loading).setVisibility(View.GONE);
        TextView message = screenView.findViewById(R.id.preview_message);
        PreviewRenderer.PhoneView old = previewPhone;   // set when this is a live refresh of a preview that is already showing
        if (prepared.error != null) {
            if (old != null) { stage.removeView(old); PreviewRenderer.release(old); previewPhone = null; }   // never keep showing an outdated screen
            ((TextView) screenView.findViewById(R.id.preview_mode)).setText(R.string.preview);
            message.setText(prepared.error);
            message.setVisibility(View.VISIBLE);
            return;
        }
        message.setVisibility(View.GONE);
        screenView.findViewById(R.id.preview_tip).setVisibility(View.GONE);
        ((TextView) screenView.findViewById(R.id.preview_title)).setText(prepared.title);
        ((TextView) screenView.findViewById(R.id.preview_mode)).setText(prepared.modeLabel);
        PreviewRenderer.PhoneView phone = PreviewRenderer.render(this, prepared, this::showPreviewTip);
        if (old != null) { stage.removeView(old); PreviewRenderer.release(old); }   // swap only once the new one is ready: no blank flash
        previewPhone = phone;
        stage.addView(phone, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        if (prepared.warning != null) showPreviewTip(prepared.warning);
        else if (prepared.landscape && prepared.orientationInfo != null) showPreviewTip(prepared.orientationInfo);
    }

    /** Tapping a bar in the preview explains where its value comes from. */
    private void showPreviewTip(String text) {
        if (screen != Screen.PREVIEW || screenView == null) return;
        TextView tip = screenView.findViewById(R.id.preview_tip);
        tip.setText(text);
        tip.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(hidePreviewTip);
        uiHandler.postDelayed(hidePreviewTip, 9000);
    }

    private final Runnable hidePreviewTip = () -> {
        if (screen == Screen.PREVIEW && screenView != null) screenView.findViewById(R.id.preview_tip).setVisibility(View.GONE);
    };

    // ------------------------------------------------------------------ build

    /** The build page is only a fullscreen log. Pressing Build in the editor opens it and starts the build immediately. */
    private void showBuild(boolean start) {
        if (currentProject == null) { showHome(); return; }
        show(Screen.BUILD, R.layout.screen_build, false, R.color.log_bg);
        ((TextView) screenView.findViewById(R.id.build_project_name)).setText(currentProject.getName());
        click(R.id.build_back, this::goBack);
        click(R.id.btn_copy_log, () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            // Copy exactly what the Build screen shows, not the full internal log.
            String shownLog = buildService.getDisplayLog();
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.build_log), shownLog.isEmpty() ? getString(R.string.no_output) : shownLog));
            toast(LanguageUi.LOG_COPIED);
        });
        // While a finger is on the log, the text under it is not touched; it catches up a moment after the finger lifts.
        logTouching = false;
        screenView.findViewById(R.id.log_scroll).setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) logTouching = true;
            else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                logTouching = false;
                uiHandler.postDelayed(this::scheduleBuildRender, 400);
            }
            return false;
        });
        if (start && !buildService.isBuilding()) startBuild(); else renderBuildState();
    }

    private boolean logTouching;

    /** While a build runs the log is redrawn about once a second so the running clock on its last line keeps ticking. */
    private final Runnable buildLiveTick = new Runnable() {
        @Override public void run() {
            if (screen != Screen.BUILD || screenView == null || buildService == null) return;
            renderBuildState();
        }
    };

    // The log is redrawn at most a few times per second. A failed Gradle build writes hundreds of lines in a burst; redrawing the whole
    // log for each one used to keep the UI thread busy for many seconds ("Studio Mobile is frozen").
    private static final long BUILD_RENDER_MIN_INTERVAL_MS = 250;
    private static final int MAX_SHOWN_LOG_CHARS = 150_000;
    private long lastBuildRenderAt;
    private boolean buildRenderScheduled;
    private final Runnable buildRenderRunnable = () -> {
        buildRenderScheduled = false;
        lastBuildRenderAt = android.os.SystemClock.uptimeMillis();
        renderBuildState();
    };

    /** UI thread only. Many calls in a row collapse into one redraw. */
    private void scheduleBuildRender() {
        if (screen != Screen.BUILD || buildRenderScheduled) return;
        buildRenderScheduled = true;
        long wait = Math.max(0, BUILD_RENDER_MIN_INTERVAL_MS - (android.os.SystemClock.uptimeMillis() - lastBuildRenderAt));
        uiHandler.postDelayed(buildRenderRunnable, wait);
    }

    /** Shows only the end of a very long log; Copy log still copies everything. */
    private static String tailOfLog(String text) {
        if (text.length() <= MAX_SHOWN_LOG_CHARS) return text;
        int from = text.indexOf('\n', text.length() - MAX_SHOWN_LOG_CHARS);
        if (from < 0) from = text.length() - MAX_SHOWN_LOG_CHARS;
        return "... (" + from + " earlier characters are not shown here; Copy log copies the whole log)\n" + text.substring(from + 1);
    }

    private void renderBuildState() {
        if (screen != Screen.BUILD || screenView == null) return;
        TextView full = screenView.findViewById(R.id.full_log_text);
        ScrollView scroll = screenView.findViewById(R.id.log_scroll);
        boolean building = buildService.isBuilding();
        // Keep following the end of the log only when the reader is already at the end, so scrolling up to read is not undone every second.
        View content = scroll.getChildAt(0);
        boolean atEnd = content == null || content.getBottom() - (scroll.getHeight() + scroll.getScrollY()) < 120 * getResources().getDisplayMetrics().density;
        final int keepY = scroll.getScrollY();
        // While the reader holds a text selection, a running build does not replace the text under their finger.
        boolean selecting = building && (full.hasSelection() || logTouching);
        if (!selecting) {
            String logText = buildService.getDisplayLog();
            String shown = logText.isEmpty() ? getString(R.string.no_output) : tailOfLog(logText);
            // Same text as before: touching it would only reset the cursor and make the ScrollView jump.
            if (!shown.equals(full.getTag(R.id.full_log_text))) {
                full.setTag(R.id.full_log_text, shown);
                // EmojiCompat scans every character on the UI thread; for a log of hundreds of KB that took more than 10 seconds.
                if (full instanceof androidx.appcompat.widget.AppCompatTextView) ((androidx.appcompat.widget.AppCompatTextView) full).setEmojiCompatEnabled(false);
                full.setFilters(new android.text.InputFilter[0]);
                // While the build runs the text changes every second (the running clock of the last step). A selectable TextView puts its
                // cursor back at position 0 on every setText and scrolls the ScrollView to the top, so it is not selectable during the build.
                if (building && full.isTextSelectable()) full.setTextIsSelectable(false);
                applyLogText(full, shown);
                if (!atEnd) holdScrollPosition(scroll, keepY);
            }
        }
        // Selecting text is allowed again once the build is over and the text no longer changes.
        if (!building && !full.isTextSelectable()) {
            full.setTextIsSelectable(true);
            if (!atEnd) holdScrollPosition(scroll, keepY);
        }
        screenView.findViewById(R.id.build_progress).setVisibility(building ? View.VISIBLE : View.GONE);
        // A wake lock only keeps the CPU running, not the display. While the build page is open and a build runs, keep the screen on too.
        if (building) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        uiHandler.removeCallbacks(buildLiveTick);
        if (building) uiHandler.postDelayed(buildLiveTick, 1000);
        // Scroll to the end directly. fullScroll() also moves the keyboard focus, which could pull the view back to the cursor.
        if (atEnd && !logTouching) scroll.post(() -> {
            View child = scroll.getChildAt(0);
            if (child != null && !logTouching) scroll.scrollTo(0, Math.max(0, child.getHeight() - scroll.getHeight()));
        });
    }

    /**
     * Replaces only the part of the text that changed (the new lines at the end and the running clocks of the current steps), not the
     * whole text. A full setText re-lays out everything and, on a selectable TextView, moves the cursor and the scroll position.
     */
    private static void applyLogText(TextView full, String shown) {
        CharSequence current = full.getText();
        if (!(current instanceof android.text.Editable)) { full.setText(shown, TextView.BufferType.EDITABLE); return; }
        android.text.Editable text = (android.text.Editable) current;
        int oldLength = text.length(), newLength = shown.length(), prefix = 0, limit = Math.min(oldLength, newLength);
        while (prefix < limit && text.charAt(prefix) == shown.charAt(prefix)) prefix++;
        int oldEnd = oldLength, newEnd = newLength;
        while (oldEnd > prefix && newEnd > prefix && text.charAt(oldEnd - 1) == shown.charAt(newEnd - 1)) { oldEnd--; newEnd--; }
        text.replace(prefix, oldEnd, shown, prefix, newEnd);
    }

    /**
     * Keeps the reader where they are after the text was replaced. TextView registers its own pre-draw step during layout, after any
     * listener added here, and that step can scroll to the cursor again, so the position is put back right before the next frames
     * are drawn and once more shortly after.
     */
    private void holdScrollPosition(final ScrollView scroll, final int y) {
        scroll.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                android.view.ViewTreeObserver observer = scroll.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                scroll.scrollTo(0, y);
                return true;
            }
        });
        Runnable restore = () -> { if (screen == Screen.BUILD && scroll.getScrollY() != y && scroll.getScrollY() == 0) scroll.scrollTo(0, y); };
        scroll.post(restore);
        scroll.postDelayed(restore, 60);
        scroll.postDelayed(restore, 200);
    }

    private void startBuild() {
        if (buildService.isBuilding()) { toast(LanguageUi.BUILD_BUSY); return; }
        if (currentProject == null) { toast(LanguageUi.NO_PROJECT); return; }
        if (shouldAskBatteryExemption()) { askBatteryExemption(this::beginBuild); return; }
        beginBuild();
    }

    private void beginBuild() {
        if (buildService.isBuilding() || currentProject == null) return;
        saveCurrent(false);
        boolean bundle = bundleNext;
        bundleNext = false;
        if (buildService.start(currentProject, bundle) < 0) { toast(LanguageUi.BUILD_BUSY); return; }
        renderBuildState();
    }

    // ------------------------------------------------------------------ battery optimization (asked once)

    private static final String BATTERY_PREFS = "studio_battery";
    private static final String BATTERY_ASKED = "asked";

    /** True only when the app is still battery-optimized and the user has never been asked, so the question never repeats. */
    private boolean shouldAskBatteryExemption() {
        try {
            android.os.PowerManager power = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power == null || power.isIgnoringBatteryOptimizations(getPackageName())) return false;
            return !getSharedPreferences(BATTERY_PREFS, MODE_PRIVATE).getBoolean(BATTERY_ASKED, false);
        } catch (RuntimeException unavailable) { return false; }
    }

    private void askBatteryExemption(Runnable thenBuild) {
        try { getSharedPreferences(BATTERY_PREFS, MODE_PRIVATE).edit().putBoolean(BATTERY_ASKED, true).apply(); } catch (RuntimeException ignored) { }
        new MaterialAlertDialogBuilder(this).setTitle(LanguageUi.BATTERY_TITLE).setMessage(LanguageUi.BATTERY_MESSAGE)
            .setPositiveButton(LanguageUi.BATTERY_ALLOW, (d, w) -> { requestBatteryExemption(); thenBuild.run(); })
            .setNegativeButton(LanguageUi.BATTERY_LATER, (d, w) -> thenBuild.run())
            .setOnCancelListener(d -> thenBuild.run())
            .show();
    }

    private void requestBatteryExemption() {
        try {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException refused) {
            try { startActivity(new android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (RuntimeException ignored) { }
        }
    }

    /** Called on the UI thread whenever the build service changes state (started from the screen or from MCP). */
    private void onBuildStateChanged(ProjectBuildService.State state) {
        renderBuildState();
        if (state == ProjectBuildService.State.FAILED) { showChecksumFailure(buildService.takeChecksumFailure()); return; }
        if (state != ProjectBuildService.State.SUCCESS) return;
        if (buildService.wasBundle()) {
            File aab = buildService.getLastAab();
            if (aab == null) return;
            new MaterialAlertDialogBuilder(this).setTitle(LanguageUi.AAB_DONE_TITLE).setMessage(LanguageUi.aabDoneMessage(aab.getAbsolutePath()))
                .setPositiveButton(R.string.ok, null).show();
            return;
        }
        File apk = buildService.getLastApk();
        if (apk == null) return;
        // Save APK keeps a copy in Studio Mobile/My Apk, named after the app. Install APK only installs: nothing is saved or downloaded.
        new MaterialAlertDialogBuilder(this).setTitle(LanguageUi.APK_BUILT_TITLE).setMessage(LanguageUi.APK_BUILT_MESSAGE)
            .setPositiveButton(LanguageUi.SAVE_APK, (d, w) -> saveApkToStudioFolder())
            .setNeutralButton(LanguageUi.INSTALL_APK, (d, w) -> installApk(buildService.getLastApk()))
            .setNegativeButton(LanguageUi.CLOSE, null).show();
    }

    /** A library failed its SHA check (or the project's checksums.properties is wrong): say so in a dialog, with the values to compare. */
    private void showChecksumFailure(ChecksumFailure failure) {
        if (failure == null) return;
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this).setTitle(failure.title()).setMessage(failure.details())
            .setPositiveButton(LanguageUi.CLOSE, null);
        final String pinLine = failure.pinLine();
        if (pinLine != null) {
            boolean pinKnown = failure.kind() == ChecksumFailure.Kind.SERVER_SHA1_MISSING;
            dialog.setNeutralButton(pinKnown ? LanguageUi.COPY_PIN_LINE : LanguageUi.COPY_SHA256, (d, w) -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("SHA-256", pinKnown ? pinLine : failure.receivedSha256()));
                toast(LanguageUi.PIN_LINE_COPIED);
            });
        }
        dialog.show();
    }

    private void saveApkToStudioFolder() {
        File apk = buildService.getLastApk();
        if (apk == null || !apk.isFile()) return;
        final String name = buildService.getLastAppName().isEmpty() ? "app" : buildService.getLastAppName();
        io.execute(() -> {
            File target = new File(StudioStorage.apkDir(this), name + ".apk");
            try (InputStream in = new FileInputStream(apk); OutputStream out = new FileOutputStream(target)) {
                copyStream(in, out);
                runOnUiThread(() -> toast(LanguageUi.APK_SAVED + target.getAbsolutePath()));
            } catch (Exception e) {
                runOnUiThread(() -> toast(LanguageUi.APK_SAVE_FAILED + e.getMessage()));
            }
        });
    }

    /** Opens the system installer for the built APK. Nothing is copied or saved. */
    private void installApk(File apk) {
        if (apk == null || !apk.isFile()) { toast(LanguageUi.APK_MISSING); return; }
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast(LanguageUi.INSTALL_PERMISSION);
            pendingInstallApk = apk;   // installation continues by itself when the user returns with the permission granted
            try { startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
            catch (Exception ignored) { pendingInstallApk = null; }
            return;
        }
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            toast(LanguageUi.INSTALL_FAILED + e.getMessage());
        }
    }

    private void showToolchainStatus() {
        io.execute(() -> {
            com.example.studiomobile.build.LocalLibsInstaller.install(this);
            List<String> missing = new com.example.studiomobile.build.BuildToolchainInspector(this).inspect();
            String shown = missing.isEmpty() ? LanguageUi.TOOLCHAIN_OK : LanguageUi.TOOLCHAIN_MISSING + android.text.TextUtils.join("\n\n- ", missing);
            runOnUiThread(() -> new MaterialAlertDialogBuilder(this).setTitle(LanguageUi.TOOLCHAIN_TITLE).setMessage(shown).setPositiveButton(R.string.ok, null).show());
        });
    }

    // ------------------------------------------------------------------ MCP

    private McpToolRegistry newMcpRegistry(McpSettings settings) {
        return new McpToolRegistry(new ProjectFileOperations(projectsDir()), settings, buildService, toolsDir(), null, new DebugModeSettings(this));
    }

    private String appVersionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "0.1.0"; }
    }

    private void showMcp() {
        show(Screen.MCP, R.layout.screen_mcp, true, R.color.cosmic_white);
        final McpSettings settings = new McpSettings(this);
        click(R.id.mcp_back, this::goBack);

        com.google.android.material.switchmaterial.SwitchMaterial readOnlySwitch = screenView.findViewById(R.id.mcp_read_only_switch);
        readOnlySwitch.setChecked(settings.isReadOnly());
        readOnlySwitch.setOnCheckedChangeListener((button, checked) -> settings.setReadOnly(checked));

        // The relay URL belongs to the user: typed here, stored on this phone, never built into the app.
        EditText urlInput = screenView.findViewById(R.id.mcp_relay_url);
        urlInput.setText(settings.getRelayUrl());
        urlInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { settings.setRelayUrl(text.toString()); renderMcpEndpoint(); }
        });
        EditText tokenInput = screenView.findViewById(R.id.mcp_relay_token);
        tokenInput.setText(settings.getRelayToken());
        tokenInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { settings.setRelayToken(text.toString()); }
        });
        click(R.id.mcp_relay_toggle, () -> toggleMcpRelay(settings, urlInput));
        click(R.id.mcp_endpoint_copy, () -> {
            String endpoint = mcpEndpoint();
            if (endpoint == null) return;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.mcp_endpoint_title), endpoint));
            toast(getString(R.string.mcp_endpoint_copied));
        });

        for (String guide : new String[]{"claude", "chatgpt", "gemini", "code"}) {
            final View body = screenView.findViewById(getResources().getIdentifier("mcp_guide_" + guide + "_body", "id", getPackageName()));
            final View chevron = screenView.findViewById(getResources().getIdentifier("mcp_guide_" + guide + "_chevron", "id", getPackageName()));
            screenView.findViewById(getResources().getIdentifier("mcp_guide_" + guide + "_header", "id", getPackageName())).setOnClickListener(v -> {
                boolean open = body.getVisibility() != View.VISIBLE;
                body.setVisibility(open ? View.VISIBLE : View.GONE);
                chevron.setRotation(open ? 180f : 0f);
            });
        }

        click(R.id.mcp_self_test, this::runMcpSelfTest);
        click(R.id.mcp_toolchain_status, this::showToolchainStatus);
        renderMcpEndpoint();
        renderMcpRelay();
    }

    /** The relay address the user typed, normalized, or null when it is empty or not usable. */
    private String mcpRelayBase() {
        if (screenView == null) return null;
        EditText input = screenView.findViewById(R.id.mcp_relay_url);
        return input == null ? null : McpRelayUrl.normalize(input.getText().toString());
    }

    /** The address to paste into Claude, ChatGPT or Gemini, or null until a valid relay URL is entered. */
    private String mcpEndpoint() {
        String base = mcpRelayBase();
        return base == null ? null : McpRelayUrl.aiEndpoint(base);
    }

    private void toggleMcpRelay(McpSettings settings, EditText urlInput) {
        if (McpRelayService.isRunning()) { McpRelayService.stop(this); return; }
        String base = McpRelayUrl.normalize(urlInput.getText().toString());
        if (base == null) { toast(getString(R.string.mcp_relay_url_invalid)); return; }
        settings.setRelayUrl(base);
        if (!base.equals(urlInput.getText().toString())) urlInput.setText(base);
        // The foreground notification is hidden without this permission on Android 13+, but the connection still works.
        if (android.os.Build.VERSION.SDK_INT >= 33
            && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        McpRelayService.start(this);
    }

    /** Shows the address for the AI and fills the three guides with it. */
    private void renderMcpEndpoint() {
        if (screen != Screen.MCP || screenView == null) return;
        String endpoint = mcpEndpoint();
        TextView text = screenView.findViewById(R.id.mcp_endpoint_text);
        text.setText(endpoint == null ? getString(R.string.mcp_endpoint_empty) : endpoint);
        text.setTextColor(color(endpoint == null ? R.color.cosmic_muted : R.color.cosmic_ink));
        screenView.findViewById(R.id.mcp_endpoint_actions).setVisibility(endpoint == null ? View.GONE : View.VISIBLE);
        String shown = endpoint == null ? getString(R.string.mcp_endpoint_placeholder) : endpoint;
        ((TextView) screenView.findViewById(R.id.mcp_guide_claude_body)).setText(getString(R.string.mcp_guide_claude_body, shown));
        ((TextView) screenView.findViewById(R.id.mcp_guide_chatgpt_body)).setText(getString(R.string.mcp_guide_chatgpt_body, shown));
        ((TextView) screenView.findViewById(R.id.mcp_guide_gemini_body)).setText(getString(R.string.mcp_guide_gemini_body, shown));
        ((TextView) screenView.findViewById(R.id.mcp_guide_code_body)).setText(getString(R.string.mcp_guide_code_body, shown));
    }

    /** Shows the live connection state, taken from the foreground service. */
    private void renderMcpRelay() {
        if (screen != Screen.MCP || screenView == null) return;
        boolean running = McpRelayService.isRunning();
        McpRelayClient.State state = McpRelayService.getState();
        String detail = McpRelayService.getDetail();
        int label; int tint;
        switch (state) {
            case CONNECTING: label = R.string.mcp_status_connecting; tint = R.color.cosmic_primary_dark; break;
            case CONNECTED: label = R.string.mcp_status_connected; tint = R.color.cosmic_mint_ink; break;
            case RECONNECTING: label = R.string.mcp_status_reconnecting; tint = R.color.cosmic_peach_ink; break;
            case FAILED: label = R.string.mcp_status_failed; tint = R.color.cosmic_danger; break;
            default: label = R.string.mcp_connection_none; tint = R.color.cosmic_muted; break;
        }
        TextView value = screenView.findViewById(R.id.mcp_connection_value);
        value.setText(label);
        value.setTextColor(color(tint));
        TextView detailView = screenView.findViewById(R.id.mcp_status_detail);
        detailView.setText(detail);
        detailView.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) screenView.findViewById(R.id.mcp_relay_toggle)).setText(running ? R.string.mcp_disconnect : R.string.mcp_connect);
        screenView.findViewById(R.id.mcp_relay_url).setEnabled(!running);
        screenView.findViewById(R.id.mcp_relay_token).setEnabled(!running);
    }

    /** Sends three real MCP messages through the handler so the user can confirm it works on this device. */
    private void runMcpSelfTest() {
        McpSettings settings = new McpSettings(this);
        McpRequestHandler handler = new McpRequestHandler(newMcpRegistry(settings), settings, appVersionName());
        StringBuilder report = new StringBuilder();
        report.append(mcpSelfTestStep(handler, "initialize", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}")).append('\n');
        report.append(mcpSelfTestStep(handler, "tools/list", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")).append('\n');
        report.append(mcpSelfTestStep(handler, "list_projects", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_projects\",\"arguments\":{}}}"));
        new MaterialAlertDialogBuilder(this).setTitle(R.string.mcp_self_test_title).setMessage(report.toString()).setPositiveButton(R.string.ok, null).show();
    }

    private String mcpSelfTestStep(McpRequestHandler handler, String label, String request) {
        try {
            String response = handler.handle(request);
            if (response == null) return label + ": no response";
            org.json.JSONObject json = new org.json.JSONObject(response);
            if (json.has("error")) return label + ": FAILED - " + json.getJSONObject("error").optString("message");
            org.json.JSONObject result = json.getJSONObject("result");
            if (result.has("tools")) return label + ": OK, " + result.getJSONArray("tools").length() + " tools";
            if (result.has("protocolVersion")) return label + ": OK, protocol " + result.getString("protocolVersion");
            if (result.optBoolean("isError", false)) return label + ": tool returned an error";
            return label + ": OK";
        } catch (Exception e) {
            return label + ": FAILED - " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ keystore (one key per project)

    private File toolsDir() { return new File(getFilesDir(), "build-tools"); }
    private ProjectKeys keys() { return new ProjectKeys(getFilesDir()); }
    private File debugKeyFile() { return new File(toolsDir(), ApkSigningStage.DEBUG_KEYSTORE_FILE); }

    /** Key dialogs show secrets too, so their windows are protected from screenshots and screen recording as well. */
    private void secureDialog(android.app.Dialog dialog) {
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void ensureDefaultKey(Runnable afterReady) {
        io.execute(() -> {
            try {
                ApkSigningStage.ensureDebugKeyStore(toolsDir(), null);
                if (afterReady != null) runOnUiThread(afterReady);
            } catch (Exception e) {
                runOnUiThread(() -> toast(LanguageUi.KEY_DEFAULT_FAILED + e.getMessage()));
            }
        });
    }

    private interface CredentialsHandler { void accept(String alias, String storePassword, String keyPassword); }

    /** Asks for alias and password. Alias and store password are required; the key password is optional. */
    private void credentialsDialog(String title, String message, String aliasPrefill, String positive, CredentialsHandler handler) {
        View view = getLayoutInflater().inflate(R.layout.dialog_keystore_import, null);
        EditText alias = view.findViewById(R.id.import_alias), storePassword = view.findViewById(R.id.import_store_password), keyPassword = view.findViewById(R.id.import_key_password);
        alias.setText(aliasPrefill);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setView(view)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(positive, null).create();
        secureDialog(dialog);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = alias.getText().toString().trim(), sp = storePassword.getText().toString(), kp = keyPassword.getText().toString();
            if (a.isEmpty()) { alias.setError(LanguageUi.ALIAS_REQUIRED); return; }
            if (sp.isEmpty()) { storePassword.setError(LanguageUi.PASSWORD_REQUIRED); return; }
            dialog.dismiss();
            handler.accept(a, sp, kp);
        }));
        dialog.show();
    }

    // ---- the Keystore page of one project: the key it is signed with, plus Copy, Import, Export and Change

    private void showKeystore(File project) {
        keyProject = project;
        shownKey = null;
        keyPasswordVisible = false;
        show(Screen.KEYSTORE, R.layout.screen_keystore, true, R.color.cosmic_white);
        ((TextView) screenView.findViewById(R.id.keystore_project)).setText(project.getName());
        click(R.id.keystore_back, this::goBack);
        click(R.id.key_copy, () -> { if (shownKey != null) copyKey(shownKey); });
        click(R.id.key_export, () -> exportKeystore(project));
        click(R.id.key_import, () -> importKeystoreFlow(project, () -> showKeystore(project)));
        click(R.id.key_change, () -> showKeyForm(project, LanguageUi.KEY_CHANGE_TITLE, LanguageUi.KEY_CHANGE_FORM_MESSAGE, () -> { toast(LanguageUi.KEY_CHANGED); showKeystore(project); }));
        click(R.id.keystore_password_toggle, () -> { keyPasswordVisible = !keyPasswordVisible; bindKeyDetails(); });
        io.execute(() -> {
            try {
                ProjectKeys.Details details = keys().details(project.getName(), toolsDir());
                runOnUiThread(() -> { if (screen == Screen.KEYSTORE && project.equals(keyProject)) { shownKey = details; bindKeyDetails(); } });
            } catch (Exception e) {
                runOnUiThread(() -> toast(LanguageUi.KEY_READ_FAILED + e.getMessage()));
            }
        });
    }

    private void bindKeyDetails() {
        if (screen != Screen.KEYSTORE || shownKey == null) return;
        ProjectKeys.Details d = shownKey;
        String hidden = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";
        ((TextView) screenView.findViewById(R.id.keystore_active)).setText(d.name);
        ((TextView) screenView.findViewById(R.id.keystore_type)).setText(d.isDefault ? LanguageUi.KEY_TYPE_DEFAULT : LanguageUi.KEY_TYPE_CUSTOM);
        ((TextView) screenView.findViewById(R.id.keystore_alias)).setText(d.alias);
        ((TextView) screenView.findViewById(R.id.keystore_store_password)).setText(keyPasswordVisible ? d.storePassword : hidden);
        ((TextView) screenView.findViewById(R.id.keystore_key_password)).setText(keyPasswordVisible ? d.keyPassword : hidden);
        ((TextView) screenView.findViewById(R.id.keystore_owner)).setText(d.subject);
        ((TextView) screenView.findViewById(R.id.keystore_valid)).setText(d.validUntil);
        ((TextView) screenView.findViewById(R.id.keystore_sha256)).setText(d.sha256);
        ((TextView) screenView.findViewById(R.id.keystore_password_toggle)).setText(keyPasswordVisible ? LanguageUi.HIDE : LanguageUi.SHOW);
    }

    private void copyKey(ProjectKeys.Details details) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(getString(R.string.keystore), details.toClipboardText());
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);   // keeps keyboards and clipboard previews from showing the secrets
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        toast(LanguageUi.KEY_COPIED);
    }

    // ---- export the key file of a project: ask alias + password, verify them, then pick the destination

    private void exportKeystore(File project) {
        ProjectKeys store = keys();
        String name = project.getName();
        boolean custom = store.isCustom(name);
        java.util.Properties properties = custom ? store.read(name) : null;
        if (custom && properties == null) { toast(LanguageUi.KEY_NO_FILE); return; }
        File source = custom ? store.keystoreFile(name) : debugKeyFile();
        if (!source.isFile()) {
            if (!custom) { ensureDefaultKey(() -> exportKeystore(project)); return; }
            toast(LanguageUi.KEY_NO_FILE);
            return;
        }
        String alias = custom ? properties.getProperty("alias", "") : ApkSigningStage.DEBUG_KEY_ALIAS;
        String message = custom ? LanguageUi.KEY_EXPORT_MESSAGE : LanguageUi.KEY_EXPORT_MESSAGE_DEBUG;
        String fileName = safeName(name, "key") + (custom ? "" : "-debug") + ".p12";
        credentialsDialog(LanguageUi.KEY_EXPORT_TITLE, message, alias, LanguageUi.KEY_EXPORT_ACTION, (a, sp, kp) -> io.execute(() -> {
            try {
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                try (InputStream in = new FileInputStream(source)) { keyStore.load(in, sp.toCharArray()); }
                if (keyStore.getKey(a, (kp.isEmpty() ? sp : kp).toCharArray()) == null) throw new IOException("alias not found");
                runOnUiThread(() -> { pendingExportSource = source; exportKeystoreLauncher.launch(fileName); });
            } catch (Exception e) {
                runOnUiThread(() -> toast(LanguageUi.KEY_VERIFY_FAILED));
            }
        }));
    }

    private void exportKeystoreTo(Uri uri) {
        File source = pendingExportSource;
        if (source == null || !source.isFile()) return;
        io.execute(() -> {
            try (InputStream in = new FileInputStream(source); OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Could not open the destination");
                copyStream(in, out);
                runOnUiThread(() -> toast(LanguageUi.KEY_EXPORTED));
            } catch (Exception e) {
                runOnUiThread(() -> toast(LanguageUi.KEY_EXPORT_FAILED + e.getMessage()));
            }
        });
    }

    // ---- import a key file into one project: ask alias + password first, then pick the file

    private void importKeystoreFlow(File project, Runnable afterImport) {
        credentialsDialog(LanguageUi.KEY_IMPORT_TITLE, LanguageUi.KEY_IMPORT_MESSAGE, "", LanguageUi.CHOOSE_FILE, (a, sp, kp) -> {
            keyProject = project; afterKeyImport = afterImport;
            pendingImportAlias = a; pendingImportStorePassword = sp; pendingImportKeyPassword = kp;
            pickKeystore.launch(new String[]{"*/*"});
        });
    }

    private void importKeystore(Uri uri, String alias, String storePassword, String keyPasswordInput) {
        final File project = keyProject;
        final Runnable after = afterKeyImport;
        if (project == null) return;
        final String displayName = displayName(uri);
        final String keyPassword = keyPasswordInput.isEmpty() ? storePassword : keyPasswordInput;
        io.execute(() -> {
            File temporary = new File(getCacheDir(), "import-keystore.tmp");
            try {
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(temporary)) {
                    if (in == null) throw new IOException("Could not read the selected file");
                    copyStream(in, out);
                }
                KeyStore store = KeyStore.getInstance("PKCS12");
                try (InputStream in = new FileInputStream(temporary)) { store.load(in, storePassword.toCharArray()); }
                if (store.getKey(alias, keyPassword.toCharArray()) == null) throw new IOException("No private key was found for this alias and password.");
                String base = displayName.toLowerCase(Locale.ROOT).endsWith(".p12") ? displayName.substring(0, displayName.length() - 4) : displayName;
                keys().install(project.getName(), temporary, safeName(base, "key"), alias, storePassword, keyPassword);
                runOnUiThread(() -> { toast(LanguageUi.KEY_IMPORTED); if (after != null) after.run(); });
            } catch (Exception e) {
                runOnUiThread(() -> toast(LanguageUi.KEY_IMPORT_FAILED + e.getMessage()));
            } finally {
                pendingImportStorePassword = ""; pendingImportKeyPassword = "";
                temporary.delete();
            }
        });
    }

    // ---- change key: a form card that creates a new key for the project

    /**
     * The form is one scrolling card: the explanation, all fields and the Cancel / Change Key buttons are inside the same ScrollView
     * (dialog_key_form.xml). A normal AlertDialog keeps its buttons outside the scroll area, and on a short window or with the
     * keyboard open they ended up squeezed or hidden, so they could not be tapped. Here they can always be scrolled into view.
     */
    private void showKeyForm(File project, String title, String message, Runnable onChanged) {
        View view = getLayoutInflater().inflate(R.layout.dialog_key_form, null);
        ((TextView) view.findViewById(R.id.form_message)).setText(message);
        EditText alias = view.findViewById(R.id.form_alias), password = view.findViewById(R.id.form_password), confirm = view.findViewById(R.id.form_password_confirm),
            company = view.findViewById(R.id.form_company), organization = view.findViewById(R.id.form_organization), unit = view.findViewById(R.id.form_unit),
            city = view.findViewById(R.id.form_city), state = view.findViewById(R.id.form_state), country = view.findViewById(R.id.form_country);
        android.widget.Button cancelButton = view.findViewById(R.id.form_cancel), confirmButton = view.findViewById(R.id.form_confirm);
        confirmButton.setText(LanguageUi.KEY_CHANGE_ACTION);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(title).setView(view).create();
        secureDialog(dialog);
        if (dialog.getWindow() != null) {
            // The window shrinks above the keyboard instead of sliding under it.
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        confirmButton.setOnClickListener(v -> {
            String a = alias.getText().toString().trim(), pw = password.getText().toString(), co = company.getText().toString().trim(),
                org = organization.getText().toString().trim(), ou = unit.getText().toString().trim(), ci = city.getText().toString().trim(),
                st = state.getText().toString().trim(), cc = country.getText().toString().trim();
            if (a.isEmpty()) { alias.setError(LanguageUi.ALIAS_REQUIRED); alias.requestFocus(); return; }
            if (pw.length() < 6) { password.setError(LanguageUi.PASSWORD_SHORT); password.requestFocus(); return; }
            if (!pw.equals(confirm.getText().toString())) { confirm.setError(LanguageUi.PASSWORD_MISMATCH); confirm.requestFocus(); return; }
            if (co.isEmpty()) { company.setError(LanguageUi.COMPANY_REQUIRED); company.requestFocus(); return; }
            if (org.isEmpty()) { organization.setError(LanguageUi.ORGANIZATION_REQUIRED); organization.requestFocus(); return; }
            if (!cc.matches("[A-Za-z]{2}")) { country.setError(LanguageUi.COUNTRY_INVALID); country.requestFocus(); return; }
            dialog.dismiss();
            toast(LanguageUi.KEY_CREATING);
            io.execute(() -> {
                try {
                    keys().generate(project.getName(), a, pw, co, org, ou, ci, st, cc);
                    runOnUiThread(onChanged);
                } catch (Exception e) {
                    runOnUiThread(() -> toast(LanguageUi.KEY_CREATE_FAILED + e.getMessage()));
                }
            });
        });
        dialog.show();
    }

    // ---- Export AAB (for Google Play) from the project menu

    private void exportAabFlow(File project) {
        if (buildService.isBuilding()) { toast(LanguageUi.BUILD_BUSY); return; }
        if (keys().isCustom(project.getName())) { startAabBuild(project); return; }
        // Still the Studio Mobile default key: Google Play needs the developer's own key, so ask for one first.
        showKeyForm(project, LanguageUi.AAB_KEY_TITLE, LanguageUi.AAB_KEY_MESSAGE, () -> showAabReadyDialog(project));
    }

    private void showAabReadyDialog(File project) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(LanguageUi.AAB_READY_TITLE).setMessage(LanguageUi.AAB_READY_MESSAGE)
            .setPositiveButton(LanguageUi.EXPORT_AAB, (d, w) -> startAabBuild(project))
            .setNegativeButton(LanguageUi.KEY_IMPORT_TITLE, (d, w) -> importKeystoreFlow(project, () -> showAabReadyDialog(project)))
            .setNeutralButton(R.string.cancel, null).create();
        secureDialog(dialog);
        dialog.show();
    }

    private void startAabBuild(File project) {
        if (buildService.isBuilding()) { toast(LanguageUi.BUILD_BUSY); return; }
        currentProject = project; openedFile = null; openTabs.clear(); expanded.clear();
        bundleNext = true;
        showBuild(true);
    }
}
