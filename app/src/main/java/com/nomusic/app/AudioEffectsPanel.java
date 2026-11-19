package com.nomusic.app;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Presets and editable bands share the same screen and preview live. */
final class AudioEffectsPanel extends LinearLayout {
    interface Change { void apply(AudioEffects.Profile value); }
    private AudioEffects.Profile profile;
    private final Ui.Palette colors;
    private final TextView[] modes;
    private final LinearLayout custom,bass;
    private final TextView status;

    AudioEffectsPanel(Context context,Ui.Palette colors,AudioEffects.Profile profile,Change change){
        super(context);this.profile=profile;this.colors=colors;setOrientation(VERTICAL);
        Ui.pad(this,20,0,20,12);
        status=Ui.text(context,"",14,colors.MUTED,false);status.setId(R.id.effects_status);Ui.pad(status,0,0,0,10);addView(status);
        AudioEffects.Mode[] values=AudioEffects.Mode.values();modes=new TextView[values.length];
        for(int start=0;start<values.length;start+=3){
            LinearLayout row=Ui.row(context);
            for(int i=start;i<Math.min(start+3,values.length);i++){
                AudioEffects.Mode mode=values[i];
                TextView choice=Ui.button(context,mode.label,false,v->{this.profile=this.profile.mode(mode);updateSelection();change.apply(this.profile);});
                choice.setTextSize(16);choice.setMinHeight(dp(48));choice.setMinimumHeight(dp(48));Ui.pad(choice,4,0,4,0);
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);if(i>start)params.leftMargin=dp(6);
                row.addView(choice,params);modes[i]=choice;
            }
            addView(row);Ui.gap(this,6);
        }
        custom=Ui.column(context);addView(custom);
        for(int i=0;i<AudioEffects.BAND_LABELS.length;i++){
            final int band=i;int max=AudioEffects.MAX_DB;
            slider(custom,AudioEffects.BAND_LABELS[i],max*2,profile.gain(i)+max,
                    n->(n>max?"+":"")+(n-max)+" dB",n->change.apply(this.profile=this.profile.band(band,n-max)));
        }
        bass=Ui.column(context);addView(bass);
        slider(bass,"低音增强",10,profile.bass/10,n->n*10+"%",n->change.apply(this.profile=this.profile.bass(n*10)));
        updateSelection();
    }

    private int dp(int value){return Ui.dp(getContext(),value);}
    void status(String message){if(!message.contentEquals(status.getText()))status.setText(message);}
    private void updateSelection(){
        for(int i=0;i<modes.length;i++){
            TextView view=modes[i];boolean active=i==profile.mode.ordinal();view.setSelected(active);
            view.setTextColor(active?colors.ACCENT:colors.TEXT);
            view.setBackground(Ui.background(getContext(),active?colors.SOFT:colors.PANEL,colors.SOFT,colors));
            view.setContentDescription(view.getText()+(active?"，已选":""));
        }
        custom.setVisibility(profile.mode==AudioEffects.Mode.CUSTOM?VISIBLE:GONE);
        bass.setVisibility(profile.mode==AudioEffects.Mode.OFF?GONE:VISIBLE);
    }

    private interface Format { String text(int step); }
    private interface Value { void set(int step); }
    private void slider(LinearLayout parent,String title,int max,int initial,Format format,Value value){
        Context context=getContext();LinearLayout row=Ui.row(context);
        TextView label=Ui.text(context,title,14,colors.TEXT,false);row.addView(label,new LinearLayout.LayoutParams(dp(78),-2));
        TimelineSeekBar seek=new TimelineSeekBar(context);seek.setPalette(colors);seek.setMax(max);seek.setProgress(initial);seek.setKeyProgressIncrement(1);
        row.addView(seek,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView reading=Ui.text(context,format.text(initial),14,colors.MUTED,false);reading.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        row.addView(reading,new LinearLayout.LayoutParams(dp(54),-1));seek.setContentDescription(title+"，"+reading.getText());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar view){}
            @Override public void onStopTrackingTouch(SeekBar view){}
            @Override public void onProgressChanged(SeekBar view,int progress,boolean user){
                String text=format.text(progress);reading.setText(text);seek.setContentDescription(title+"，"+text);
                if(user)value.set(progress);
            }
        });
        parent.addView(row,new LinearLayout.LayoutParams(-1,dp(48)));
    }
}
