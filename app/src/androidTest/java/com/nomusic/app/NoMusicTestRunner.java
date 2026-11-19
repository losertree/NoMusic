package com.nomusic.app;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;

/** The platform runner's getArguments() is unavailable on API 16. */
@SuppressWarnings("deprecation")
public final class NoMusicTestRunner extends InstrumentationTestRunner {
    private Bundle supplied;
    @Override public void onCreate(Bundle arguments) {
        supplied=new Bundle(arguments);
        if(!supplied.containsKey("class") && !supplied.containsKey("server"))
            supplied.putString("class","com.nomusic.app.NoMusicIntegrationTest");
        super.onCreate(supplied);
    }
    Bundle suppliedArguments() { return supplied; }
}
