package com.nomusic.app;

import android.content.Context;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/** Stable bilingual rows; rendering and scrolling never load lyrics from the network. */
final class LyricsView extends LinearLayout {
    private final ListView list;
    private final TextView status,message,follow;
    private final View translationButton;
    private final IconView translationIcon,more;
    private final IconView pronunciationIcon;
    private PronunciationRepository.Request pronunciationRequest;
    private PronunciationRepository.Result pronunciation;
    private String pronunciationScope="",pronunciationKey="";
    private String pronunciationStatus="",loopStatus="";
    private boolean karaoke;
    private final AppPrefs prefs;
    private final Rows adapter=new Rows();
    private final LyricsRepository.Clock clock;
    private LyricsDocument document=LyricsDocument.EMPTY;
    private final java.util.List<Integer> visibleCues=new java.util.ArrayList<>();
    private String raw="";
    private int current=-1,wordEnd,offset,font=30;
    private long manualUntil,position;
    private boolean touching;
    private Ui.Palette colors=Ui.NIGHT;

    LyricsView(Context context,AppPrefs prefs){this(context,prefs,SystemClock::elapsedRealtime,null);}
    LyricsView(Context context,AppPrefs prefs,LyricsRepository.Clock clock){this(context,prefs,clock,null);}
    LyricsView(Context context,AppPrefs prefs,LyricsRepository.Clock clock,View headerButton) {
        super(context);this.clock=clock;this.prefs=prefs;setOrientation(VERTICAL);font=prefs.store.getInt("lyrics_font",30);
        LinearLayout controls=Ui.row(context);
        controls.addView(new View(context),new LayoutParams(0,1,1));
        follow=Ui.button(context,"回到当前行",false,v->resumeFollowing());follow.setTextSize(16);follow.setId(R.id.lyrics_follow);follow.setVisibility(GONE);
        controls.addView(follow,new LayoutParams(Ui.dp(context,80),Ui.dp(context,56)));
        pronunciationIcon=new IconView(context,"pronunciation",Ui.MUTED);pronunciationIcon.setId(R.id.lyrics_pronunciation);Ui.pad(pronunciationIcon,12,12,12,12);pronunciationIcon.setFocusable(true);pronunciationIcon.setClickable(true);pronunciationIcon.setContentDescription("发音辅助与歌词语言");pronunciationIcon.setBackground(Ui.background(context,android.graphics.Color.TRANSPARENT,Ui.NIGHT.SOFT));Ui.press(pronunciationIcon);controls.addView(pronunciationIcon,new LayoutParams(Ui.dp(context,48),Ui.dp(context,56)));
        if(headerButton==null){translationIcon=new IconView(context,"translation",Ui.MUTED);Ui.pad(translationIcon,12,12,12,12);translationButton=translationIcon;controls.addView(translationButton,new LayoutParams(Ui.dp(context,48),Ui.dp(context,56)));}
        else {translationButton=headerButton;translationIcon=(IconView)((LinearLayout)headerButton).getChildAt(0);}
        translationButton.setId(R.id.lyrics_translation);translationButton.setFocusable(true);translationButton.setClickable(true);translationButton.setBackground(Ui.background(context,android.graphics.Color.TRANSPARENT,Ui.NIGHT.SOFT));
        Ui.press(translationButton);
        more=new IconView(context,"more",Ui.MUTED);more.setId(R.id.lyrics_more);Ui.pad(more,12,12,12,12);more.setFocusable(true);more.setClickable(true);more.setContentDescription("歌词设置与编辑");more.setBackground(Ui.background(context,android.graphics.Color.TRANSPARENT,Ui.NIGHT.SOFT));controls.addView(more,new LayoutParams(Ui.dp(context,48),Ui.dp(context,56)));
        Ui.press(more);
        addView(controls,new LayoutParams(-1,Ui.dp(context,56)));
        status=Ui.text(context,"",16,Ui.MUTED,false);status.setId(R.id.lyrics_status);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);Ui.pad(status,0,0,0,4);addView(status,new LayoutParams(-1,-2));
        FrameLayout viewport=new FrameLayout(context);LayoutParams vp=new LayoutParams(-1,0,1);vp.topMargin=Ui.dp(context,16);addView(viewport,vp);
        message=Ui.text(context,"选择歌曲后显示歌词",22,Ui.TEXT,false);message.setGravity(Gravity.CENTER_VERTICAL);message.setLineSpacing(Ui.dp(context,8),1);
        list=new ListView(context);list.setId(R.id.lyrics_list);list.setDivider(null);list.setCacheColorHint(android.graphics.Color.TRANSPARENT);list.setVerticalScrollBarEnabled(false);list.setFadingEdgeLength(Ui.dp(context,20));list.setVerticalFadingEdgeEnabled(true);
        list.setSelector(Ui.background(context,android.graphics.Color.TRANSPARENT,Ui.NIGHT.SOFT));list.setClipToPadding(false);list.setPadding(0,0,0,Ui.dp(context,200));
        viewport.addView(list,new FrameLayout.LayoutParams(-1,-1));viewport.addView(message,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(message);list.setAdapter(adapter);
        list.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l==or-ol&&b-t==ob-ot)return;int anchor=Math.max(0,Math.round((b-t)*.38f-font*getResources().getDisplayMetrics().scaledDensity*.85f)-Ui.dp(context,12));list.setPadding(0,anchor,0,Math.max(Ui.dp(context,100),Math.round((b-t)*.65f)));if(manualUntil==0)center(false);});
        list.setOnTouchListener((view,event)->{int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN)touching=true;if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)touching=false;if(document.timed){manualUntil=clock.now()+5000;follow.setVisibility(VISIBLE);}return false;});
        list.setOnKeyListener((v,key,event)->{if(event.getAction()==android.view.KeyEvent.ACTION_DOWN&&(key==android.view.KeyEvent.KEYCODE_DPAD_UP||key==android.view.KeyEvent.KEYCODE_DPAD_DOWN)&&document.timed){manualUntil=clock.now()+5000;follow.setVisibility(VISIBLE);}return false;});
        translationButton.setOnClickListener(v->{int first=list.getFirstVisiblePosition(),cue=visibleCues.isEmpty()?-1:visibleCues.get(Math.min(first,visibleCues.size()-1)),top=list.getChildCount()>0?list.getChildAt(0).getTop()-list.getPaddingTop():0;prefs.setBool("lyrics_translation",!translated());rebuildRows();updateStatus();adapter.notifyDataSetChanged();if(manualUntil==0)center(false);else list.post(()->list.setSelectionFromTop(Math.max(0,rowForCue(cue)),top));});
        updateStatus();
    }
    void setMoreListener(View.OnClickListener listener){more.setOnClickListener(listener);}
    void setPronunciationListener(View.OnClickListener listener){pronunciationIcon.setOnClickListener(listener);}
    void setPronunciationScope(String scope){if(!pronunciationScope.equals(scope)){pronunciationScope=scope;pronunciationKey="";pronunciation=null;}loadPronunciation();}
    void refreshPronunciation(){pronunciationKey="";loadPronunciation();}
    void setKaraoke(boolean value){if(karaoke==value)return;karaoke=value;loadPronunciation();updateStatus();adapter.notifyDataSetChanged();if(manualUntil==0)center(false);}
    void setLoopStatus(boolean active){String label=active?"单句循环":"";if(loopStatus.equals(label))return;loopStatus=label;updateStatus();}
    LyricsDocument document(){return document;}
    int currentCue(){return selectedCue();}
    private boolean pronunciationEnabled(){return karaoke?prefs.bool("karaoke_pronunciation",true):prefs.bool("lyrics_pronunciation",false);}
    private void loadPronunciation(){
        if(raw.isEmpty()||pronunciationScope.isEmpty()||!pronunciationEnabled()){
            if(pronunciationRequest!=null)pronunciationRequest.cancel();pronunciationRequest=null;pronunciation=null;pronunciationKey="";pronunciationStatus="";updateStatus();adapter.notifyDataSetChanged();return;
        }
        PronunciationEngine.Language language=PronunciationRepository.language(prefs,pronunciationScope);
        String key=PronunciationRepository.cacheKey(pronunciationScope,raw,language);if(key.equals(pronunciationKey))return;
        if(pronunciationRequest!=null)pronunciationRequest.cancel();pronunciationKey=key;pronunciation=null;pronunciationStatus="正在生成发音…";updateStatus();
        pronunciationRequest=PronunciationRepository.get(getContext()).load(pronunciationScope,raw,language,result->{
            if(!key.equals(pronunciationKey))return;pronunciation=result;pronunciationStatus=!result.error.isEmpty()?result.error:result.language==PronunciationEngine.Language.AUTO&&hasHan()?"汉字歌词请选择粤语；也可手动选择日语":"";
            updateStatus();adapter.notifyDataSetChanged();if(manualUntil==0)center(false);
        });
    }
    private boolean hasHan(){for(LyricsDocument.Cue cue:document.cues)for(int i=0;i<cue.original.length();i++){char c=cue.original.charAt(i);if(c>=0x3400&&c<=0x9fff)return true;}return false;}
    void dispose(){if(pronunciationRequest!=null)pronunciationRequest.cancel();pronunciationKey="";}
    @Override protected void onDetachedFromWindow(){dispose();super.onDetachedFromWindow();}
    void setPalette(Ui.Palette value){
        colors=value;Context context=getContext();
        translationButton.setBackground(Ui.background(context,android.graphics.Color.TRANSPARENT,colors.SOFT,colors));
        more.setBackground(Ui.background(context,android.graphics.Color.TRANSPARENT,colors.SOFT,colors));
        pronunciationIcon.setBackground(Ui.background(context,android.graphics.Color.TRANSPARENT,colors.SOFT,colors));
        follow.setBackground(Ui.background(context,colors.SURFACE,colors.SOFT,colors));
        list.setSelector(Ui.background(context,android.graphics.Color.TRANSPARENT,colors.SOFT,colors));
        updateStatus();updateVisible();
    }
    static final class Bookmark {final int first,top;final boolean manual;Bookmark(int first,int top,boolean manual){this.first=first;this.top=top;this.manual=manual;}}
    Bookmark bookmark(){return new Bookmark(list.getFirstVisiblePosition(),list.getChildCount()>0?list.getChildAt(0).getTop()-list.getPaddingTop():0,manualUntil!=0);}
    void restore(Bookmark state){if(!state.manual)return;manualUntil=clock.now()+5000;follow.setVisibility(VISIBLE);list.post(()->list.setSelectionFromTop(state.first,state.top));}
    void setResult(LyricsRepository.Result result) {
        if(result.text.isEmpty()){setMessage(result.display());return;}
        if(!raw.equals(result.text)){raw=result.text;document=result.document;rebuildRows();manualUntil=0;touching=false;message.setText(visibleCues.isEmpty()?"暂无可显示歌词":"");adapter.notifyDataSetChanged();follow.setVisibility(GONE);loadPronunciation();updateStatus();center(false);}
    }
    void setMessage(String text){raw="";document=LyricsDocument.EMPTY;visibleCues.clear();current=-1;wordEnd=0;manualUntil=0;touching=false;message.setText(text);adapter.notifyDataSetChanged();follow.setVisibility(GONE);loadPronunciation();updateStatus();}
    void updatePosition(long value){long before=position;position=Math.max(0,value);int selected=selectedCue();boolean changed=current!=selected;current=selected;int end=highlight();boolean progress=end!=wordEnd;wordEnd=end;if(changed||progress)updateVisible();if(!touching&&manualUntil!=0&&clock.now()>=manualUntil){resumeFollowing();return;}if(changed&&manualUntil==0)center(position>=before&&position-before<2000);}
    void setOffset(int value){offset=value;updatePosition(position);updateStatus();}
    void setFont(int value){int first=list.getFirstVisiblePosition(),top=list.getChildCount()>0?list.getChildAt(0).getTop()-list.getPaddingTop():0;font=value;adapter.notifyDataSetChanged();if(manualUntil==0)center(false);else list.post(()->list.setSelectionFromTop(first,top));}
    void setSeekListener(android.widget.AdapterView.OnItemLongClickListener listener){list.setOnItemLongClickListener(listener);}
    long seekPosition(int row){return document.timed&&row>=0&&row<visibleCues.size()?Math.max(0,document.cues.get(visibleCues.get(row)).timeMs-offset):-1;}
    private void rebuildRows(){visibleCues.clear();for(int i=0;i<document.cues.size();i++){LyricsDocument.Cue cue=document.cues.get(i);if(!cue.original.trim().isEmpty()||translated()&&!cue.translation.trim().isEmpty())visibleCues.add(i);}current=selectedCue();wordEnd=highlight();}
    private int rowForCue(int cue){int at=java.util.Collections.binarySearch(visibleCues,cue);return at>=0?at:-at-2;}
    private int selectedCue(){int row=rowForCue(document.indexAt(position+offset));return row<0?-1:visibleCues.get(row);}
    private int highlight(){return current<0?0:document.cues.get(current).wordEnd(position+offset);}
    private boolean translated(){return prefs.bool("lyrics_translation",true);}
    private void resumeFollowing(){manualUntil=0;follow.setVisibility(GONE);center(false);}
    private void updateVisible(){for(int i=0;i<list.getChildCount();i++){View child=list.getChildAt(i);int at=list.getFirstVisiblePosition()+i;if(child instanceof LinearLayout&&at<visibleCues.size())adapter.bind((LinearLayout)child,at);}}
    private void center(boolean smooth) {
        if(!document.timed||visibleCues.isEmpty())return;
        final int target=Math.max(0,rowForCue(current));final LyricsDocument snapshot=document;
        list.post(()->{if(manualUntil!=0||document!=snapshot||visibleCues.isEmpty()||target!=Math.max(0,rowForCue(current)))return;
            LinearLayout measured=(LinearLayout)adapter.getView(target,null,list);measured.measure(MeasureSpec.makeMeasureSpec(Math.max(1,list.getWidth()),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            TextView original=measured.findViewById(R.id.lyrics_original);int baseline=measured.getPaddingTop()+original.getBaseline();
            TextView reading=measured.findViewById(R.id.lyrics_reading);if(reading.getVisibility()!=GONE)baseline+=reading.getMeasuredHeight();
            int anchor=Math.round(list.getHeight()*.38f)-baseline-list.getPaddingTop();
            UiMotion motion=Ui.motion(getContext());
            if(smooth&&getWindowToken()!=null&&motion!=null&&motion.enabled())list.smoothScrollToPositionFromTop(target,anchor,240);else {list.smoothScrollBy(0,0);list.setSelectionFromTop(target,anchor);}
        });
    }
    private void updateStatus() {
        pronunciationIcon.setIcon("pronunciation",pronunciationEnabled()?colors.ACCENT:Ui.MUTED);pronunciationIcon.setSelected(pronunciationEnabled());
        boolean available=document.hasTranslation,on=available&&translated();translationButton.setEnabled(available);translationButton.setAlpha(available?1f:.45f);translationButton.setSelected(on);
        translationIcon.setIcon(!available?"translation_none":on?"translation_on":"translation",on?colors.ACCENT:Ui.MUTED);translationButton.setContentDescription(!available?"本曲没有译文":on?"隐藏歌词翻译":"显示歌词翻译");
        String label=!document.cues.isEmpty()&&!document.timed?"纯文本歌词":"";
        if(offset!=0)label+=(label.isEmpty()?"":" · ")+(offset>0?"+":"")+offset+" ms";
        if(!loopStatus.isEmpty())label+=(label.isEmpty()?"":" · ")+loopStatus;
        if(!pronunciationStatus.isEmpty())label+=(label.isEmpty()?"":" · ")+pronunciationStatus;
        status.setText(label);status.setVisibility(label.isEmpty()?GONE:VISIBLE);
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount(){return visibleCues.size();}
        @Override public Object getItem(int position){return document.cues.get(visibleCues.get(position));}
        @Override public long getItemId(int position){return visibleCues.get(position);}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            LinearLayout row;
            if(recycled==null){
                row=Ui.column(getContext());Ui.pad(row,0,12,8,20);row.setMinimumHeight(Ui.dp(getContext(),64));
                TextView reading=Ui.text(getContext(),"",20,Ui.MUTED,false);reading.setId(R.id.lyrics_reading);reading.setLineSpacing(Ui.dp(getContext(),4),1);Ui.pad(reading,0,0,0,6);row.addView(reading);
                TextView original=new StableLine(getContext());original.setId(R.id.lyrics_original);original.setIncludeFontPadding(false);original.setTextColor(Ui.MUTED);original.setLineSpacing(Ui.dp(getContext(),6),1);row.addView(original);
                TextView translation=Ui.text(getContext(),"",20,Ui.MUTED,false);translation.setId(R.id.lyrics_translation_line);Ui.pad(translation,0,6,0,0);translation.setLineSpacing(Ui.dp(getContext(),4),1);row.addView(translation);
            }else row=(LinearLayout)recycled;
            bind(row,position);return row;
        }
        void bind(LinearLayout row,int position){
            int cueIndex=visibleCues.get(position);LyricsDocument.Cue cue=document.cues.get(cueIndex);boolean active=cueIndex==current;
            TextView original=row.findViewById(R.id.lyrics_original),translation=row.findViewById(R.id.lyrics_translation_line),reading=row.findViewById(R.id.lyrics_reading);
            original.setTextSize(font);translation.setTextSize(Math.max(16,Math.round(font*2f/3f)));original.setTextColor(active?Ui.TEXT:Ui.MUTED);
            if(active&&wordEnd>0&&wordEnd<=cue.original.length()){SpannableString text=new SpannableString(cue.original);text.setSpan(new ForegroundColorSpan(colors.ACCENT),0,wordEnd,0);original.setText(text);}else original.setText(cue.original);
            original.setTypeface(Typeface.create("sans-serif",active?Typeface.BOLD:Typeface.NORMAL));translation.setText(cue.translation);translation.setTextColor(active?0xffe3d6c3:Ui.MUTED);translation.setVisibility(translated()&&!cue.translation.isEmpty()?VISIBLE:GONE);
            PronunciationEngine.Line line=pronunciation==null?PronunciationEngine.Line.EMPTY:pronunciation.line(cueIndex);
            reading.setTextSize(Math.max(17,Math.round(font*.58f)));reading.setTextColor(active?0xffe3d6c3:Ui.MUTED);
            int spoken=active?line.highlightedEnd(wordEnd):0;
            if(spoken>0&&spoken<=line.text.length()){SpannableString text=new SpannableString(line.text);text.setSpan(new ForegroundColorSpan(colors.ACCENT),0,spoken,0);reading.setText(text);}else reading.setText(line.text);
            reading.setVisibility(pronunciationEnabled()&&!line.text.isEmpty()?VISIBLE:GONE);
            row.setSelected(active);row.setContentDescription(active?"当前歌词":"");
        }
    }
    /** Reserve the taller normal/bold layout so activation never changes row height. */
    private static final class StableLine extends TextView {
        StableLine(Context c){super(c);}
        @Override protected void onMeasure(int ws,int hs){
            super.onMeasure(ws,hs);int width=getMeasuredWidth()-getPaddingLeft()-getPaddingRight();if(width<=0)return;
            android.text.TextPaint paint=new android.text.TextPaint(getPaint());int height=0;
            for(int style:new int[]{Typeface.NORMAL,Typeface.BOLD}){paint.setTypeface(Typeface.create("sans-serif",style));android.text.StaticLayout layout=new android.text.StaticLayout(getText(),paint,width,android.text.Layout.Alignment.ALIGN_NORMAL,1,Ui.dp(getContext(),6),false);height=Math.max(height,layout.getHeight());}
            setMeasuredDimension(getMeasuredWidth(),Math.max(getMeasuredHeight(),height+getPaddingTop()+getPaddingBottom()));
        }
    }
}
