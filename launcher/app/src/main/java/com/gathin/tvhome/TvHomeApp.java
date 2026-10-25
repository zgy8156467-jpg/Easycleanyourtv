package com.gathin.tvhome;

import android.app.Application;

public class TvHomeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        Errors.install(this);
        HotPosters.init(this);
    }
}
