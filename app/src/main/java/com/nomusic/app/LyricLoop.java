package com.nomusic.app;

/** A temporary practice range. It never changes queue repeat or the persisted seek position. */
final class LyricLoop {
    long start=-1,end=-1;
    boolean active(){return start>=0&&end>start;}
    void clear(){start=end=-1;}
    boolean set(LyricsDocument document,int cue,int offset,long duration){
        clear();if(!document.timed||cue<0||cue>=document.cues.size()||document.cues.get(cue).original.trim().isEmpty())return false;
        long from=Math.max(0,document.cues.get(cue).timeMs-offset),until=duration;
        for(int i=cue+1;i<document.cues.size();i++)if(document.cues.get(i).timeMs>document.cues.get(cue).timeMs){until=document.cues.get(i).timeMs-offset;break;}
        if(until<=from+150||duration<=0)return false;
        start=from;end=Math.min(duration,until);return active();
    }
    boolean shouldSeek(long position){return active()&&(position>=end||position<Math.max(0,start-500));}
}
