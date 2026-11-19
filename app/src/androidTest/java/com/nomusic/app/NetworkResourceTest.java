package com.nomusic.app;

import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.File;

/** Exercises the real, certificate-validated HTTPS request path without account data. */
@SuppressWarnings("deprecation")
public final class NetworkResourceTest extends InstrumentationTestCase {
    private int descriptors() {
        String[] entries=new File("/proc/self/fd").list();
        assertNotNull("Cannot inspect process descriptors",entries);
        return entries.length;
    }

    private void collect() {
        System.gc();System.runFinalization();SystemClock.sleep(150);
    }

    public void testRepeatedHttpsRequestsKeepDescriptorsBounded() throws Exception {
        TlsSocketFactory.initialize(getInstrumentation().getTargetContext());
        EmbyClient api=new EmbyClient("https://nomusic.losertree.de","","","resource-regression");
        assertEquals(1,api.json("GET","/updates/latest.json",null).getInt("schemaVersion"));
        collect();int before=descriptors();
        for(int i=1;i<=48;i++) {
            assertEquals(1,api.json("GET","/updates/latest.json",null).getInt("schemaVersion"));
            if(i%12==0) {
                Bundle status=new Bundle();
                status.putString("stream","HTTPS requests "+i+" / 48: open descriptors "+descriptors()+" (initial "+before+")\n");
                getInstrumentation().sendStatus(2,status);
            }
        }
        collect();int after=descriptors();
        assertTrue("HTTPS requests leaked descriptors: before="+before+", after="+after,after<=before+12);
    }
}
