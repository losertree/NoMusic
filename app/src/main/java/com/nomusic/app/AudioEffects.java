package com.nomusic.app;

import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;

/** Owns only the current MediaPlayer's effects. A missing DSP never prevents playback. */
final class AudioEffects {
    static final int[] FREQUENCIES={60,230,910,3600,14000};
    static final String[] BAND_LABELS={"60 Hz","230 Hz","910 Hz","3.6 kHz","14 kHz"};
    static final int MAX_DB=6;
    enum Mode {
        OFF("关闭",0,0,0,0,0), FLAT("平直",0,0,0,0,0), WARM("温暖",3,2,0,-1,-2),
        VOCAL("人声",-2,-1,2,3,0), ROCK("摇滚",3,1,-2,2,3), CUSTOM("自定义",0,0,0,0,0);
        final String label; final int[] gain;
        Mode(String label,int...gain){this.label=label;this.gain=gain;}
    }

    static final class Profile {
        final Mode mode;
        final int bass;
        private final int[] custom;
        private Profile(Mode mode,int bass,int[] custom){this.mode=mode;this.bass=bass;this.custom=custom;}
        int gain(int index){return custom[index];}
        Profile mode(Mode value){return new Profile(value,bass,custom);}
        Profile bass(int value){return new Profile(mode,Math.max(0,Math.min(100,value)),custom);}
        Profile band(int index,int db){int[] next=custom.clone();next[index]=clamp(db,MAX_DB);return new Profile(mode,bass,next);}

        static Profile read(AppPrefs prefs){
            Mode mode;
            try{mode=Mode.valueOf(prefs.store.getString("eq_mode","OFF"));}catch(IllegalArgumentException e){mode=Mode.OFF;}
            int[] bands=new int[FREQUENCIES.length];
            String[] stored=prefs.store.getString("eq_bands","").split(",");
            for(int i=0;i<Math.min(bands.length,stored.length);i++)try{bands[i]=clamp(Integer.parseInt(stored[i]),MAX_DB);}catch(NumberFormatException ignored){}
            return new Profile(mode,0,bands).bass(prefs.store.getInt("eq_bass",0));
        }
        void save(AppPrefs prefs){
            StringBuilder bands=new StringBuilder();
            for(int gain:custom){if(bands.length()>0)bands.append(',');bands.append(gain);}
            prefs.store.edit().putString("eq_mode",mode.name()).putString("eq_bands",bands.toString()).putInt("eq_bass",bass).apply();
        }
    }

    private int session;
    private boolean eqTried,bassTried,variableBass,eqActive,bassActive;
    private Equalizer equalizer;
    private BassBoost bassBoost;
    private int[] centers;
    private short[] levels;

    void apply(int audioSession,Profile profile){
        if(profile.mode==Mode.OFF||audioSession<=0){release();return;}
        if(session!=audioSession){release();session=audioSession;}
        if(!eqTried){
            eqTried=true;
            try{
                equalizer=new Equalizer(0,session);
                levels=equalizer.getBandLevelRange();centers=new int[equalizer.getNumberOfBands()];
                for(short i=0;i<centers.length;i++)centers[i]=equalizer.getCenterFreq(i)/1000;
            }catch(RuntimeException ignored){releaseEqualizer();}
        }
        if(equalizer!=null)try{
            int[] gain=profile.mode==Mode.CUSTOM?profile.custom:profile.mode.gain;
            for(short i=0;i<centers.length;i++){
                int level=bandLevel(centers[i],gain);
                equalizer.setBandLevel(i,(short)Math.max(levels[0],Math.min(levels[1],level)));
            }
            eqActive=equalizer.setEnabled(true)==AudioEffect.SUCCESS;
        }catch(RuntimeException ignored){releaseEqualizer();}

        if(profile.bass>0&&!bassTried){
            bassTried=true;
            try{bassBoost=new BassBoost(0,session);variableBass=bassBoost.getStrengthSupported();}
            catch(RuntimeException ignored){releaseBass();}
        }
        if(bassBoost!=null)try{
            if(profile.bass>0&&variableBass)bassBoost.setStrength((short)(profile.bass*10));
            bassActive=bassBoost.setEnabled(profile.bass>0)==AudioEffect.SUCCESS&&profile.bass>0;
        }catch(RuntimeException ignored){releaseBass();}
    }

    String status(Profile profile){
        if(profile.mode==Mode.OFF)return "音效已关闭";
        if(session==0)return "播放音乐后应用";
        if(!eqActive&&!bassActive)return "此设备的音频输出暂不支持所选音效，音乐照常播放";
        if(!eqActive)return "仅低音增强可用";
        if(profile.bass>0&&!bassActive)return "均衡器已生效 · 此输出不支持低音增强";
        if(bassActive&&!variableBass)return "均衡器已生效 · 低音为设备固定强度";
        return bassActive?"均衡器与低音增强已生效":"均衡器已生效";
    }

    void release(){
        releaseEqualizer();releaseBass();session=0;eqTried=false;bassTried=false;
    }
    private void releaseEqualizer(){releaseEffect(equalizer);equalizer=null;centers=null;levels=null;eqActive=false;}
    private void releaseBass(){releaseEffect(bassBoost);bassBoost=null;bassActive=false;variableBass=false;}
    private static void releaseEffect(AudioEffect value){if(value!=null)try{value.release();}catch(RuntimeException ignored){}}

    private static int bandLevel(int frequency,int[] gain){
        if(frequency<=FREQUENCIES[0])return gain[0]*100;
        for(int i=1;i<FREQUENCIES.length;i++)if(frequency<=FREQUENCIES[i]){
            double fraction=Math.log(frequency/(double)FREQUENCIES[i-1])/Math.log(FREQUENCIES[i]/(double)FREQUENCIES[i-1]);
            return (int)Math.round(100*(gain[i-1]+(gain[i]-gain[i-1])*fraction));
        }
        return gain[gain.length-1]*100;
    }
    private static int clamp(int value,int max){return Math.max(-max,Math.min(max,value));}
}
