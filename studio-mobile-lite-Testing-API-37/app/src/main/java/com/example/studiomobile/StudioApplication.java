package com.example.studiomobile;

import android.app.Application;
import android.content.Context;

import com.example.studiomobile.build.DownloadPrompt;

/** Lets {@link DownloadPrompt} know which screen is showing, so it can ask before a download. */
public final class StudioApplication extends Application {
    /** Runs before onCreate() and before any Activity or Service, so Studio Mobile's own crash catcher is in place first. */
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        SelfCrashCatcher.install(this);
    }

    @Override public void onCreate() {
        super.onCreate();
        DownloadPrompt.attach(this);
    }
}
