package com.nomusic.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable, sorted lyric cues. No Android APIs or playback state are kept here. */
final class LyricsDocument {
    private static final Pattern TIME=Pattern.compile("\\[(\\d{1,6}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET=Pattern.compile("\\[offset:([+-]?\\d+)\\]",Pattern.CASE_INSENSITIVE);
    private static final Pattern META=Pattern.compile("^\\[[a-zA-Z]+:.*\\]$");
    private static final Pattern WORD_TIME=Pattern.compile("<(\\d{1,6}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    // Canonical cache marker for an explicit API translation, which need not use
    // a different writing system. Embedded LRC without this marker uses script grouping.
    static final String TRANSLATION="[tr:]";
    static final LyricsDocument EMPTY=new LyricsDocument(new ArrayList<>(),false);

    static final class Cue {
        final long timeMs;
        final String original,translation;
        final List<Word> words;
        Cue(long timeMs,String original,String translation) {this(timeMs,original,translation,Collections.emptyList());}
        Cue(long timeMs,String original,String translation,List<Word> words) {
            this.timeMs=timeMs;this.original=original;this.translation=translation;this.words=Collections.unmodifiableList(words);
        }
        int wordEnd(long position) {
            int end=0;for(Word word:words){if(position<word.start)break;end=word.end;}return end;
        }
    }

    static final class Word {
        final long start;final int end;
        Word(long start,int end){this.start=start;this.end=end;}
    }

    final List<Cue> cues;
    final boolean timed,hasTranslation;
    final int sizeBytes;

    private LyricsDocument(List<Cue> cues,boolean timed) {
        this.cues=Collections.unmodifiableList(cues);this.timed=timed;
        boolean translated=false;int size=96;
        for(Cue cue:cues){translated|=!cue.translation.isEmpty();size+=96+2*(cue.original.length()+cue.translation.length())+24*cue.words.size();}
        hasTranslation=translated;sizeBytes=size;
    }

    int indexAt(long positionMs) {
        if(!timed)return -1;
        int left=0,right=cues.size()-1,result=-1;
        while(left<=right) {
            int middle=(left+right)>>>1;
            if(cues.get(middle).timeMs<=positionMs){result=middle;left=middle+1;}else right=middle-1;
        }
        return result;
    }

    static boolean hasTimestamp(String text) {return TIME.matcher(text).find();}

    static String timestamp(long millis) {
        long value=Math.max(0,millis),minutes=value/60000,seconds=value/1000%60,fraction=value%1000;
        return "["+(minutes<10?"0":"")+minutes+":"+(seconds<10?"0":"")+seconds+"."+(fraction<100?"0":"")+(fraction<10?"0":"")+fraction+"]";
    }

    static String wordTimestamp(long millis) {String stamp=timestamp(millis);return "<"+stamp.substring(1,stamp.length()-1)+">";}

    /** Align an independently timed track to nearby original cues. Plain tracks must have equal line counts. */
    static String merge(String original,String translation) {
        LyricsDocument base=parse(original),other=parse(translation);
        if(base.cues.isEmpty()||other.cues.isEmpty())return original;
        String[] translated=new String[base.cues.size()];
        if(base.timed&&other.timed) {
            for(Cue cue:other.cues) {
                int at=base.indexAt(cue.timeMs);
                if(at<0||at+1<translated.length&&cue.timeMs-base.cues.get(at).timeMs>base.cues.get(at+1).timeMs-cue.timeMs)at++;
                if(Math.abs(base.cues.get(at).timeMs-cue.timeMs)<=1500&&!cue.original.isEmpty())
                    translated[at]=translated[at]==null?cue.original:translated[at]+"\n"+cue.original;
            }
        } else if(!other.timed&&base.cues.size()==other.cues.size()) {
            for(int i=0;i<translated.length;i++)translated[i]=other.cues.get(i).original;
        } else return original;
        StringBuilder result=new StringBuilder();
        for(int i=0;i<translated.length;i++) {
            Cue cue=base.cues.get(i);
            String stamp=base.timed?timestamp(cue.timeMs):"";
            StringBuilder line=new StringBuilder(cue.original);
            int start=0,shift=0;
            for(Word word:cue.words) {
                if(word.end<=start||start>cue.original.length())continue;
                String time=wordTimestamp(word.start);
                line.insert(start+shift,time);shift+=time.length();start=word.end;
            }
            result.append(stamp).append(line.toString().replace("\n","\n"+stamp)).append('\n');
            String text=translated[i]==null?cue.translation:translated[i];
            for(String part:text.split("\n"))if(!part.isEmpty())result.append(stamp).append(TRANSLATION).append(part).append('\n');
        }
        return result.toString();
    }

    static LyricsDocument parse(String raw) {
        if(raw==null||raw.trim().isEmpty())return EMPTY;
        Matcher shift=OFFSET.matcher(raw);long offset=0;
        if(shift.find())try {offset=Long.parseLong(shift.group(1));offset=Math.max(-86400000,Math.min(86400000,offset));}catch(NumberFormatException ignored){}
        TreeMap<Long,List<String>> timed=new TreeMap<>();List<String> plain=new ArrayList<>();
        List<Long> previous=new ArrayList<>();
        for(String line:raw.replace("\ufeff","").replace("\r","").split("\n")) {
            line=line.trim();if(line.isEmpty()||META.matcher(line).matches())continue;
            Matcher matcher=TIME.matcher(line);List<Long> times=new ArrayList<>();int end=0;
            while(matcher.find()) {
                if(!line.substring(end,matcher.start()).trim().isEmpty())break;
                long at=millis(matcher);if(at<0)break;
                // Positive LRC offsets advance lyrics relative to the recording.
                times.add(Math.max(0,at-offset));end=matcher.end();
            }
            if(!times.isEmpty()) {
                String text=line.substring(end).trim();
                for(long time:times)add(timed,time,text);
                previous=times;
            } else if(!line.startsWith("[")||line.startsWith(TRANSLATION)) {
                if(previous.isEmpty())plain.add(line);
                else for(long time:previous)add(timed,time,line);
            }
        }
        List<Cue> cues=new ArrayList<>();
        if(!timed.isEmpty()) {
            for(Map.Entry<Long,List<String>> entry:timed.entrySet()) {
                List<String> lines=entry.getValue();StringBuilder original=new StringBuilder(),translation=new StringBuilder();
                List<Word> words=new ArrayList<>();
                int language=0;for(String line:lines)if(!line.startsWith(TRANSLATION)){language=script(WORD_TIME.matcher(line).replaceAll(""));break;}
                for(int i=0;i<lines.size();i++) {
                    String text=lines.get(i);boolean explicit=text.startsWith(TRANSLATION);if(explicit)text=text.substring(TRANSLATION.length());
                    int other=script(text);
                    // Preserve simultaneous lines in the same script as originals. Embedded
                    // English/Japanese/Korean + Chinese pairs can be hidden independently.
                    if(explicit||i>0&&language!=0&&other!=0&&language!=other)append(translation,WORD_TIME.matcher(text).replaceAll(""));
                    else appendTimed(original,text,words,entry.getKey(),offset);
                }
                cues.add(new Cue(entry.getKey(),original.toString(),translation.toString(),words));
            }
            return new LyricsDocument(cues,true);
        }
        for(String line:plain) {
            if(line.startsWith(TRANSLATION)&&!cues.isEmpty()) {
                Cue last=cues.remove(cues.size()-1);String translated=line.substring(TRANSLATION.length());
                cues.add(new Cue(-1,last.original,last.translation.isEmpty()?translated:last.translation+"\n"+translated));
            } else if(!line.startsWith(TRANSLATION))cues.add(new Cue(-1,line,""));
        }
        return cues.isEmpty()?EMPTY:new LyricsDocument(cues,false);
    }

    private static long millis(Matcher match) {
        long seconds=Long.parseLong(match.group(2));if(seconds>=60)return -1;
        String part=match.group(3);long fraction=part==null?0:Long.parseLong(part)*(part.length()==1?100:part.length()==2?10:1);
        return Long.parseLong(match.group(1))*60000+seconds*1000+fraction;
    }

    private static void appendTimed(StringBuilder target,String line,List<Word> words,long cue,long offset) {
        Matcher match=WORD_TIME.matcher(line);
        if(!match.find()){append(target,line);return;}
        if(target.length()>0)target.append('\n');
        int from=0;long time=cue;
        do {
            if(match.start()>from) {
                target.append(line,from,match.start());
                words.add(new Word(time,target.length()));
            }
            long at=millis(match);if(at>=0)time=Math.max(time,at-offset);
            from=match.end();
        } while(match.find());
        if(from<line.length()){target.append(line.substring(from));words.add(new Word(time,target.length()));}
    }

    private static void add(TreeMap<Long,List<String>> groups,long time,String text) {
        List<String> lines=groups.get(time);if(lines==null){lines=new ArrayList<>();groups.put(time,lines);}
        if(!text.isEmpty()&&!lines.contains(text))lines.add(text);
    }

    private static void append(StringBuilder target,String text) {if(text.isEmpty())return;if(target.length()>0)target.append('\n');target.append(text);}

    private static int script(String text) {
        boolean han=false,latin=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c>=0x3040&&c<=0x30ff)return 2;
            if(c>=0xac00&&c<=0xd7af||c>=0x1100&&c<=0x11ff)return 3;
            if(c>=0x3400&&c<=0x9fff)han=true;
            else if(c>='A'&&c<='Z'||c>='a'&&c<='z'||c>=0x00c0&&c<=0x024f)latin=true;
        }
        return han?1:latin?4:0;
    }
}
