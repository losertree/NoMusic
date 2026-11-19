package com.nomusic.app;

import android.content.Context;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in: fetch the live manifest and validate a real downloadable package on Android 4.x. */
@SuppressWarnings("deprecation")
public final class UpdateIntegrationTest extends InstrumentationTestCase {
    public void testPublishedUpdateDownloadIdentityAndInstaller() throws Exception {
        Context context=getInstrumentation().getTargetContext();TlsSocketFactory.initialize(context);
        CountDownLatch checked=new CountDownLatch(1);AtomicReference<UpdateManager.Release> release=new AtomicReference<>();
        AtomicReference<String> error=new AtomicReference<>();
        UpdateManager.check(context,(value,problem)->{release.set(value);error.set(problem);checked.countDown();});
        assertTrue(checked.await(25,TimeUnit.SECONDS));assertEquals("",error.get());
        assertNotNull("Install a versionCode=1 update probe before this test",release.get());
        assertEquals(context.getPackageName(),release.get().packageName);assertTrue(release.get().code>BuildConfig.VERSION_CODE);
        CountDownLatch downloaded=new CountDownLatch(1);AtomicReference<File> apk=new AtomicReference<>();
        UpdateManager.download(context,release.get(),percent->{},(file,problem)->{apk.set(file);error.set(problem);downloaded.countDown();});
        assertTrue(downloaded.await(90,TimeUnit.SECONDS));assertEquals("",error.get());assertNotNull(apk.get());
        assertEquals(release.get().size,apk.get().length());
        android.content.Intent installer=UpdateManager.installer(context,apk.get());
        assertNotNull(context.getPackageManager().resolveActivity(installer,0));
        assertEquals(android.os.Build.VERSION.SDK_INT<24?"file":"content",installer.getData().getScheme());
        UpdateManager.Release wrong=new UpdateManager.Release("com.other.player",new JSONObject()
                .put("versionCode",release.get().code).put("versionName",release.get().name).put("size",release.get().size).put("minSdk",16)
                .put("url",release.get().url).put("sha256",release.get().sha256));
        try {UpdateManager.validate(context,apk.get(),wrong);fail("A different package was accepted");}
        catch(java.io.IOException expected){}
    }
}
