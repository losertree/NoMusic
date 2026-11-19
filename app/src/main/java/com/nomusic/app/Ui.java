package com.nomusic.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG=0xff151412,PANEL=0xff1d1b18,SURFACE=0xff27231f,LINE=0xff494137,
            TEXT=0xfff4f0e8,MUTED=0xffc2b8aa,ACCENT=0xffd9b982,DARK=0xff241b10;
    static final class Palette {
        final int BG,PANEL,SURFACE,RAISED,LINE,TEXT,MUTED,FAINT,ACCENT,SOFT,DARK;
        final boolean light;
        Palette(boolean light,int bg,int panel,int surface,int raised,int line,int text,int muted,int faint,int accent,int soft,int ink) {
            this.light=light;BG=bg;PANEL=panel;SURFACE=surface;RAISED=raised;LINE=line;TEXT=text;MUTED=muted;FAINT=faint;ACCENT=accent;SOFT=soft;DARK=ink;
        }
    }
    static final Palette NIGHT=new Palette(false,BG,PANEL,SURFACE,0xff322d27,LINE,TEXT,MUTED,0xffa69b8c,ACCENT,0xff3c3225,DARK);
    static final Palette DAY=new Palette(true,0xfff7f6f3,0xffefede8,0xffffffff,0xffe8e3db,0xffd4cec4,0xff26231f,0xff625c53,0xff6b6256,0xff805b2e,0xffede0cd,0xffffffff);
    static final class ThemeColor {
        final String id,label;
        final int night,day;
        ThemeColor(String id,String label,int night,int day){this.id=id;this.label=label;this.night=night;this.day=day;}
    }
    static final ThemeColor[] THEME_COLORS={
            new ThemeColor("gold","暖金",0xffd9b982,0xff805b2e),
            new ThemeColor("orange","落日橙",0xfff1ab83,0xff9a4926),
            new ThemeColor("rose","玫瑰",0xffeab1ba,0xff9e405b),
            new ThemeColor("jade","青玉",0xff8fd0b5,0xff286e57),
            new ThemeColor("blue","雾蓝",0xffa4c5ee,0xff3565a0),
            new ThemeColor("purple","藤紫",0xffc3b4ed,0xff70509c)
    };
    static ThemeColor themeColor(AppPrefs prefs){
        for(ThemeColor choice:THEME_COLORS)if(choice.id.equals(prefs.themeColor()))return choice;
        return THEME_COLORS[0];
    }
    static Palette libraryPalette(AppPrefs prefs){return themePalette(prefs,prefs.bool("light_theme",false));}
    static Palette themePalette(AppPrefs prefs,boolean light){
        Palette base=light?DAY:NIGHT;ThemeColor choice=themeColor(prefs);int accent=light?choice.day:choice.night;
        if(choice==THEME_COLORS[0])return base;
        return new Palette(light,base.BG,base.PANEL,base.SURFACE,base.RAISED,base.LINE,base.TEXT,base.MUTED,base.FAINT,
                accent,blend(base.SURFACE,accent,light?.12f:.16f),base.DARK);
    }
    static int blend(int from,int to,float amount) {
        return Color.rgb(Math.round(Color.red(from)*(1-amount)+Color.red(to)*amount),
                Math.round(Color.green(from)*(1-amount)+Color.green(to)*amount),
                Math.round(Color.blue(from)*(1-amount)+Color.blue(to)*amount));
    }
    static Palette coverPalette(Palette base,int tint) {
        if(tint==AmbientDrawable.DEFAULT)return base;
        float[] hsv=new float[3];Color.colorToHSV(tint,hsv);
        hsv[1]=Math.min(.5f,hsv[1]*1.2f);hsv[2]=base.light?.45f:.93f;
        int accent=Color.HSVToColor(hsv),soft=blend(base.SURFACE,accent,base.light?.13f:.16f);
        return new Palette(base.light,base.BG,base.PANEL,base.SURFACE,base.RAISED,base.LINE,base.TEXT,base.MUTED,base.FAINT,accent,soft,base.DARK);
    }
    static Palette colors(Context c) {
        while(c instanceof android.content.ContextWrapper) {
            if(c instanceof MainActivity)return ((MainActivity)c).uiColors();
            Context next=((android.content.ContextWrapper)c).getBaseContext();if(next==c)break;c=next;
        }
        return NIGHT;
    }
    static UiMotion motion(Context c){
        while(c instanceof android.content.ContextWrapper){
            if(c instanceof MainActivity)return ((MainActivity)c).uiMotion();
            Context next=((android.content.ContextWrapper)c).getBaseContext();if(next==c)break;c=next;
        }
        return null;
    }
    static void press(View view){UiMotion motion=motion(view.getContext());if(motion!=null)motion.bindPress(view);}
    static int dp(Context context,float value) { return (int)(value*context.getResources().getDisplayMetrics().density+0.5f); }
    static LinearLayout column(Context c) { LinearLayout v=new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static LinearLayout row(Context c) { LinearLayout v=new LinearLayout(c); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    static TextView text(Context c,String value,int size,int color,boolean bold) {
        TextView view=new TextView(c); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL); view.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        view.setIncludeFontPadding(false); return view;
    }
    static GradientDrawable shape(int color,int radius,Context c) {
        GradientDrawable drawable=new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(c,radius)); return drawable;
    }
    static GradientDrawable gradient(Context c,int start,int end,int radius) {
        GradientDrawable drawable=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{start,end});
        drawable.setCornerRadius(dp(c,radius));return drawable;
    }
    static StateListDrawable background(Context c,int normal,int selected) {
        return background(c,normal,selected,colors(c));
    }
    static StateListDrawable background(Context c,int normal,int selected,Palette palette) {
        StateListDrawable drawable=new StateListDrawable();
        GradientDrawable focus=shape(selected,12,c); focus.setStroke(dp(c,2),palette.ACCENT);
        drawable.addState(new int[]{android.R.attr.state_focused},focus);
        drawable.addState(new int[]{android.R.attr.state_pressed},shape(selected==palette.ACCENT?blend(selected,palette.light?Color.BLACK:Color.WHITE,.12f):palette.RAISED,12,c));
        drawable.addState(new int[]{android.R.attr.state_selected},shape(selected,12,c));
        drawable.addState(new int[]{},shape(normal,12,c)); return drawable;
    }
    static StateListDrawable playButton(Context c,Palette palette) {
        StateListDrawable drawable=new StateListDrawable();
        GradientDrawable focused=shape(Color.TRANSPARENT,14,c);focused.setStroke(dp(c,2),palette.ACCENT);
        GradientDrawable pressed=shape(Color.TRANSPARENT,14,c);pressed.setStroke(dp(c,1),palette.ACCENT);
        drawable.addState(new int[]{android.R.attr.state_focused},focused);
        drawable.addState(new int[]{android.R.attr.state_pressed},pressed);
        drawable.addState(new int[]{},new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));return drawable;
    }
    static TextView button(Context c,String label,boolean primary,View.OnClickListener listener) {
        Palette palette=colors(c);
        TextView view=text(c,label,20,primary?palette.DARK:palette.TEXT,true); view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(c,56)); view.setPadding(dp(c,16),dp(c,8),dp(c,16),dp(c,8));
        view.setBackground(background(c,primary?palette.ACCENT:palette.SURFACE,primary?palette.ACCENT:palette.SOFT));
        view.setFocusable(true); view.setClickable(true); view.setOnClickListener(listener); view.setContentDescription(label);
        press(view);
        return view;
    }
    static GradientDrawable surface(Context c,int radius) {
        GradientDrawable shape=shape(colors(c).SURFACE,radius,c);shape.setStroke(dp(c,1),colors(c).LINE);return shape;
    }
    static void pad(View v,int left,int top,int right,int bottom) { Context c=v.getContext(); v.setPadding(dp(c,left),dp(c,top),dp(c,right),dp(c,bottom)); }
    static void gap(LinearLayout parent,int height) { parent.addView(new View(parent.getContext()),new LinearLayout.LayoutParams(1,dp(parent.getContext(),height))); }
}
