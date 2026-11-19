package com.nomusic.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

public final class MediaButtonReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String command = null;
        if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (event != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                command = commandForKey(event.getKeyCode());
        } else {
            String value = intent.getStringExtra("command");
            if (value == null) value = intent.getStringExtra("cmd");
            if ("togglepause".equals(value) || "toggle".equals(value)) command = PlaybackService.TOGGLE;
            if ("play".equals(value)) command = PlaybackService.PLAY;
            if ("pause".equals(value)) command = PlaybackService.PAUSE;
            if ("next".equals(value)) command = PlaybackService.NEXT;
            if ("previous".equals(value)) command = PlaybackService.PREVIOUS;
            if ("stop".equals(value)) command = PlaybackService.STOP;
        }
        android.util.Log.d("NoMusicControls", command == null ? "Ignored control event" : "Dispatch " + command);
        if (command != null) {
            context.startService(new Intent(context, PlaybackService.class).setAction(command));
            if (isOrderedBroadcast()) abortBroadcast();
        }
    }

    public static String commandForKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK: return PlaybackService.TOGGLE;
            case KeyEvent.KEYCODE_MEDIA_PLAY: return PlaybackService.PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return PlaybackService.PAUSE;
            case KeyEvent.KEYCODE_MEDIA_NEXT: return PlaybackService.NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return PlaybackService.PREVIOUS;
            case KeyEvent.KEYCODE_MEDIA_STOP: return PlaybackService.STOP;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return PlaybackService.FORWARD;
            case KeyEvent.KEYCODE_MEDIA_REWIND: return PlaybackService.REWIND;
            default: return null;
        }
    }
}
