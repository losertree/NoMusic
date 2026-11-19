package com.nomusic.app;

import android.net.Uri;
import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in read-only check using the test device's existing session; never logs account data. */
@SuppressWarnings("deprecation")
public final class LiveLyricsTest extends InstrumentationTestCase {
    public void testSavedSessionEmbeddedLyricsAndDiskCache() throws Exception {
        TlsSocketFactory.initialize(getInstrumentation().getTargetContext());
        AppPrefs prefs=new AppPrefs(getInstrumentation().getTargetContext());
        assertTrue("Log in on the test device before running this opt-in test",prefs.loggedIn());
        EmbyClient api=new EmbyClient(prefs);
        JSONObject page=api.json("GET","/Users/"+Uri.encode(api.userId)+"/Items?Recursive=true&IncludeItemTypes=Audio&Limit=100&Fields=MediaSources,MediaStreams&SortBy=SortName&SortOrder=Ascending",null);
        JSONArray items=page.getJSONArray("Items");Set<String> verified=new HashSet<>();
        File directory=new File(getInstrumentation().getTargetContext().getCacheDir(),"lyrics-live-"+java.util.UUID.randomUUID());
        LyricsRepository cache=new LyricsRepository(directory,System::currentTimeMillis);
        try {
            for(int i=0;i<items.length();i++) {
                JSONObject json=items.getJSONObject(i);String container=json.optString("Container");
                if((!"flac".equals(container)&&!"mp3".equals(container))||verified.contains(container))continue;
                MediaItem item=new MediaItem(json);
                LyricsRepository.Result result=load(cache,api,item);
                assertTrue("Embedded "+container+" lyrics failed",result.error.isEmpty());
                assertTrue("Embedded "+container+" lyrics are missing",result.text.length()>50);
                assertTrue("Embedded "+container+" lyric timeline is missing",result.document.timed);
                assertFalse("Unicode lyric text must be preserved",result.text.contains("\ufffd"));
                assertEquals(result.text,load(cache,api,item).text);
                cache.close();cache=new LyricsRepository(directory,System::currentTimeMillis);
                LyricsRepository.Result disk=load(cache,api,item);
                assertEquals("Disk cache must preserve real embedded lyrics",result.text,disk.text);
                assertEquals(result.document.cues.size(),disk.document.cues.size());assertEquals(result.document.hasTranslation,disk.document.hasTranslation);
                verified.add(container);
            }
            assertTrue("No FLAC track was checked",verified.contains("flac"));
            assertTrue("No MP3 track was checked",verified.contains("mp3"));
        } finally {
            cache.close();File[] files=directory.listFiles();if(files!=null)for(File file:files)file.delete();directory.delete();
        }
    }

    private LyricsRepository.Result load(LyricsRepository cache,EmbyClient api,MediaItem item) throws Exception {
        CountDownLatch ready=new CountDownLatch(1);AtomicReference<LyricsRepository.Result> result=new AtomicReference<>();
        cache.load(api,item,false,value->{result.set(value);ready.countDown();});
        assertTrue("Lyrics callback did not arrive",ready.await(45,TimeUnit.SECONDS));return result.get();
    }
}
