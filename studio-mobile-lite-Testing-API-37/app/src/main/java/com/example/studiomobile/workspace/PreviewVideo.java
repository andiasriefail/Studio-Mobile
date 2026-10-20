package com.example.studiomobile.workspace;

import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.VideoView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the video of a project really play in the preview. Java is not run, so the project's own player is not used.
 * Instead the source is read from the code (https URL, res/raw or assets file) and played by the platform's VideoView:
 *  - a VideoView in the layout gets the source (tap it to play or pause),
 *  - an ExoPlayer / Media3 PlayerView in the layout is drawn as a VideoView (see PreviewFactory),
 *  - the picture-in-picture window and the media notification card of the preview use the same source.
 * Only https is played: Studio Mobile does not allow plain http. DRM streams and addresses built in code cannot be found.
 */
final class PreviewVideo {
    private PreviewVideo() { }

    static final class Source { Uri uri; boolean autoStart; }

    private static final String EXT = "(?:mp4|webm|3gp|mkv|m3u8)";
    private static final Pattern CALL_URL = Pattern.compile("(?:setVideoURI\\s*\\(\\s*Uri\\s*\\.\\s*parse|setVideoPath|fromUri|setMediaItem)\\s*\\(\\s*\"(https://[^\"\\s]+)\"");
    private static final Pattern URL_EXT = Pattern.compile("\"(https://[^\"\\s]+\\." + EXT + "(?:\\?[^\"\\s]*)?)\"");
    private static final Pattern RAW = Pattern.compile("\\bR\\s*\\.\\s*raw\\s*\\.\\s*(\\w+)");
    private static final Pattern LOCAL = Pattern.compile("\"([^\"\\s]+\\." + EXT + ")\"");
    private static final Pattern AUTO = Pattern.compile("\\bstart\\s*\\(\\s*\\)|setPlayWhenReady\\s*\\(\\s*true|\\.play\\s*\\(\\s*\\)|playWhenReady\\s*=\\s*true");

    /** The first video source found in the project's code, or null. */
    static Source find(ProjectPreview.Session s) {
        if (s == null || s.sources == null) return null;
        StringBuilder all = new StringBuilder();
        for (File f : s.sources) { if (all.length() > 600000) break; all.append(read(f)).append('\n'); }
        String code = all.toString();
        Source source = new Source();
        Matcher m = CALL_URL.matcher(code);
        if (m.find()) source.uri = Uri.parse(m.group(1));
        if (source.uri == null) { m = URL_EXT.matcher(code); if (m.find()) source.uri = Uri.parse(m.group(1)); }
        if (source.uri == null) {
            m = RAW.matcher(code);
            while (m.find()) { File file = rawFile(s, m.group(1)); if (file != null) { source.uri = Uri.fromFile(file); break; } }
        }
        if (source.uri == null) {
            m = LOCAL.matcher(code);
            while (m.find()) {
                String name = m.group(1);
                if (name.startsWith("http")) continue;
                if (name.startsWith("file:///android_asset/")) name = name.substring("file:///android_asset/".length());
                File file = s.assets == null ? null : new File(s.assets, name);
                if (file == null || !file.isFile()) {
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    int dot = base.lastIndexOf('.');
                    file = rawFile(s, dot > 0 ? base.substring(0, dot) : base);
                }
                if (file != null && file.isFile()) { source.uri = Uri.fromFile(file); break; }
            }
        }
        if (source.uri == null) return null;
        source.autoStart = AUTO.matcher(code).find();
        return source;
    }

    private static File rawFile(ProjectPreview.Session s, String name) {
        if (s.resDir == null) return null;
        File[] files = new File(s.resDir, "raw").listFiles();
        if (files == null) return null;
        for (File f : files) {
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            if (f.isFile() && (dot > 0 ? n.substring(0, dot) : n).equals(name)) return f;
        }
        return null;
    }

    private static String read(File file) {
        try { return file != null && file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : ""; }
        catch (Exception unreadable) { return ""; }
    }

    /** Gives every VideoView found under root the source. Returns them. */
    static List<VideoView> attach(View root, Source source, PreviewRenderer.TipListener tips) {
        List<VideoView> found = new ArrayList<>();
        collect(root, found);
        for (VideoView view : found) configure(view, source, tips, true);
        return found;
    }

    private static void collect(View view, List<VideoView> out) {
        if (view instanceof VideoView) { out.add((VideoView) view); return; }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    /** tapToToggle: a tap on the video plays or pauses it (not wanted in the PiP window, which handles taps itself). */
    static void configure(final VideoView view, final Source source, final PreviewRenderer.TipListener tips, boolean tapToToggle) {
        view.setOnErrorListener((player, what, extra) -> {
            tips.onTip("The video could not be played in the preview: " + source.uri);
            return true;   // no system dialog
        });
        view.setOnPreparedListener(player -> { if (source.autoStart) view.start(); else view.seekTo(1); });
        if (tapToToggle) view.setOnClickListener(v -> { if (view.isPlaying()) view.pause(); else view.start(); });
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) { view.stopPlayback(); }
        });
        view.setVideoURI(source.uri);
    }
}
