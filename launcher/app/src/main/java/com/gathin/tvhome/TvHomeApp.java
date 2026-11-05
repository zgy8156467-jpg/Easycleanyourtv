package com.gathin.tvhome;

import android.app.Application;

public class TvHomeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        BootGuard.noteStart(this);
        CrashLog.install(this);
        Errors.install(this);
        if (BootGuard.safeMode(this)) Errors.record("boot", "safe mode after "
                + BootGuard.starts(this) + " quick starts");
        HotPosters.init(this);
    }
}
