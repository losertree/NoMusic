package com.nomusic.app;

import android.app.Application;

public final class NoMusicApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        try { TlsSocketFactory.initialize(this); }
        catch(Exception e) { throw new IllegalStateException("Unable to initialize certificate-validated TLS",e); }
    }
}
