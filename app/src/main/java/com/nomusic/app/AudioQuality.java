package com.nomusic.app;

/** Requested transport profiles. The suffix is part of the on-disk cache format. */
enum AudioQuality {
    SAVER("96", 96, ".96.mp3", "省流"),
    STANDARD("128", 128, ".128.mp3", "标准"),
    BALANCED("192", 192, ".mp3", "均衡"), // Keep the 0.5.x cache and downloads.
    HIGH("256", 256, ".256.mp3", "高音质"),
    MAX("320", 320, ".320.mp3", "最高 MP3"),
    ORIGINAL("source", 0, ".src", "原始音质");

    static final AudioQuality[] ALL=values();
    // Prefer a complete lossless/original copy, then descending bitrates, when the exact file is unavailable.
    static final AudioQuality[] OFFLINE_ORDER={ORIGINAL,MAX,HIGH,BALANCED,STANDARD,SAVER};
    private static final AudioQuality[] FILE_ORDER={ORIGINAL,MAX,HIGH,STANDARD,SAVER,BALANCED};
    final String key,suffix,label;
    final int kbps;

    AudioQuality(String key,int kbps,String suffix,String label){this.key=key;this.kbps=kbps;this.suffix=suffix;this.label=label;}
    boolean original(){return this==ORIGINAL;}
    String format(){return original()?"原始音质":"MP3 "+kbps+" kbps";}
    String choice(){return original()?"原始音质 · 保留源文件":label+" · "+format();}
    static AudioQuality read(String key,boolean original){
        for(AudioQuality value:ALL)if(value.key.equals(key))return value;
        return original?ORIGINAL:BALANCED;
    }
    static String trackKey(String filename){
        // Check the longer suffixes before .mp3.
        for(AudioQuality value:FILE_ORDER)
            if(filename.endsWith(value.suffix))return filename.substring(0,filename.length()-value.suffix.length());
        return "";
    }
    static boolean sourceUnavailable(int status){return status==400||status==404||status==415;}
}
