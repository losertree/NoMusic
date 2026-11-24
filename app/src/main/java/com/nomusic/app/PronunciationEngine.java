package com.nomusic.app;

import android.content.Context;
import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.SoftReference;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Offline readings. Call on the pronunciation worker, never on the player thread. */
final class PronunciationEngine {
    enum Language {
        AUTO("auto","自动识别"), CANTONESE("yue","粤语 · 粤拼"), JAPANESE("ja","日语 · 罗马音"), KOREAN("ko","韩语 · 罗马音");
        final String key,label;
        Language(String key,String label){this.key=key;this.label=label;}
        static Language read(String key){for(Language language:values())if(language.key.equals(key))return language;return AUTO;}
    }

    static final class Line {
        static final Line EMPTY=new Line("",new int[0],new int[0]);
        final String text;
        final int[] originalEnds,readingEnds;
        Line(String text,int[] originalEnds,int[] readingEnds){this.text=text;this.originalEnds=originalEnds;this.readingEnds=readingEnds;}
        int highlightedEnd(int originalEnd){int end=0;for(int i=0;i<originalEnds.length;i++){if(originalEnds[i]>originalEnd)break;end=readingEnds[i];}return end;}
    }

    private static final class Builder {
        final StringBuilder text=new StringBuilder();final List<Integer> originals=new ArrayList<>(),readings=new ArrayList<>();
        void append(String value,int end,boolean separate){
            if(value.isEmpty())return;
            if(separate&&text.length()>0&&!Character.isWhitespace(text.charAt(text.length()-1)))text.append(' ');
            text.append(value);originals.add(end);readings.add(text.length());
        }
        Line build(){int[] a=new int[originals.size()],b=new int[a.length];for(int i=0;i<a.length;i++){a[i]=originals.get(i);b[i]=readings.get(i);}return new Line(text.toString().trim(),a,b);}
    }

    private final Context context;
    private SoftReference<Tokenizer> japanese=new SoftReference<>(null);
    private SoftReference<Lexicon> cantonese=new SoftReference<>(null);
    PronunciationEngine(Context context){this.context=context.getApplicationContext();}

    static Language detect(LyricsDocument document){
        int kana=0,hangul=0;
        for(LyricsDocument.Cue cue:document.cues)for(int i=0;i<cue.original.length();i++){
            char c=cue.original.charAt(i);if(isKana(c))kana++;else if(isHangul(c))hangul++;
        }
        if(kana>0||hangul>0)return kana>=hangul?Language.JAPANESE:Language.KOREAN;
        // Han characters alone do not distinguish Cantonese, Mandarin or Japanese.
        return Language.AUTO;
    }
    static boolean isKana(char c){return c>=0x3041&&c<=0x30ff||c>=0xff66&&c<=0xff9f;}
    static boolean isHangul(char c){return c>=0xac00&&c<=0xd7a3;}
    private static boolean han(char c){return c>=0x3400&&c<=0x9fff||c>=0xf900&&c<=0xfaff;}

    Line read(String original,Language language)throws Exception {
        if(original.trim().isEmpty()||language==Language.AUTO)return Line.EMPTY;
        switch(language){
            case CANTONESE:return cantonese(original);
            case JAPANESE:return japanese(original);
            case KOREAN:return korean(original);
            default:return Line.EMPTY;
        }
    }

    private static final class Lexicon {
        final String[] keys,values;
        Lexicon(Context context)throws Exception{
            ArrayList<String> k=new ArrayList<>(),v=new ArrayList<>();
            try(BufferedReader input=new BufferedReader(new InputStreamReader(new GZIPInputStream(context.getAssets().open("pronunciation/jyutping.lex")),"UTF-8"))){
                String line;while((line=input.readLine())!=null){int split=line.indexOf('\t');if(split>0){k.add(line.substring(0,split));v.add(line.substring(split+1));}}
            }
            keys=k.toArray(new String[0]);values=v.toArray(new String[0]);
        }
        String get(String text){int at=Arrays.binarySearch(keys,text);return at<0?null:values[at];}
    }
    private Line cantonese(String original)throws Exception {
        Lexicon lexicon=cantonese.get();if(lexicon==null){lexicon=new Lexicon(context);cantonese=new SoftReference<>(lexicon);}
        Builder result=new Builder();
        for(int at=0;at<original.length();){
            char c=original.charAt(at);
            if(!han(c)){result.append(String.valueOf(c),++at,false);continue;}
            String reading=null;int end=Math.min(original.length(),at+8);
            for(;end>at;end--){reading=lexicon.get(original.substring(at,end));if(reading!=null)break;}
            if(reading==null){result.append(String.valueOf(c),++at,true);continue;}
            String[] syllables=reading.split(" ");
            for(int i=0;i<syllables.length;i++)result.append(syllables[i],at+i+1,true);
            at=end;
        }
        return result.build();
    }

    private Line japanese(String original) {
        Tokenizer tokenizer=japanese.get();if(tokenizer==null){tokenizer=new Tokenizer();japanese=new SoftReference<>(tokenizer);}
        Builder result=new Builder();
        for(Token token:tokenizer.tokenize(original)){
            String surface=token.getSurface(),reading=token.getReading();
            if(reading==null||reading.equals("*"))reading=surface;
            boolean particle=token.getPartOfSpeechLevel1().equals("助詞");
            if(particle&&surface.equals("は"))reading="ワ";
            else if(particle&&surface.equals("へ"))reading="エ";
            else if(particle&&surface.equals("を"))reading="オ";
            boolean kana=false;for(int i=0;i<reading.length();i++)if(isKana(reading.charAt(i))){kana=true;break;}
            result.append(kana?romaji(reading):surface,token.getPosition()+surface.length(),kana);
        }
        return result.build();
    }

    private static final String KANA="ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔ";
    private static final String[] ROMAJI=("a a i i u u e e o o ka ga ki gi ku gu ke ge ko go sa za shi ji su zu se ze so zo ta da chi ji _ tsu zu te de to do na ni nu ne no ha ba pa hi bi pi fu bu pu he be pe ho bo po ma mi mu me mo ya ya yu yu yo yo ra ri ru re ro wa wa i e o n vu").split(" ");
    static String romaji(String source){
        String normalized=Normalizer.normalize(source,Normalizer.Form.NFKC);StringBuilder kana=new StringBuilder();
        for(int i=0;i<normalized.length();i++){char c=normalized.charAt(i);kana.append(c>=0x30a1&&c<=0x30f6?(char)(c-0x60):c);}
        StringBuilder result=new StringBuilder();boolean geminate=false;
        for(int i=0;i<kana.length();i++){
            char c=kana.charAt(i);if(c=='っ'){geminate=true;continue;}
            if(c=='ー'){for(int p=result.length()-1;p>=0;p--){char v=result.charAt(p);if("aeiou".indexOf(v)>=0){result.append(v);break;}}continue;}
            int at=KANA.indexOf(c);String value=at<0?String.valueOf(c):ROMAJI[at];
            if(i+1<kana.length()){
                char small=kana.charAt(i+1);String vowel=small=='ゃ'?"a":small=='ゅ'?"u":small=='ょ'?"o":"";
                if(!vowel.isEmpty()&&value.endsWith("i")){
                    value=value.equals("shi")?"sh"+vowel:value.equals("chi")?"ch"+vowel:value.equals("ji")?"j"+vowel:value.substring(0,value.length()-1)+"y"+vowel;i++;
                }else if("ぁぃぅぇぉ".indexOf(small)>=0&&("fu".equals(value)||"vu".equals(value)||"te".equals(value)||"de".equals(value)||"shi".equals(value)||"chi".equals(value)||"je".equals(value))){
                    String v="aiueo".substring("ぁぃぅぇぉ".indexOf(small),"ぁぃぅぇぉ".indexOf(small)+1);
                    value=value.equals("fu")?"f"+v:value.equals("vu")?"v"+v:value.equals("te")?"t"+v:value.equals("de")?"d"+v:value.substring(0,value.length()-1)+v;i++;
                }
            }
            if(c=='ん'&&i+1<kana.length()){char next=kana.charAt(i+1);if("あいうえおやゆよ".indexOf(next)>=0)value="n'";}
            if(geminate&&value.length()>0){char first=value.charAt(0);if("bcdfghjklmpqrstvwxyz".indexOf(first)>=0)result.append(value.startsWith("ch")?'t':first);geminate=false;}
            result.append(value);
        }
        return result.toString().toLowerCase(Locale.ROOT);
    }

    private static final String[] INITIAL={"g","kk","n","d","tt","r","m","b","pp","s","ss","","j","jj","ch","k","t","p","h"};
    private static final String[] VOWEL={"a","ae","ya","yae","eo","e","yeo","ye","o","wa","wae","oe","yo","u","wo","we","wi","yu","eu","ui","i"};
    private static final String[] FINAL={"","k","k","k","n","n","n","t","l","k","m","l","l","l","p","l","m","p","p","t","t","ng","t","t","k","t","p","t"};
    // Onsets and remaining codas when a final consonant is followed by silent ㅇ.
    private static final int[] LIAISON={11,0,1,9,2,12,2,3,5,0,6,7,9,16,17,5,6,7,9,9,10,11,12,14,15,16,17,11};
    private static final String[] REMAIN={"","","","k","","n","","","","l","l","l","l","l","l","","","","p","","","ng","","","","","",""};
    static Line korean(String original){
        Builder result=new Builder();int override=-1;
        for(int i=0;i<original.length();i++){
            char c=original.charAt(i);if(!isHangul(c)){result.append(String.valueOf(c),i+1,false);override=-1;continue;}
            int value=c-0xac00,initial=value/588,vowel=value%588/28,last=value%28;
            int onset=override<0?initial:override;
            String lead=INITIAL[onset],tail=FINAL[last];override=-1;
            if(initial==5&&i>0&&isHangul(original.charAt(i-1))&&(original.charAt(i-1)-0xac00)%28==0)lead="r";
            if(i+1<original.length()&&isHangul(original.charAt(i+1))){
                int next=original.charAt(i+1)-0xac00,n=next/588,nv=next%588/28;
                if(n==11&&last>0){
                    tail=REMAIN[last];override=LIAISON[last];
                    if((last==7||last==25)&&nv==20)override=last==7?12:14;
                }else if((last==6||last==15||last==27)&&(n==0||n==3||n==12||n==9)){
                    tail=last==6?"n":last==15?"l":"";override=n==0?15:n==3?16:n==12?14:10;
                }else if(n==18&&(tail.equals("k")||tail.equals("t")||tail.equals("p"))){
                    override=tail.equals("k")?15:tail.equals("t")?16:17;tail="";
                }else if(n==2||n==6||n==5){
                    if(tail.equals("k"))tail="ng";else if(tail.equals("t"))tail="n";else if(tail.equals("p"))tail="m";
                    if(n==5){if(tail.equals("n")||tail.equals("l")){tail="l";override=5;}else if(tail.equals("ng")||tail.equals("m"))override=2;}
                    else if(n==2&&tail.equals("l"))override=5;
                }
            }
            if(onset==5&&i>0&&isHangul(original.charAt(i-1))){int previous=(original.charAt(i-1)-0xac00)%28;if(previous==4||previous==8)lead="l";}
            result.append(lead+VOWEL[vowel]+tail,i+1,false);
        }
        return result.build();
    }
}
