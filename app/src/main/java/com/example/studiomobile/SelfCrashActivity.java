package com.example.studiomobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Full-screen crash page for Studio Mobile itself (see {@link SelfCrashCatcher}). Runs in its own process so it
 * stays alive after the crashed app process is killed. Built in code: it needs no resources.
 */
public final class SelfCrashActivity extends Activity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String log = loadLog();
        final float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText(log.startsWith("=== STUDIO MOBILE FROZE") ? "Studio Mobile is frozen" : "Studio Mobile crashed");
        title.setTextColor(Color.parseColor("#B00020"));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Studio Mobile's own Debug Mode caught this before it could close silently. Reports are also saved in the \"App Crash Logs\" folder (the newest 10 are kept). Copy the log and send it to your AI assistant.");
        hint.setTextColor(Color.parseColor("#555555"));
        hint.setTextSize(14);
        hint.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(hint);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        Button copy = new Button(this);
        copy.setText("Copy log");
        copy.setAllCaps(false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Crash log", log));
                Toast.makeText(SelfCrashActivity.this, "Log copied", Toast.LENGTH_SHORT).show();
            }
        });
        Button close = new Button(this);
        close.setText("Close");
        close.setAllCaps(false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishAndRemoveTask(); android.os.Process.killProcess(android.os.Process.myPid()); }
        });
        buttons.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(close, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.parseColor("#F5F5F5"));
        box.setCornerRadius(8 * density);
        scroll.setBackground(box);
        TextView body = new TextView(this);
        body.setText(log);
        body.setTextColor(Color.parseColor("#111111"));
        body.setTextSize(12);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        scroll.addView(body);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = pad / 2;
        root.addView(scroll, scrollParams);

        setContentView(root);
    }

    private String loadLog() {
        String fromIntent = getIntent() == null ? null : getIntent().getStringExtra(SelfCrashCatcher.EXTRA_LOG);
        if (fromIntent != null && fromIntent.length() > 0) return fromIntent;
        return SelfCrashCatcher.readSavedLog(this);
    }

    @Override public void onBackPressed() { finishAndRemoveTask(); android.os.Process.killProcess(android.os.Process.myPid()); }
}
