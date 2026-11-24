package com.nomusic.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Build;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final Handler handler=new Handler();
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final List<MediaItem> items=new ArrayList<>();
    private AppPrefs prefs;
    private EmbyClient client;
    private ImageLoader images;
    private AudioCache audioCache;
    private LyricsRepository lyrics;
    private LyricsRepository.Request lyricRequest;
    private PlaybackService playback;
    private boolean bound,destroyed,shell,loading,dragging;
    private int requestId,total,loaded,shownQueueRevision=-1,queueDrag=-1,queueDrop=-1;
    private String section="home",parentId=null,parentType=null,parentName=null,query="";
    private String sort="",genre="",genreName="";
    private boolean descending,updateBusy,downloadsLoading;
    private TextView libraryCacheStatus,libraryCacheButton;
    private View audioCacheSetting;
    private int shownDownloadRevision=-1;
    private final ArrayList<CacheLabel> homeCacheLabels=new ArrayList<>();
    private boolean returningToPlayer;
    private String expiredMessage="";
    private PageState playerBack;
    private BrowserSnapshot playerBackList;
    private final LinkedHashMap<String,BrowserSnapshot> browserPages=new LinkedHashMap<>(16,.75f,true);
    private final List<LegacyAudioPage> legacyAudioPages=new ArrayList<>();
    private int browserEpoch=-1;
    private final List<PageState> folderTrail=new ArrayList<>();
    private FrameLayout shellFrame;
    private View shellRoot,nowRoot;
    private Runnable pendingPlayerExit;
    private AlertDialog activeDialog;
    private UpdateManager.Release pendingRelease;
    private boolean awaitingInstallPermission;
    private LinearLayout content,nav,playerPane,compactPane,body;
    private TextView heading,subheading,emptyText,more,playList,playerTitle,playerArtist,playerState,positionText,durationText,repeatLabel;
    private LinearLayout empty;
    private ProgressBar spinner;
    private ListView list;
    private LibraryAdapter adapter;
    private EditText searchInput;
    private ImageView cover;
    private IconView playIcon,shuffleIcon,repeatIcon,favoriteIcon;
    private View playButton;
    private SeekBar seek;
    private String renderedTrack="";
    private Bitmap renderedCover;
    private TextView queueLabel,nowTitle,nowArtist,nowBitrate,nowAlbum;
    private LyricsView nowLyrics;
    private ImageView nowCover;
    private TextView nowStatus,nowTimer;
    private TextView settingsQuality,settingsEffects,nowQuality;
    private IconView effectsIcon;
    private AudioEffectsPanel effectsPanel;
    private LyricsRepository.Result baseLyrics;
    private LyricsEdits lyricEdits=LyricsEdits.EMPTY;
    private String nowTrack="";
    private boolean wide;
    private Ui.Palette colors=Ui.NIGHT;
    private Ui.Palette playerColors=Ui.NIGHT;
    private int artworkTint=AmbientDrawable.DEFAULT;
    private String paletteTrack="";
    Ui.Palette uiColors(){return colors;}
    private UiMotion motion;
    UiMotion uiMotion(){return motion;}
    private int screenWidth,gridColumns;
    private boolean searchExpanded,restoreAtStartup=true,playlistBusy;
    private String downloadTab="saved";
    private TextView toolbarTitle,toolbarMeta;
    private View toolbarBack,toolbarFilter,toolbarSort,settingsEntry,queueButton,downloadButton;
    private LinearLayout searchBox;
    private ProgressBar miniProgress;
    private IconView downloadIcon;
    private AmbientDrawable ambience;
    private PlayerStage playerStage;
    private FrameLayout queueOverlay;
    private View drawerPanel;
    private boolean drawerClosing;
    private ListView drawerList;
    private TextView drawerSummary;
    private QueueAdapter drawerAdapter;
    private int drawerRevision=-1,drawerIndex=-2,drawerDrag=-1,drawerDrop=-1,drawerDragRevision;
    private final List<MediaItem> drawerItems=new ArrayList<>();
    private final List<View> drawerFocus=new ArrayList<>();
    private final List<BrowseReturn> browseTrail=new ArrayList<>();
    private LyricsView.Bookmark lyricBookmark;
    private String lyricBookmarkTrack="";
    private boolean restoreLyricBookmark;
    private boolean karaoke;
    private IconView karaokeIcon;

    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            playback=((PlaybackService.LocalBinder)binder).service();
            updatePlayer();
            if("queue".equals(section) && shell) showQueue();
            if(restoreAtStartup){restoreAtStartup=false;if(shell&&(playback.current()!=null||getIntent().getBooleanExtra("open_player",false)))showLyrics();}
        }
        @Override public void onServiceDisconnected(ComponentName name) { playback=null; }
    };
    private final BroadcastReceiver changes=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            updatePlayer();
            if("queue".equals(section) && shell)showQueue();
            else if(adapter!=null)adapter.notifyDataSetChanged();
            refreshDrawer();
        }
    };
    private final BroadcastReceiver appChanges=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            if(AudioCache.CHANGED.equals(intent.getAction())) {
                updateDownloadControl();refreshVisibleCache();
                if(shell&&"downloads".equals(section)&&shownDownloadRevision!=audioCache.downloadRevision())showDownloads();
                updateLibraryCacheControls();
                return;
            }
            if(client.currentSession())return;
            boolean hadLogin=client.authenticated();
            client=new EmbyClient(prefs);
            if(hadLogin&&!client.authenticated()&&prefs.hasAccount()) {
                if(activeDialog!=null)activeDialog.dismiss();
                expiredMessage="Emby 登录已失效，请重新登录。已下载的歌曲仍可离线播放。";
                showLogin();
            }
        }
    };
    private final Runnable tick=new Runnable() {
        @Override public void run() { if(shell) updateTimeline(); handler.postDelayed(this,shell&&"player".equals(section)?150:500); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=new AppPrefs(this); client=new EmbyClient(prefs); images=new ImageLoader();lyrics=LyricsRepository.get(this);audioCache=AudioCache.get(this);
        motion=new UiMotion(prefs);
        karaoke=getIntent().getBooleanExtra("karaoke",false);
        if(prefs.hasAccount()&&!prefs.loggedIn())expiredMessage="请重新登录 Emby，或播放已下载的歌曲。";
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        registerReceiver(changes,new IntentFilter(PlaybackService.CHANGED));
        IntentFilter events=new IntentFilter(AppPrefs.SESSION_CHANGED);events.addAction(AudioCache.CHANGED);registerReceiver(appChanges,events);
        startService(new Intent(this,PlaybackService.class));
        bound=bindService(new Intent(this,PlaybackService.class),connection,Context.BIND_AUTO_CREATE);
        if(prefs.loggedIn()){showShell();if(getIntent().getBooleanExtra("cache_settings",false)){restoreAtStartup=false;navigate("settings");}}else showLogin();
        if(prefs.bool("floating_lyrics",false)&&FloatingLyricsService.allowed(this)&&prefs.hasAccount())startService(new Intent(this,FloatingLyricsService.class));
        if(prefs.bool("check_updates",true)&&System.currentTimeMillis()-prefs.store.getLong("update_checked",0)>24L*60*60*1000)
            handler.postDelayed(()->checkUpdate(false),1600);
        handler.post(tick);
    }

    @Override protected void onResume() {
        super.onResume();
        if(motion!=null)motion.foreground(true);
        FloatingLyricsService.insideApp=true;
        if(prefs!=null && prefs.bool("keep_screen",true)) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setFullscreen(shell&&"player".equals(section));
        retryLegacyAudioPages();
        if(awaitingInstallPermission) {
            awaitingInstallPermission=false;
            if(!UpdateManager.needsInstallerPermission(this)&&pendingApk!=null&&pendingApk.isFile())promptInstall(pendingApk);
        }
    }

    @Override protected void onPause() {
        if(drawerClosing)closeDrawer();
        if(motion!=null)motion.foreground(false);
        if(pendingPlayerExit!=null)finishPlayerExit();
        else if(nowRoot!=null&&shell&&"player".equals(section))shellRoot.setVisibility(View.GONE);
        FloatingLyricsService.insideApp=false;super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if(focused)setFullscreen(shell&&"player".equals(section));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);updatePlayer();
        if(shell&&intent.getBooleanExtra("cache_settings",false))navigate("settings");
        else if(shell&&intent.getBooleanExtra("open_player",false)){if(intent.getBooleanExtra("karaoke",false))karaoke=true;showLyrics();applyKaraoke();}
    }

    private int dp(float value) { return Ui.dp(this,value); }
    private TextView text(String value,int size,int color,boolean bold) { return Ui.text(this,value,size,color,bold); }
    private TextView button(String value,boolean primary,View.OnClickListener listener) { return Ui.button(this,value,primary,listener); }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_SHORT).show(); }
    private void async(Runnable task) { if(!destroyed) io.execute(task); }
    private void onUi(Runnable task) { handler.post(() -> { if(!destroyed) task.run(); }); }
    private void dialog(AlertDialog.Builder builder) {
        if(destroyed||isFinishing())return;
        activeDialog=builder.show();
        android.view.Window window=activeDialog.getWindow();
        if(window!=null){window.setWindowAnimations(0);window.setBackgroundDrawable(Ui.shape(colors.SURFACE,20,this));window.getDecorView().setPadding(0,0,0,0);motion.dialog(window.getDecorView());}
        for(String name:new String[]{"parentPanel","topPanel","contentPanel","customPanel","buttonPanel"}) {
            View panel=dialogPart(name);if(panel!=null)panel.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }
        View title=dialogPart("alertTitle");if(title instanceof TextView)((TextView)title).setTextColor(colors.TEXT);
        for(String name:new String[]{"titleDivider","titleDividerTop","titleDividerNoCustom"}){View divider=dialogPart(name);if(divider!=null)divider.setVisibility(View.GONE);}
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){android.widget.Button button=activeDialog.getButton(which);if(button!=null){button.setMinHeight(dp(56));button.setTextSize(18);button.setTextColor(colors.ACCENT);button.setBackground(Ui.background(this,android.graphics.Color.TRANSPARENT,colors.SOFT));Ui.press(button);}}
    }
    private View dialogPart(String name){int id=getResources().getIdentifier(name,"id","android");return id==0?null:activeDialog.findViewById(id);}

    private void setFullscreen(boolean fullscreen) {
        if(fullscreen)getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(!fullscreen?0:Build.VERSION.SDK_INT>=19?
                View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY:
                View.SYSTEM_UI_FLAG_LOW_PROFILE|View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private EditText field(String hint,int type,int id) {
        EditText view=new EditText(this); view.setId(id); view.setTextSize(18); view.setTextColor(colors.TEXT);
        view.setHintTextColor(colors.MUTED); view.setHint(hint); view.setSingleLine(true); view.setInputType(type);
        Ui.pad(view,16,8,16,8); view.setBackground(Ui.background(this,colors.SURFACE,colors.SURFACE));
        view.setMinHeight(dp(56)); return view;
    }

    private void showLogin() {
        motion.finishAll();
        pendingPlayerExit=null;
        closeDrawer();colors=Ui.libraryPalette(prefs);
        cancelLyrics();
        shell=false;section="home";returningToPlayer=false;adapter=null;++requestId;setFullscreen(false);
        LinearLayout root=Ui.row(this); root.setBackground(Ui.gradient(this,colors.PANEL,colors.BG,0)); Ui.pad(root,40,24,40,24);
        LinearLayout intro=Ui.column(this); intro.setGravity(Gravity.CENTER_VERTICAL); Ui.pad(intro,16,0,48,0);
        intro.addView(brandMark(),new LinearLayout.LayoutParams(dp(72),dp(72)));
        Ui.gap(intro,24); intro.addView(text("NoMusic",42,colors.TEXT,true)); Ui.gap(intro,16);
        intro.addView(text("音乐，随行。",28,colors.TEXT,true)); Ui.gap(intro,12);
        TextView lead=text("连接你的 Emby 音乐库\n让每一段旅途，都有好音乐。",18,colors.MUTED,false); lead.setLineSpacing(dp(6),1); intro.addView(lead);
        Ui.gap(intro,36); intro.addView(text("EMBY  /  为车载聆听而设计",13,colors.MUTED,false));
        root.addView(intro,new LinearLayout.LayoutParams(0,-1,1));
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout form=Ui.column(this); form.setGravity(Gravity.CENTER_VERTICAL); Ui.pad(form,24,16,24,16);
        form.setBackground(Ui.shape(colors.PANEL,16,this));
        form.addView(text("连接音乐库",28,colors.TEXT,true)); Ui.gap(form,8);
        form.addView(text("使用你的 Emby 账号登录",16,colors.MUTED,false)); Ui.gap(form,24);
        form.addView(text("服务器地址",14,colors.MUTED,false)); Ui.gap(form,8);
        EditText server=field("https://music.example.com:8096",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI,R.id.server);
        server.setText(prefs.server()); form.addView(server,new LinearLayout.LayoutParams(-1,dp(56))); Ui.gap(form,16);
        form.addView(text("用户名",14,colors.MUTED,false)); Ui.gap(form,8);
        EditText username=field("Emby 用户名",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,R.id.username);
        username.setText(prefs.username()); form.addView(username,new LinearLayout.LayoutParams(-1,dp(56))); Ui.gap(form,16);
        form.addView(text("密码",14,colors.MUTED,false)); Ui.gap(form,8);
        EditText password=field("Emby 密码（允许留空）",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,R.id.password);
        form.addView(password,new LinearLayout.LayoutParams(-1,dp(56))); Ui.gap(form,16);
        TextView status=text(expiredMessage.isEmpty()?"支持服务器首页或 Web 页面地址":expiredMessage,14,colors.MUTED,false); status.setMinHeight(dp(36)); form.addView(status); Ui.gap(form,8);
        TextView signIn=button("连接 Emby",true,null); signIn.setId(R.id.sign_in);
        View.OnClickListener login=v -> {
            final String address;
            try { address=EmbyClient.normalizeServer(server.getText().toString()); }
            catch(Exception e) { status.setText(e.getMessage()); return; }
            final String name=username.getText().toString().trim(), secret=password.getText().toString();
            if(name.isEmpty()) { status.setText("请输入用户名"); return; }
            signIn.setEnabled(false); signIn.setAlpha(0.6f); signIn.setText("正在连接…"); status.setText("正在验证账号，请稍候");
            hideKeyboard();
            async(() -> {
                try {
                    JSONObject session=EmbyClient.authenticate(address,name,secret,prefs.deviceId());
                    JSONObject user=session.getJSONObject("User");
                    onUi(() -> {
                        prefs.saveSession(session.optString("_Server"),user.optString("Id"),user.optString("Name",name),session.optString("AccessToken"));
                        password.setText("");expiredMessage="";client=new EmbyClient(prefs);section="home";parentId=null;query="";showShell();
                        if(prefs.bool("floating_lyrics",false)&&FloatingLyricsService.allowed(this))startService(new Intent(this,FloatingLyricsService.class));
                    });
                } catch(Exception e) {
                    onUi(() -> { password.setText(""); status.setText(EmbyClient.message(e)); signIn.setEnabled(true);signIn.setAlpha(1);signIn.setText("连接 Emby"); });
                }
            });
        };
        signIn.setOnClickListener(login); password.setImeOptions(EditorInfo.IME_ACTION_GO);
        password.setOnEditorActionListener((v,action,event) -> { if(action==EditorInfo.IME_ACTION_GO && signIn.isEnabled()) { login.onClick(signIn);return true; } return false; });
        form.addView(signIn,new LinearLayout.LayoutParams(-1,dp(56)));
        if(prefs.hasAccount()) {
            Ui.gap(form,10);form.addView(button("打开离线歌曲",false,v->{section="downloads";showShell();}));
        }
        Ui.gap(form,10);form.addView(button("检查更新",false,v->checkUpdate(true)));
        scroll.addView(form,new ScrollView.LayoutParams(-1,-1));
        int width=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);
        root.addView(scroll,new LinearLayout.LayoutParams(dp(Math.min(440,Math.max(320,width/2))),-1));
        setContentView(root);
    }

    private View iconButton(String icon,String label,View.OnClickListener listener) {
        LinearLayout view=Ui.row(this); view.setGravity(Gravity.CENTER); view.setBackground(Ui.background(this,android.graphics.Color.TRANSPARENT,colors.SURFACE));
        IconView image=new IconView(this,icon,colors.TEXT); view.addView(image,new LinearLayout.LayoutParams(dp(24),dp(24)));
        view.setContentDescription(label);view.setFocusable(true);view.setClickable(true);view.setOnClickListener(listener);
        Ui.press(view);
        view.setMinimumWidth(dp(56));view.setMinimumHeight(dp(56));return view;
    }

    private ImageView brandMark(){
        ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.ic_launcher);mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return mark;
    }


    private void showShell() {
        closeDrawer();motion.finishAll();
        pendingPlayerExit=null;
        shell=true;client=new EmbyClient(prefs);colors=Ui.libraryPalette(prefs);
        screenWidth=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);
        wide=screenWidth>=1100;
        shellFrame=new FrameLayout(this);nowRoot=null;
        LinearLayout root=Ui.row(this);shellRoot=root;root.setBackgroundColor(colors.BG);root.setFocusableInTouchMode(true);
        buildRail(root);
        LinearLayout workspace=Ui.column(this);root.addView(workspace,new LinearLayout.LayoutParams(0,-1,1));
        buildToolbar(workspace);
        content=Ui.column(this);Ui.pad(content,24,8,24,0);workspace.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        playerPane=Ui.column(this);compactPane=playerPane;
        LinearLayout.LayoutParams dock=new LinearLayout.LayoutParams(-1,dp(96));dock.setMargins(dp(24),dp(16),dp(24),dp(24));workspace.addView(playerPane,dock);
        shellFrame.addView(root,new FrameLayout.LayoutParams(-1,-1));
        buildPlayer();setContentView(shellFrame);root.requestFocus();navigate(section);
    }

    private LinearLayout navigationItem(String icon,String label,String target,boolean narrow) {
        LinearLayout item=narrow?Ui.column(this):Ui.row(this);item.setGravity(narrow?Gravity.CENTER:Gravity.CENTER_VERTICAL);Ui.pad(item,narrow?0:16,0,8,0);item.setTag(target);
        IconView image=new IconView(this,icon,colors.MUTED);image.setTag(icon);item.addView(image,new LinearLayout.LayoutParams(dp(24),dp(24)));
        TextView title=text(label,narrow?16:20,colors.MUTED,false);Ui.pad(title,narrow?0:16,narrow?4:0,0,0);item.addView(title);
        item.setBackground(Ui.background(this,colors.PANEL,colors.SOFT));item.setContentDescription(label);item.setFocusable(true);item.setClickable(true);item.setOnClickListener(v->navigate(target));
        Ui.press(item);
        return item;
    }

    private void buildRail(LinearLayout root) {
        boolean narrow=screenWidth<900;
        LinearLayout rail=Ui.column(this);Ui.pad(rail,12,24,12,24);rail.setBackgroundColor(colors.PANEL);
        LinearLayout brand=Ui.row(this);brand.setGravity(narrow?Gravity.CENTER:Gravity.CENTER_VERTICAL);Ui.pad(brand,narrow?0:8,0,0,0);
        brand.addView(brandMark(),new LinearLayout.LayoutParams(dp(36),dp(36)));
        if(!narrow){TextView name=text("NoMusic",22,colors.TEXT,true);Ui.pad(name,12,0,0,0);brand.addView(name);}
        rail.addView(brand,new LinearLayout.LayoutParams(-1,dp(40)));Ui.gap(rail,40);
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);nav=Ui.column(this);
        String[][] entries={{"home","首页","home"},{"music","曲库","songs"},{"heart","我喜欢","favorites"},{"queue","歌单","playlists"},{"download","离线","downloads"}};
        for(String[] e:entries){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(narrow?72:56));lp.bottomMargin=dp(8);nav.addView(navigationItem(e[0],e[1],e[2],narrow),lp);}
        scroll.addView(nav);rail.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        View line=new View(this);line.setBackgroundColor(colors.LINE);rail.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));Ui.gap(rail,16);
        settingsEntry=navigationItem("settings","设置","settings",narrow);rail.addView(settingsEntry,new LinearLayout.LayoutParams(-1,dp(narrow?72:56)));
        root.addView(rail,new LinearLayout.LayoutParams(dp(narrow?80:wide?192:160),-1));
    }

    private void buildToolbar(LinearLayout workspace) {
        LinearLayout toolbar=Ui.row(this);Ui.pad(toolbar,24,16,24,8);
        toolbarBack=iconButton("back","返回",v->onBackPressed());toolbar.addView(toolbarBack,new LinearLayout.LayoutParams(dp(56),dp(56)));
        LinearLayout labels=Ui.column(this);toolbarTitle=text("首页",28,colors.TEXT,true);toolbarTitle.setSingleLine(true);toolbarTitle.setEllipsize(TextUtils.TruncateAt.END);labels.addView(toolbarTitle);
        toolbarMeta=text("",16,colors.MUTED,false);toolbarMeta.setSingleLine(true);toolbarMeta.setEllipsize(TextUtils.TruncateAt.END);labels.addView(toolbarMeta);toolbar.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        searchBox=Ui.row(this);searchBox.setBackground(Ui.background(this,colors.SURFACE,colors.SOFT));
        searchInput=field("搜索音乐",InputType.TYPE_CLASS_TEXT,R.id.search_input);searchInput.setTextSize(18);searchInput.setBackgroundColor(android.graphics.Color.TRANSPARENT);searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setVisibility(wide||searchExpanded?View.VISIBLE:View.GONE);searchBox.addView(searchInput,new LinearLayout.LayoutParams(0,dp(56),1));
        searchBox.addView(iconButton("search","搜索歌曲、专辑、歌手",v->{
            if(!wide&&!searchExpanded){searchExpanded=true;searchInput.setVisibility(View.VISIBLE);searchBox.getLayoutParams().width=dp(200);searchBox.requestLayout();searchInput.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(searchInput,InputMethodManager.SHOW_IMPLICIT);}
            else searchMusic();
        }),new LinearLayout.LayoutParams(dp(56),dp(56)));
        searchInput.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){searchMusic();return true;}return false;});
        toolbar.addView(searchBox,new LinearLayout.LayoutParams(dp(wide?288:searchExpanded?200:56),dp(56)));
        toolbarFilter=iconButton("filter","筛选曲库",v->filterMenu(v));toolbar.addView(toolbarFilter,new LinearLayout.LayoutParams(dp(56),dp(56)));
        toolbarSort=iconButton("sort","排序方式",v->sortMenu());toolbar.addView(toolbarSort,new LinearLayout.LayoutParams(dp(56),dp(56)));
        toolbar.addView(iconButton("more","页面操作",v->pageMenu()),new LinearLayout.LayoutParams(dp(56),dp(56)));
        workspace.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(88)));
    }

    private boolean librarySection(){return "songs".equals(section)||"albums".equals(section)||"artists".equals(section)||"genres".equals(section)||"folders".equals(section)||"search".equals(section);}
    private void pageMenu() {
        if("queue".equals(section)){queueMenu();return;}
        if("downloads".equals(section)){downloadMenu();return;}
        if("playlists".equals(section)){if(parentId==null)playlistName(null,null,null);else playlistMenu(parentId,parentName);return;}
        LinkedHashMap<String,Runnable> actions=new LinkedHashMap<>();actions.put("刷新当前页面",this::refreshPage);
        if(!genre.isEmpty())actions.put("清除筛选",()->{genre="";genreName="";loading=false;load(false);});
        if(playback!=null&&playback.current()!=null)actions.put("播放队列",this::openDrawer);
        actions("页面操作",actions);
    }

    private void searchMusic() {
        String value=searchInput.getText().toString().trim();if(value.isEmpty()){toast("请输入搜索内容");return;}
        hideKeyboard();navigate("search",value);
    }

    private void refreshPage() {
        client.invalidateBrowse();browserPages.clear();playerBackList=null;
        if("player".equals(section)) { if(playback!=null&&playback.current()!=null)loadLyrics(playback.current(),true); }
        else if("home".equals(section)||"settings".equals(section))navigate(section);
        else if("queue".equals(section))showQueue();
        else if("downloads".equals(section))showDownloads();
        else {loading=false;load(false);}
    }


    private void updateNavigation() {
        for(int i=0;i<nav.getChildCount();i++)updateNavigationItem((LinearLayout)nav.getChildAt(i));
        updateNavigationItem((LinearLayout)settingsEntry);
        toolbarTitle.setText("home".equals(section)?"首页":"settings".equals(section)?"设置":sectionName());
        toolbarMeta.setText(genreName.isEmpty()?"":"筛选 · "+genreName);
        toolbarMeta.setVisibility(toolbarMeta.getText().length()==0?View.GONE:View.VISIBLE);
        toolbarBack.setVisibility(parentId!=null||!query.isEmpty()||returningToPlayer?View.VISIBLE:View.GONE);
        boolean browse=!"home".equals(section)&&!"settings".equals(section)&&!"queue".equals(section)&&!"downloads".equals(section);
        toolbarFilter.setVisibility(browse?View.VISIBLE:View.GONE);toolbarSort.setVisibility(browse&&!"genres".equals(section)?View.VISIBLE:View.GONE);
    }
    private void updateNavigationItem(LinearLayout item) {
        String target=(String)item.getTag();boolean selected=target.equals(section)||("songs".equals(target)&&librarySection());item.setSelected(selected);
        IconView icon=(IconView)item.getChildAt(0);icon.setIcon((String)icon.getTag(),selected?colors.ACCENT:colors.MUTED);
        ((TextView)item.getChildAt(1)).setTextColor(selected?colors.ACCENT:colors.MUTED);
    }

    private void navigate(String target) {
        navigate(target,"");
    }

    private void navigate(String target,String search) {
        if("player".equals(target)){showLyrics();return;}
        returningToPlayer=false;
        browseTrail.clear();
        folderTrail.clear();
        showPage(new PageState(target,null,null,null,search,"",false,"",""));
    }

    private void showPage(PageState state) {
        showPage(state,null);
    }

    private void showPage(PageState state,BrowserSnapshot restore) {
        rememberPage();
        closeDrawer();cancelLyrics();
        motion.finishTree(content);
        dragging=false;pendingPlayerExit=null;
        boolean fromPlayer=nowRoot!=null;
        if(fromPlayer) {
            if(nowLyrics!=null){lyricBookmark=nowLyrics.bookmark();lyricBookmarkTrack=nowTrack;}
            motion.finishTree(nowRoot);shellFrame.removeView(nowRoot);nowRoot=null;shellRoot.setVisibility(View.VISIBLE);
            playerPane=compactPane;playerPane.removeAllViews();nowLyrics=null;nowStatus=null;nowTimer=null;nowQuality=null;effectsIcon=null;
        }
        colors=Ui.libraryPalette(prefs);
        setFullscreen(false);
        ++requestId;loading=false;downloadsLoading=false;loaded=0;total=0;shownQueueRevision=-1;queueDrag=-1;queueDrop=-1;
        section=state.section;parentId=state.id;parentType=state.type;parentName=state.name;query=state.search;
        sort=state.sort;descending=state.descending;genre=state.genre;genreName=state.genreName;
        if(restore==null)restore=browserPages.get(browserKey(state));
        if(playerPane.getChildCount()==0)buildPlayer();
        items.clear();legacyAudioPages.clear();adapter=null;homeCacheLabels.clear();
        searchInput.setText(query);updateNavigation();content.removeAllViews();
        if(!fromPlayer&&!"home".equals(section))motion.page(content);
        if("home".equals(section)) { buildHome();return; }
        if("settings".equals(section)) { buildSettings();return; }
        buildBrowser();
        if("queue".equals(section))showQueue();
        else if("downloads".equals(section))showDownloads();
        else if(restore!=null) {
            loaded=restore.loaded;total=restore.total;items.addAll(restore.items);
            for(LegacyAudioPage page:restore.legacyAudioPages)if(!page.finished)legacyAudioPages.add(page);
            renderBrowser();list.setSelectionFromTop(restore.first,restore.top);
            retryLegacyAudioPages();
        }
        else load(false);
    }

    private PageState pageState(){return new PageState(section,parentId,parentType,parentName,query,sort,descending,genre,genreName);}
    private String browserKey(PageState state){try{return client.accountKey()+new BrowseSpec(state.section,state.id,state.type,state.search,state.sort,state.descending,state.genre).toJson().toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private void rememberPage(){
        int epoch=BrowseCache.get(this).revision(client);if(browserEpoch!=epoch){browserEpoch=epoch;browserPages.clear();return;}
        if(adapter==null||list==null||loading||"queue".equals(section)||"downloads".equals(section)||"player".equals(section))return;
        browserPages.put(browserKey(pageState()),browserSnapshot());
        while(browserPages.size()>12)browserPages.remove(browserPages.keySet().iterator().next());
    }
    private static final class PageState {
        final String section,id,type,name,search,sort,genre,genreName;final boolean descending;
        PageState(String section,String id,String type,String name,String search,String sort,boolean descending,String genre,String genreName){
            this.section=section;this.id=id;this.type=type;this.name=name;this.search=search;this.sort=sort;
            this.descending=descending;this.genre=genre;this.genreName=genreName;
        }
    }
    private static final class BrowserSnapshot {
        final List<MediaItem> items;final int loaded,total,first,top;
        final List<LegacyAudioPage> legacyAudioPages;
        BrowserSnapshot(List<MediaItem> items,int loaded,int total,int first,int top,List<LegacyAudioPage> legacyAudioPages) {
            this.items=new ArrayList<>(items);this.loaded=loaded;this.total=total;this.first=first;this.top=top;
            this.legacyAudioPages=new ArrayList<>(legacyAudioPages);
        }
    }
    private static final class LegacyAudioPage {
        final int offset;final List<MediaItem> tracks;
        long checkedAt=SystemClock.elapsedRealtime();boolean finished;
        LegacyAudioPage(int offset,List<MediaItem> tracks){this.offset=offset;this.tracks=tracks;}
    }
    private BrowserSnapshot browserSnapshot(){
        View first=list.getChildAt(0);
        return new BrowserSnapshot(items,loaded,total,list.getFirstVisiblePosition(),first==null?0:first.getTop(),legacyAudioPages);
    }
    private static final class BrowseReturn {
        final PageState state;final BrowserSnapshot snapshot;
        BrowseReturn(PageState state,BrowserSnapshot snapshot){this.state=state;this.snapshot=snapshot;}
    }
    private void rememberBrowser(){if(list!=null&&adapter!=null)browseTrail.add(new BrowseReturn(pageState(),browserSnapshot()));}

    private void buildHome() {
        if(!client.authenticated()) {
            content.addView(text("离线模式",28,colors.TEXT,true),new LinearLayout.LayoutParams(-1,dp(80)));
            content.addView(button("播放已下载歌曲",true,v->navigate("downloads")));Ui.gap(content,10);
            content.addView(button("重新登录 Emby",false,v->showLogin()));return;
        }
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(true);
        LinearLayout page=Ui.column(this);Ui.pad(page,0,8,0,24);scroll.addView(page);content.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        HomeShortcuts shortcuts=new HomeShortcuts(this,colors,(index,source)->{
            if(index==0)playRandom(source);else navigate(index==1?"favorites":index==2?"recent":"downloads");
        });
        page.addView(shortcuts,new LinearLayout.LayoutParams(-1,-2));Ui.gap(page,24);
        TextView recentHeading=addHomeHeading(page,"最近播放","查看全部",v -> navigate("recent"));
        LinearLayout recent=Ui.column(this);page.addView(recent);TextView status=text("正在载入最近播放…",16,colors.MUTED,false);recent.addView(status,new LinearLayout.LayoutParams(-1,dp(88)));
        Ui.gap(page,24);addHomeHeading(page,"你的专辑","全部专辑",v -> navigate("albums"));
        LinearLayout albums=Ui.row(this);page.addView(albums);albums.addView(text("正在载入专辑…",16,colors.MUTED,false),new LinearLayout.LayoutParams(-1,dp(100)));
        final int ticket=requestId;final EmbyClient snapshot=client;
        async(()->{
            try {
                EmbyClient.Page result=snapshot.browse("recent",null,null,"",0);boolean fallback=result.items.isEmpty();
                if(fallback)result=snapshot.browse("songs",null,null,"",0);
                final List<MediaItem> tracks=new ArrayList<>(result.items.subList(0,Math.min(wide?6:4,result.items.size())));final boolean recommended=fallback;
                final int libraryTotal=result.total;
                onUi(()->{
                    if(ticket!=requestId)return;recent.removeAllViews();
                    if(recommended)recentHeading.setText("音乐库精选");
                    if(tracks.isEmpty()){recent.addView(button("还没有歌曲 · 前往音乐库",false,v->navigate("songs")));return;}
                    int columns=wide?3:2;
                    for(int start=0;start<tracks.size();start+=columns){LinearLayout row=Ui.row(this);for(int col=0;col<columns;col++){
                        final int position=start+col;
                        View tile=position<tracks.size()?homeTrack(tracks,position,recommended?"songs":"recent",libraryTotal):new View(this);
                        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(76),1);if(col<columns-1)lp.rightMargin=dp(16);row.addView(tile,lp);
                    }recent.addView(row);}
                });
                result.observeAudioRefresh(snapshot,updated->{
                    if(updated.fillSourceAudio(tracks))onUi(()->{if(snapshot.currentSession())refreshVisibleCache();});
                });
            } catch(Exception e){onUi(()->{if(ticket==requestId){recent.removeAllViews();recent.addView(button("加载失败 · 点击重试",false,v->navigate("home")));}});}
        });
        async(()->{
            try{EmbyClient.Page result=snapshot.browse("albums",null,null,"",0);onUi(()->{
                if(ticket!=requestId)return;albums.removeAllViews();
                if(result.items.isEmpty()){albums.addView(text("专辑将显示在这里",16,colors.MUTED,false));return;}
                int count=Math.min(wide?5:4,result.items.size());for(int i=0;i<count;i++){
                    MediaItem album=result.items.get(i);LinearLayout tile=Ui.column(this);Ui.pad(tile,0,0,0,4);
                    ImageView image=new ArtView(this,12);image.setScaleType(ImageView.ScaleType.CENTER_CROP);tile.addView(image,new LinearLayout.LayoutParams(-1,dp(wide?136:110)));images.load(snapshot,album,image);
                    Ui.gap(tile,10);TextView title=text(album.name,16,colors.TEXT,true);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);tile.addView(title);Ui.gap(tile,6);
                    TextView artist=text(album.artist,13,colors.MUTED,false);artist.setSingleLine(true);artist.setEllipsize(TextUtils.TruncateAt.END);tile.addView(artist);
                    tile.setFocusable(true);tile.setBackground(Ui.background(this,colors.BG,colors.SURFACE));tile.setContentDescription("专辑 "+album.name);tile.setOnClickListener(v->{section="albums";updateNavigation();openFolder(album);});
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i<count-1)lp.rightMargin=dp(18);albums.addView(tile,lp);
                }
            });}catch(Exception e){onUi(()->{if(ticket==requestId){albums.removeAllViews();albums.addView(button("查看专辑",false,v->navigate("albums")));}});}
        });
    }

    private TextView addHomeHeading(LinearLayout page,String title,String action,View.OnClickListener click) {
        LinearLayout row=Ui.row(this);TextView label=text(title,23,colors.TEXT,true);row.addView(label,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView link=button(action,false,click);link.setTextSize(14);link.setTextColor(colors.MUTED);link.setBackground(Ui.background(this,colors.BG,colors.SURFACE));row.addView(link,new LinearLayout.LayoutParams(-2,dp(48)));page.addView(row);Ui.gap(page,8);return label;
    }

    private View homeTrack(List<MediaItem> tracks,int position,String scope,int libraryTotal) {
        MediaItem item=tracks.get(position);LinearLayout tile=Ui.row(this);Ui.pad(tile,8,8,8,8);tile.setBackground(Ui.background(this,colors.BG,colors.SURFACE));
        ImageView image=new ArtView(this,12);image.setScaleType(ImageView.ScaleType.CENTER_CROP);tile.addView(image,new LinearLayout.LayoutParams(dp(52),dp(52)));images.load(client,item,image);
        LinearLayout labels=Ui.column(this);Ui.pad(labels,12,0,0,0);TextView title=text(item.name,17,colors.TEXT,true);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);labels.addView(title);Ui.gap(labels,7);
        TextView artist=text(item.artist,14,colors.MUTED,false);artist.setSingleLine(true);artist.setEllipsize(TextUtils.TruncateAt.END);labels.addView(artist);tile.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        CacheLabel label=new CacheLabel(item,artist,item.artist);homeCacheLabels.add(label);refreshCacheLabel(label);
        tile.setFocusable(true);tile.setContentDescription("播放 "+item.name);tile.setOnClickListener(v->{
            if(playback!=null) {
                BrowseSpec continuation=new BrowseSpec(scope,null,null,"","",false,"");
                playback.playQueue(new ArrayList<>(tracks),position,tracks.size()<libraryTotal?
                        new QueueSource(continuation,tracks.size(),libraryTotal):null);
                showLyrics();
            }
        });return tile;
    }

    private void playRandom(View card) {
        if(playback==null){toast("播放器正在初始化，请稍候");return;}
        card.setEnabled(false);card.setAlpha(0.5f);toast("正在挑选音乐…");final int ticket=requestId;final EmbyClient snapshot=client;
        async(()->{try{
            EmbyClient.Page count=snapshot.browse(BrowseSpec.songs(),0,1);
            QueueSource continuation=QueueSource.random(count.total,new java.util.Random());
            List<MediaItem> tracks=new ArrayList<>();
            while(tracks.isEmpty()&&continuation.more()) {
                EmbyClient.Page page=snapshot.browse(continuation.spec,continuation.offset(),EmbyClient.PAGE_SIZE);
                tracks.addAll(page.items);continuation.advance(page);
            }
            Collections.shuffle(tracks);
            onUi(()->{
                card.setEnabled(true);card.setAlpha(1);if(ticket!=requestId||playback==null||!snapshot.currentSession())return;
                if(tracks.isEmpty()){toast("音乐库还没有歌曲");return;}
                playback.playQueue(tracks,0,continuation);showLyrics();
            });
        }catch(Exception e){onUi(()->{card.setEnabled(true);card.setAlpha(1);if(ticket==requestId&&snapshot.currentSession())toast(EmbyClient.message(e));});}});
    }

    private String sectionName() {
        if(parentName!=null) return parentName;
        if(!query.isEmpty()) return "搜索结果";
        if("favorites".equals(section)) return "我喜欢";
        if("recent".equals(section)) return "最近播放";
        if("queue".equals(section)) return "播放队列";
        if("downloads".equals(section)) return "离线歌曲";
        if("genres".equals(section)) return "曲风";
        if("folders".equals(section)) return "文件夹";
        if("albums".equals(section)) return "专辑";
        if("artists".equals(section)) return "歌手";
        if("playlists".equals(section)) return "歌单";
        return "曲库";
    }


    private interface TabAction { void choose(String value); }
    private View tabs(String[][] entries,String selected,TabAction action) {
        android.widget.HorizontalScrollView scroll=new android.widget.HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);scroll.setFillViewport(false);
        LinearLayout row=Ui.row(this);
        for(String[] entry:entries){TextView tab=button(entry[0],false,v->action.choose(entry[1]));tab.setTextColor(selected.equals(entry[1])?colors.ACCENT:colors.MUTED);tab.setSelected(selected.equals(entry[1]));tab.setBackground(Ui.background(this,android.graphics.Color.TRANSPARENT,colors.SOFT));tab.setContentDescription(entry[0]+(selected.equals(entry[1])?"，已选择":""));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(56));lp.rightMargin=dp(8);row.addView(tab,lp);}
        scroll.addView(row);return scroll;
    }

    private void buildBrowser() {
        heading=toolbarTitle;subheading=toolbarMeta;gridColumns=parentId==null&&("albums".equals(section)||"artists".equals(section)||"playlists".equals(section))?(wide?5:screenWidth>=900?3:2):0;
        LinearLayout tools=Ui.row(this);
        playList=button("播放歌曲",true,v->playItems(firstAudio()));playList.setVisibility(View.GONE);
        if(parentId==null&&librarySection()){
            String[][] entries=query.isEmpty()?new String[][]{{"歌曲","songs"},{"专辑","albums"},{"歌手","artists"},{"曲风","genres"},{"文件夹","folders"}}:new String[][]{{"全部","search"},{"歌曲","songs"},{"专辑","albums"},{"歌手","artists"}};
            View categories=tabs(entries,section,value->navigate(value,query));categories.setId(R.id.library_tabs);tools.addView(categories,new LinearLayout.LayoutParams(0,dp(56),1));
        } else if("downloads".equals(section)) {
            tools.addView(tabs(new String[][]{{"已下载","saved"},{"下载中","pending"}},downloadTab,value->{downloadTab=value;downloadsLoading=false;showPage(pageState());}),new LinearLayout.LayoutParams(0,dp(56),1));
        } else {
            TextView context=text("queue".equals(section)?"拖动调整顺序 · 更多可编辑":"playlists".equals(section)?parentId==null?"收藏你的音乐旅程":"歌单歌曲":"选择音乐，开始聆听",18,colors.MUTED,false);context.setSingleLine(true);context.setEllipsize(TextUtils.TruncateAt.END);tools.addView(context,new LinearLayout.LayoutParams(0,dp(56),1));
        }
        tools.addView(playList,new LinearLayout.LayoutParams(-2,dp(56)));
        if("playlists".equals(section)&&parentId==null){View create=button("新建歌单",true,v->playlistName(null,null,null));tools.addView(create,new LinearLayout.LayoutParams(-2,dp(56)));}
        else if("Playlist".equals(parentType)){tools.addView(iconButton("shuffle","随机播放此歌单",v->{playItems(firstAudio());if(playback!=null&&!playback.shuffle())playback.toggleShuffle();}),new LinearLayout.LayoutParams(dp(56),dp(56)));}
        content.addView(tools,new LinearLayout.LayoutParams(-1,dp(56)));Ui.gap(content,8);
        if(gridColumns==0){
            LinearLayout columns=Ui.row(this);TextView number=text("#",16,colors.FAINT,false);number.setGravity(Gravity.CENTER);columns.addView(number,new LinearLayout.LayoutParams(dp("queue".equals(section)?56:32),-2));columns.addView(new View(this),new LinearLayout.LayoutParams(dp(68),1));
            TextView song=text("歌曲 / 歌手",16,colors.FAINT,false);columns.addView(song,new LinearLayout.LayoutParams(0,dp(32),1));
            if(wide){TextView album=text("专辑",16,colors.FAINT,false);columns.addView(album,new LinearLayout.LayoutParams(dp(192),-2));}
            TextView time=text("时长",16,colors.FAINT,false);time.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);columns.addView(time,new LinearLayout.LayoutParams(dp(72),-2));columns.addView(new View(this),new LinearLayout.LayoutParams(dp(56),1));content.addView(columns);
        }
        FrameLayout frame=new FrameLayout(this);list=new ListView(this);list.setId(R.id.library_list);list.setDivider(null);list.setCacheColorHint(colors.BG);list.setSelector(Ui.background(this,colors.BG,colors.SOFT));list.setClipToPadding(false);list.setVerticalScrollBarEnabled(false);list.setPadding(0,0,0,dp(8));
        more=button("加载更多",false,v->load(true));list.addFooterView(more,null,false);adapter=new LibraryAdapter();list.setAdapter(adapter);
        list.setOnItemClickListener((parent,view,position,id)->{
            if(gridColumns>0||position>=items.size())return;MediaItem item=items.get(position);
            if(item.isAudio()){if("queue".equals(section)){if(playback!=null)playback.select(position);}else if("downloads".equals(section)&&!audioCache.saved(client,item))trackMenu(item,position,null);else playItems(position);}else openFolder(item);
        });
        list.setOnItemLongClickListener((parent,view,position,id)->{if(gridColumns>0||position>=items.size())return false;MediaItem item=items.get(position);if(item.isAudio())trackMenu(item,position,null);else if("Playlist".equals(item.type))playlistMenu(item.id,item.name);else return false;return true;});
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener(){
            @Override public void onScrollStateChanged(android.widget.AbsListView view,int state){}
            @Override public void onScroll(android.widget.AbsListView view,int first,int visible,int count){
                if(view==list&&adapter!=null&&visible>0&&first+visible>=adapter.getCount()-3&&loaded<total&&more.isEnabled()&&(view.isInTouchMode()||first>0))
                    handler.post(()->{if(view==list&&adapter!=null&&loaded<total&&more.isEnabled())load(true);});
            }
        });
        frame.addView(list,new FrameLayout.LayoutParams(-1,-1));empty=Ui.column(this);empty.setGravity(Gravity.CENTER);Ui.pad(empty,24,24,24,24);empty.setBackgroundColor(colors.BG);
        spinner=new ProgressBar(this);empty.addView(spinner,new LinearLayout.LayoutParams(dp(36),dp(36)));Ui.gap(empty,16);emptyText=text("正在加载音乐库…",20,colors.MUTED,false);emptyText.setGravity(Gravity.CENTER);emptyText.setLineSpacing(dp(5),1);empty.addView(emptyText);Ui.gap(empty,16);
        empty.addView(button("queue".equals(section)||"downloads".equals(section)?"前往曲库":"重新加载",false,v->{if("queue".equals(section)||"downloads".equals(section))navigate("songs");else load(false);}));frame.addView(empty,new FrameLayout.LayoutParams(-1,-1));content.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
    }

    private void openFolder(MediaItem item) {
        rememberBrowser();
        if("MusicGenre".equals(item.type)){showPage(new PageState("songs",null,null,null,"","",false,item.id,item.name));return;}
        String target="MusicAlbum".equals(item.type)?"albums":"MusicArtist".equals(item.type)?"artists":
                "Playlist".equals(item.type)?"playlists":"folders";
        if("folders".equals(target)&&"folders".equals(section))folderTrail.add(pageState());
        showPage(new PageState(target,item.id,item.type,item.name,"","",false,"",""));
    }

    private BrowseSpec browseSpec(){return new BrowseSpec(section,parentId,parentType,query,sort,descending,genre);}

    private void load(boolean append) {
        if(loading||!shell||adapter==null||"queue".equals(section)||"downloads".equals(section)||"player".equals(section))return;
        if(!client.authenticated()) {
            empty.setVisibility(View.VISIBLE);spinner.setVisibility(View.GONE);emptyText.setText("请重新登录以浏览曲库");
            return;
        }
        final int ticket=++requestId;loading=true;
        if(!append) {loaded=0;total=0;items.clear();legacyAudioPages.clear();adapter.notifyDataSetChanged();empty.setVisibility(View.VISIBLE);spinner.setVisibility(View.VISIBLE);emptyText.setText("正在加载音乐库…");}
        more.setEnabled(false);more.setText("正在加载…");
        final EmbyClient snapshot=client;
        final BrowseSpec spec=browseSpec();
        final int offset=append?loaded:0;
        async(() -> {
            try {
                EmbyClient.Page page="genres".equals(spec.section)?snapshot.genres(offset):snapshot.browse(spec,offset,EmbyClient.PAGE_SIZE);
                LegacyAudioPage legacy=page.legacyAudio()?new LegacyAudioPage(offset,page.items):null;
                onUi(() -> {
                    if(ticket!=requestId||!snapshot.currentSession())return;
                    loaded=offset+page.received;total=page.received==0?loaded:Math.max(page.total,loaded);
                    if(legacy!=null)legacyAudioPages.add(legacy);
                    items.addAll(page.items);loading=false;renderBrowser();
                });
                if(legacy!=null)observeLegacyAudio(snapshot,legacy,page);
            } catch(Exception e) {
                onUi(() -> {
                    if(ticket!=requestId||!snapshot.currentSession())return;
                    loading=false;spinner.setVisibility(View.GONE);more.setEnabled(true);more.setText("加载失败，点击重试");
                    if(items.isEmpty()) { empty.setVisibility(View.VISIBLE);emptyText.setText(EmbyClient.message(e)); }
                    // The footer keeps a stable retry action for a failed next page.
                });
            }
        });
    }

    private void observeLegacyAudio(EmbyClient snapshot,LegacyAudioPage legacy,EmbyClient.Page page){
        if(page.legacyAudio())page.observeAudioRefresh(snapshot,updated->applyRefreshedAudio(snapshot,legacy,updated));
        else applyRefreshedAudio(snapshot,legacy,page);
    }

    private void applyRefreshedAudio(EmbyClient snapshot,LegacyAudioPage legacy,EmbyClient.Page page){
        // The same entries also back retained snapshots. Update their facts without rebinding the list.
        boolean changed=page.fillSourceAudio(legacy.tracks);
        onUi(()->{
            legacy.finished=true;legacyAudioPages.remove(legacy);
            if(changed&&snapshot.currentSession())refreshVisibleCache();
        });
    }

    private void retryLegacyAudioPages(){
        if(adapter==null||client==null||!client.authenticated()||legacyAudioPages.isEmpty())return;
        EmbyClient snapshot=client;BrowseSpec spec=browseSpec();long now=SystemClock.elapsedRealtime();
        for(LegacyAudioPage legacy:legacyAudioPages)if(!legacy.finished&&now-legacy.checkedAt>=60000){
            legacy.checkedAt=now;
            async(()->{
                try{observeLegacyAudio(snapshot,legacy,snapshot.browse(spec,legacy.offset,EmbyClient.PAGE_SIZE));}
                catch(Exception ignored){/* Keep the old rows visible if the network is still unavailable. */}
            });
        }
    }

    private void renderBrowser() {
        adapter.notifyDataSetChanged();empty.setVisibility(items.isEmpty()?View.VISIBLE:View.GONE);spinner.setVisibility(View.GONE);
        emptyText.setText(query.isEmpty()?"这里还没有音乐\n可刷新曲库，或换个分类看看":"没有找到匹配内容\n试试其他关键词");
        more.setEnabled(true);more.setText("加载更多（"+loaded+" / "+total+"）");
        more.setVisibility(loaded<total?View.VISIBLE:View.GONE);
        subheading.setText(total+" 项"+(!query.isEmpty()?" · “"+query+"”":"")+(genreName.isEmpty()?"":" · "+genreName)+" · "+sortDescription());subheading.setVisibility(View.VISIBLE);
        playList.setVisibility(firstAudio()>=0?View.VISIBLE:View.GONE);playList.setText("播放歌曲");
    }

    private String sortDescription(){if(sort.isEmpty())return "默认顺序";return "SortName".equals(sort)?descending?"名称降序":"名称升序":"DateCreated".equals(sort)?descending?"最近添加":"最早添加":descending?"时长降序":"时长升序";}

    private void showQueue() {
        if(adapter==null || !"queue".equals(section) || !shell)return;
        if(playback!=null&&shownQueueRevision==playback.queueRevision())return;
        if(queueDrag>=0)return;
        shownQueueRevision=playback==null?-1:playback.queueRevision();
        items.clear();if(playback!=null)items.addAll(playback.queue());
        adapter.notifyDataSetChanged();spinner.setVisibility(View.GONE);more.setVisibility(View.GONE);
        empty.setVisibility(items.isEmpty()?View.VISIBLE:View.GONE);emptyText.setText("队列还是空的\n去音乐库选择一首歌曲吧");
        subheading.setVisibility(View.VISIBLE);subheading.setText(items.size()+" 首歌曲"+(playback!=null&&playback.hasMore()?" · 到队尾自动补充":" · 长按或拖动可编辑"));
        playList.setVisibility(items.isEmpty()?View.GONE:View.VISIBLE);playList.setText("从头播放");
    }

    private int firstAudio(){for(int i=0;i<items.size();i++)if(items.get(i).isAudio())return i;return -1;}

    private void playItems(int selected) {
        if(playback==null){toast("播放器正在初始化，请稍候");return;}
        if(items.isEmpty()||selected<0||selected>=items.size())return;
        if(!items.get(selected).isAudio())selected=firstAudio();
        if(selected<0)return;
        if("queue".equals(section))playback.select(selected);
        else if("downloads".equals(section)) {
            List<MediaItem> ready=new ArrayList<>();int start=-1;
            for(int i=0;i<items.size();i++)if(audioCache.saved(client,items.get(i))) {
                if(i==selected)start=ready.size();ready.add(items.get(i));
            }
            if(start<0&&selected==0&&!ready.isEmpty())start=0;
            if(start>=0){playback.playQueue(ready,start);showLyrics();}
        } else {
            playback.playQueue(new ArrayList<>(items),selected,loaded<total?new QueueSource(browseSpec(),loaded,total):null);
            showLyrics();
        }
        updatePlayer();
    }

    private void actions(String title,LinkedHashMap<String,Runnable> choices) {
        if(choices.isEmpty())return;
        List<Runnable> calls=new ArrayList<>(choices.values());
        final List<String> labels=new ArrayList<>(choices.keySet());
        android.widget.ArrayAdapter<String> rows=new android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){@Override public View getView(int p,View old,ViewGroup parent){TextView view=(TextView)super.getView(p,old,parent);view.setTextSize(20);view.setTextColor(colors.TEXT);view.setMinHeight(dp(56));Ui.pad(view,24,12,24,12);return view;}};
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle(title).setAdapter(rows,(d,i)->calls.get(i).run()).setNegativeButton("关闭",null));
    }

    private void filterMenu(View anchor) {
        LinkedHashMap<String,Runnable> choices=new LinkedHashMap<>();
        if("songs".equals(section)||"favorites".equals(section)||"recent".equals(section)||"search".equals(section))choices.put("按曲风筛选",()->chooseGenre(0));
        if(!genre.isEmpty())choices.put("清除曲风筛选",()->{genre="";genreName="";loading=false;load(false);});
        choices.put("按音乐文件夹浏览",()->navigate("folders"));actions("曲库筛选",choices);
    }

    private void sortMenu() {
        String[] labels={"默认顺序","名称 A → Z","名称 Z → A","最近添加","最早添加","时长由短到长","时长由长到短"};
        String[] values={"","SortName","SortName","DateCreated","DateCreated","Runtime","Runtime"};
        boolean[] reverse={false,false,true,true,false,false,true};
        int checked=0;
        for(int i=0;i<values.length;i++)if(sort.equals(values[i])&&descending==reverse[i])checked=i;
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("曲库排序").setSingleChoiceItems(labels,checked,(d,which)->{
            d.dismiss();sort=values[which];descending=reverse[which];loading=false;load(false);
        }).setNegativeButton("取消",null));
    }

    private void chooseGenre(int start) {
        final EmbyClient snapshot=client;final int ticket=requestId;
        async(()->{
            try {
                EmbyClient.Page page=snapshot.genres(start);
                onUi(()->{
                    if(ticket!=requestId||!snapshot.currentSession())return;
                    LinkedHashMap<String,Runnable> choices=new LinkedHashMap<>();
                    choices.put("全部流派",()->{genre="";genreName="";loading=false;load(false);});
                    for(MediaItem item:page.items)choices.put(item.name,()->{genre=item.id;genreName=item.name;loading=false;load(false);});
                    if(page.received>0&&start+page.received<page.total)choices.put("更多流派…",()->chooseGenre(start+page.received));
                    actions("选择流派",choices);
                });
            } catch(Exception e){showError(snapshot,e);}
        });
    }

    private void showError(EmbyClient snapshot,Exception e) {
        onUi(()->{if(snapshot.currentSession())toast(EmbyClient.message(e));});
    }

    private void queueMenu() {
        if(playback==null)return;
        LinkedHashMap<String,Runnable> choices=new LinkedHashMap<>();
        choices.put("回到全屏播放",this::showLyrics);
        choices.put("定时停止",this::sleepMenu);
        if(playback.queueSize()>0)choices.put("清空队列",()->dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("清空播放队列？")
                .setMessage("播放会停止。").setNegativeButton("取消",null).setPositiveButton("清空",(d,i)->playback.stopPlayback(true))));
        actions("播放队列",choices);
    }


    private void showDownloads() {
        if(!"downloads".equals(section)||adapter==null||downloadsLoading)return;downloadsLoading=true;EmbyClient snapshot=client;int ticket=requestId;
        shownDownloadRevision=audioCache.downloadRevision();
        async(()->{List<MediaItem> saved=audioCache.savedItems(snapshot);List<MediaItem> pending=audioCache.pending(snapshot);long[] usage=audioCache.usage(snapshot);
            onUi(()->{if(ticket!=requestId||!snapshot.currentSession()||!"downloads".equals(section))return;downloadsLoading=false;items.clear();
                if("saved".equals(downloadTab))items.addAll(saved);else for(MediaItem item:pending)if(!audioCache.saved(snapshot,item))items.add(item);
                adapter.notifyDataSetChanged();spinner.setVisibility(View.GONE);more.setVisibility(View.GONE);empty.setVisibility(items.isEmpty()?View.VISIBLE:View.GONE);emptyText.setText("saved".equals(downloadTab)?"还没有离线歌曲\n在歌曲菜单中选择「下载到离线」":"没有正在下载的歌曲");
                subheading.setText(saved.size()+" 首已下载 · "+bytes(usage[0])+" / "+bytes(audioCache.limit(true))+" · 自动缓存 "+bytes(usage[1])+" / "+bytes(audioCache.limit(false)));subheading.setVisibility(View.VISIBLE);
                playList.setVisibility("saved".equals(downloadTab)&&!saved.isEmpty()?View.VISIBLE:View.GONE);playList.setText("播放已下载");
                if(shownDownloadRevision!=audioCache.downloadRevision())showDownloads();
            });
        });
    }
    private String bytes(long n){return n>=1073741824L?String.format(java.util.Locale.getDefault(),"%.1f GB",n/1073741824.0):String.format(java.util.Locale.getDefault(),"%.1f MB",n/1048576.0);}

    private void downloadMenu() {
        LinkedHashMap<String,Runnable> choices=new LinkedHashMap<>();
        choices.put("自动缓存上限 · "+bytes(audioCache.limit(false)),()->cacheSize(false));
        choices.put("离线下载上限 · "+bytes(audioCache.limit(true)),()->cacheSize(true));
        choices.put("清除自动音频缓存",()->dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("清除自动音频缓存？")
                .setMessage("手动下载的歌曲会保留。").setNegativeButton("取消",null)
                .setPositiveButton("清除",(d,i)->{CachePreloader.get(this).suspend(true);audioCache.clearAutomatic(client);toast("正在清除缓存");})));
        if(!client.authenticated())choices.put("重新登录",this::showLogin);
        actions("下载管理",choices);
    }

    private void trackMenu(MediaItem item,int row,View anchor) {
        LinkedHashMap<String,Runnable> choices=new LinkedHashMap<>();
        boolean queuePage="queue".equals(section)&&row>=0,downloadPage="downloads".equals(section);
        int revision=playback==null?-1:playback.queueRevision();
        if(queuePage) {
            if(row>0)choices.put("上移",()->moveQueue(row,row-1,revision));
            if(row+1<items.size())choices.put("下移",()->moveQueue(row,row+1,revision));
            choices.put("从队列移除",()->{if(validQueue(revision))playback.remove(row);});
        } else if(playback!=null) {
            choices.put("下一首播放",()->{playback.enqueue(item,true);toast("已设为下一首");});
            choices.put("加入播放队列",()->{playback.enqueue(item,false);toast("已加入播放队列");});
        }
        if(audioCache.saved(client,item)) {
            if(downloadPage)choices.put("移除离线文件",()->audioCache.remove(client,item));
        } else if(audioCache.progress(client,item)>=0)choices.put("取消下载",()->audioCache.cancel(client,item));
        else if(client.authenticated())choices.put("下载到离线",()->{
            audioCache.download(client,item,prefs.audioQuality(),error->{if(!destroyed)toast(error.isEmpty()?"已保存到离线歌曲":error);});
            toast("已加入下载");
        });
        if(!audioCache.failure(client,item).isEmpty()){
            choices.put("重试下载",()->audioCache.download(client,item,prefs.audioQuality(),error->{if(!destroyed)toast(error.isEmpty()?"已保存到离线歌曲":error);}));
            choices.put("移除失败任务",()->audioCache.cancel(client,item));
        }
        if(client.authenticated()) {
            choices.put(item.favorite?"取消收藏":"添加收藏",()->favorite(item));
            choices.put("添加到歌单",()->choosePlaylist(item,0));
            if("Playlist".equals(parentType)&&!item.playlistEntryId.isEmpty()) {
                String playlist=parentId;
                choices.put("从此歌单移除",()->playlistWrite(api->api.removeFromPlaylist(playlist,item.playlistEntryId),"已从歌单移除",true));
            }
        }
        if("player".equals(section)){
            choices.put("播放音质",this::qualityMenu);
            choices.put("定时停止",this::sleepMenu);
            View mode=playerPane.findViewById(R.id.player_mode);
            if(mode!=null&&mode.getVisibility()==View.GONE)choices.put("播放模式",this::playbackMode);
        }
        choices.put("歌曲信息",()->songInfo(item));
        actions(item.name,choices);
    }

    private boolean validQueue(int revision) {
        if(playback!=null&&playback.queueRevision()==revision)return true;
        toast("队列已变化，请重试");return false;
    }

    private void moveQueue(int from,int to,int revision){if(validQueue(revision))playback.move(from,to);}

    private void playlistMenu(String id,String name) {
        LinkedHashMap<String,Runnable> choices=new LinkedHashMap<>();
        choices.put("重命名",()->playlistName(id,name,null));
        choices.put("删除歌单",()->dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("删除歌单？").setMessage(name+"\n歌曲文件会保留。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(d,i)->{
                    playlistWrite(api->api.deletePlaylist(id),"已删除歌单",true);
                })));
        actions(name,choices);
    }

    private void playlistName(String id,String old,MediaItem track) {
        EditText input=field("歌单名称",InputType.TYPE_CLASS_TEXT,View.NO_ID);
        if(old!=null)input.setText(old);
        LinearLayout form=Ui.column(this);Ui.pad(form,20,8,20,8);form.addView(input);
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle(id==null?"新建歌单":"重命名歌单").setView(form)
                .setNegativeButton("取消",null).setPositiveButton("保存",(d,i)->{
                    String name=input.getText().toString().trim();
                    if(name.isEmpty()||name.length()>120){toast("歌单名需为 1–120 个字符");return;}
                    playlistWrite(api->{if(id==null)api.createPlaylist(name,track==null?null:track.id);else api.renamePlaylist(id,name);},
                            id==null?"歌单已创建":"歌单已重命名",true);
                }));
    }

    private void choosePlaylist(MediaItem track,int start) {
        EmbyClient snapshot=client;
        async(()->{
            try {
                EmbyClient.Page page=snapshot.browse("playlists",null,null,"",start);
                onUi(()->{
                    if(!snapshot.currentSession())return;
                    LinkedHashMap<String,Runnable> choices=new LinkedHashMap<>();
                    choices.put("新建歌单并添加歌曲",()->playlistName(null,null,track));
                    for(MediaItem list:page.items) {
                        String label=list.name;for(int n=2;choices.containsKey(label);n++)label=list.name+" ("+n+")";
                        choices.put(label,()->playlistWrite(api->api.addToPlaylist(list.id,track.id),"已加入 "+list.name,true));
                    }
                    if(page.received>0&&start+page.received<page.total)choices.put("更多歌单…",()->choosePlaylist(track,start+page.received));
                    actions("添加到歌单",choices);
                });
            } catch(Exception e){showError(snapshot,e);}
        });
    }

    private interface PlaylistAction{void run(EmbyClient api)throws Exception;}

    private void playlistWrite(PlaylistAction action,String success,boolean refresh) {
        if(playlistBusy){toast("正在保存歌单，请稍候");return;}playlistBusy=true;
        EmbyClient snapshot=client;
        async(()->{
            try {
                action.run(snapshot);
                onUi(()->{
                    playlistBusy=false;if(!snapshot.currentSession())return;
                    toast(success);
                    if(refresh&&"playlists".equals(section)) {
                        if(!success.equals("已从歌单移除")&&!success.startsWith("已加入"))navigate("playlists");
                        else {loading=false;load(false);}
                    }
                });
            } catch(Exception e){onUi(()->{playlistBusy=false;if(snapshot.currentSession())toast(EmbyClient.message(e));});}
        });
    }

    private void songInfo(MediaItem item) {
        EmbyClient snapshot=client;
        if(!snapshot.authenticated()){showSongInfo(item,null);return;}
        async(()->{
            try {
                JSONObject detail=snapshot.lyricDetails(item);
                onUi(()->{if(snapshot.currentSession())showSongInfo(item,detail);});
            } catch(Exception e){
                onUi(()->{if(snapshot.currentSession())showSongInfo(item,null);});
            }
        });
    }

    private void showSongInfo(MediaItem item,JSONObject detail) {
        ScrollView scroll=new ScrollView(this);LinearLayout rows=Ui.column(this);Ui.pad(rows,24,12,24,16);scroll.addView(rows);
        TextView meta=text(item.artist+(item.album.isEmpty()?"":" · "+item.album)+"\n"+MediaItem.time(item.durationMs),
                16,colors.TEXT,false);meta.setLineSpacing(dp(6),1);rows.addView(meta);
        SourceAudio sourceAudio=detail==null?item.sourceAudio:SourceAudio.read(detail);
        if(sourceAudio==SourceAudio.EMPTY)sourceAudio=item.sourceAudio;
        infoLine(rows,"原文件音质",sourceAudio.badge);
        infoLine(rows,"容器",sourceAudio.container);
        infoLine(rows,"编码",sourceAudio.codec.toUpperCase(java.util.Locale.ROOT));
        if(sourceAudio.rate>0)infoLine(rows,"采样率",sourceAudio.rate+" Hz");
        if(sourceAudio.bits>0)infoLine(rows,"位深",sourceAudio.bits+" bit");
        if(sourceAudio.channels>0)infoLine(rows,"声道",String.valueOf(sourceAudio.channels));
        infoLine(rows,"码率",sourceAudio.bitrateLabel());
        if(playback!=null&&playback.current()!=null&&playback.current().id.equals(item.id)&&playback.activeQuality()!=null)
            infoLine(rows,"当前播放档位",(playback.isOfflinePlayback()?"本地 · ":"")+playback.activeQuality().format());
        if(detail!=null) {
            JSONArray sources=detail.optJSONArray("MediaSources");
            JSONObject source=sources==null?null:sources.optJSONObject(0);
            if(source!=null) {
                long size=source.optLong("Size");if(size>0)infoLine(rows,"文件大小",String.format(java.util.Locale.getDefault(),"%.1f MB",size/1048576.0));
            }
            if(detail.optInt("ProductionYear")>0)infoLine(rows,"发行年",detail.optString("ProductionYear"));
            JSONArray genres=detail.optJSONArray("Genres");
            if(genres!=null&&genres.length()>0) {
                List<String> names=new ArrayList<>();
                for(int i=0;i<genres.length();i++)names.add(genres.optString(i));
                infoLine(rows,"流派",TextUtils.join(" / ",names));
            }
        }
        if(client.authenticated()) {
            String album=detail==null?item.albumId:detail.optString("AlbumId",item.albumId);
            if(!album.isEmpty()) {
                Ui.gap(rows,12);rows.addView(button("查看专辑  ›",false,v->{activeDialog.dismiss();openLinked("albums",album,"MusicAlbum",item.album);}));
            }
            JSONArray artists=detail==null?null:detail.optJSONArray("ArtistItems");
            if(artists==null)artists=item.artistItems;
            for(int i=0;i<artists.length();i++) {
                JSONObject artist=artists.optJSONObject(i);if(artist==null)continue;
                String id=artist.optString("Id"),name=artist.optString("Name");
                if(id.isEmpty())continue;
                Ui.gap(rows,8);rows.addView(button("歌手  ·  "+name+"  ›",false,v->{
                    activeDialog.dismiss();openLinked("artists",id,"MusicArtist",name);
                }));
            }
            if(artists.length()==0&&!item.artist.isEmpty()&&!"未知艺术家".equals(item.artist)) {
                Ui.gap(rows,8);rows.addView(button("查找歌手  ›",false,v->{
                    activeDialog.dismiss();navigate("artists",item.artist.split(" / ")[0]);
                }));
            }
        }
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle(item.name).setView(scroll).setPositiveButton("关闭",null));
    }

    private void infoLine(LinearLayout rows,String name,String value) {
        if(value==null||value.isEmpty())return;
        TextView line=text(name+"  ·  "+value,15,colors.MUTED,false);Ui.pad(line,0,9,0,2);rows.addView(line);
    }

    private void openLinked(String target,String id,String type,String name) {
        PageState destination=new PageState(target,id,type,name,"","",false,"","");
        if("player".equals(section))leavePlayer(()->{returningToPlayer=true;showPage(destination);});
        else {rememberBrowser();returningToPlayer=false;showPage(destination);}
    }

    private void sleepMenu() {
        if(playback==null)return;
        LinearLayout box=Ui.column(this);Ui.pad(box,20,8,20,16);
        long remaining=playback.sleepRemaining();
        box.addView(text(playback.sleepAfterTrack()?"当前歌曲播完后停止":remaining>0?"剩余 "+MediaItem.time(remaining):"播放会自动暂停，可继续播放。",16,colors.TEXT,false));
        Ui.gap(box,12);
        CheckBox fade=new CheckBox(this);fade.setText("最后 8 秒逐渐降低音量");fade.setChecked(prefs.bool("sleep_fade",true));box.addView(fade);
        LinearLayout presets=Ui.row(this);box.addView(presets);
        for(int minutes:new int[]{15,30,60}) {
            TextView timer=button(minutes+" 分钟",false,v->{activeDialog.dismiss();setSleep(minutes,fade.isChecked());});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(52),1);lp.rightMargin=dp(5);presets.addView(timer,lp);
        }
        EditText custom=field("自定分钟数（1–720）",InputType.TYPE_CLASS_NUMBER,View.NO_ID);
        Ui.gap(box,12);box.addView(custom,new LinearLayout.LayoutParams(-1,dp(48)));
        Ui.gap(box,10);box.addView(button("播完当前歌曲",false,v->{
            if(playback.current()==null){toast("还没有正在播放的歌曲");return;}
            activeDialog.dismiss();prefs.setBool("sleep_fade",fade.isChecked());playback.sleep(0,true,fade.isChecked());toast("本曲结束后停止");
        }));
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("定时停止").setView(box).setNeutralButton("关闭定时",(d,i)->playback.sleep(0,false,false))
                .setNegativeButton("取消",null).setPositiveButton("开始计时",(d,i)->{
                    try {
                        int minutes=Integer.parseInt(custom.getText().toString().trim());
                        if(minutes<1||minutes>720)throw new NumberFormatException();
                        setSleep(minutes,fade.isChecked());
                    } catch(NumberFormatException e){toast("请输入 1–720 分钟");}
                }));
    }

    private void setSleep(int minutes,boolean fade) {
        prefs.setBool("sleep_fade",fade);playback.sleep(minutes*60000L,false,fade);toast(minutes+" 分钟后停止播放");
    }


    private void buildPlayer() {
        playerPane.removeAllViews();boolean full="player".equals(section);miniProgress=null;seek=null;downloadButton=null;downloadIcon=null;
        playerPane.setBackground(Ui.surface(this,24));Ui.pad(playerPane,0,0,0,full?PlaybackControls.BOTTOM_DP:0);shuffleIcon=null;repeatIcon=null;
        playerTitle=text("好音乐，在路上",22,colors.TEXT,true);playerArtist=text("选择歌曲，开始聆听",18,colors.MUTED,false);playerState=text("",16,colors.MUTED,false);
        playerTitle.setSingleLine(true);playerTitle.setEllipsize(TextUtils.TruncateAt.END);playerArtist.setSingleLine(true);playerArtist.setEllipsize(TextUtils.TruncateAt.END);playerState.setSingleLine(true);playerState.setEllipsize(TextUtils.TruncateAt.END);
        repeatLabel=text("",16,colors.MUTED,false);queueLabel=text("",16,colors.MUTED,false);cover=new ArtView(this,12);
        favoriteIcon=new IconView(this,"heart",colors.MUTED);
        View favorite=smallControl(favoriteIcon,"收藏当前歌曲",v->{if(playback!=null&&playback.current()!=null)favorite(playback.current());});
        View previous=iconButton("previous","上一首",v->{if(playback!=null)playback.previous();});previous.setId(R.id.player_previous);
        View next=iconButton("next","下一首",v->{if(playback!=null)playback.next(false);});next.setId(R.id.player_next);
        LinearLayout toggle=Ui.row(this);toggle.setGravity(Gravity.CENTER);
        playIcon=new IconView(this,"play",colors.ACCENT);toggle.addView(playIcon,new LinearLayout.LayoutParams(dp(32),dp(32)));toggle.setFocusable(true);toggle.setClickable(true);
        toggle.setOnClickListener(v->{if(playback!=null){if(playback.current()==null)navigate("songs");else playback.toggle();}});toggle.setContentDescription("播放");toggle.setId(R.id.player_toggle);playButton=toggle;
        Ui.press(toggle);
        queueButton=iconButton("queue","播放队列",v->openDrawer());queueButton.setId(R.id.queue_open);
        if(full) {
            LinearLayout timeline=Ui.row(this);Ui.pad(timeline,16,0,16,0);
            positionText=text("0:00",14,colors.MUTED,false);durationText=text("0:00",14,colors.MUTED,false);durationText.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
            for(TextView time:new TextView[]{positionText,durationText}){time.setSingleLine(true);time.setEllipsize(TextUtils.TruncateAt.END);}
            timeline.addView(positionText,new LinearLayout.LayoutParams(dp(56),-1));seek=new TimelineSeekBar(this);seek.setMax(1000);seek.setContentDescription("播放进度");
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                @Override public void onStartTrackingTouch(SeekBar b){dragging=true;}
                @Override public void onStopTrackingTouch(SeekBar b){if(playback!=null){playback.clearPracticeLoop();playback.seek(playback.duration()*b.getProgress()/1000L);}dragging=false;}
                @Override public void onProgressChanged(SeekBar b,int progress,boolean user){if(user&&playback!=null){long at=playback.duration()*progress/1000L;positionText.setText(MediaItem.time(at));if(nowLyrics!=null)nowLyrics.updatePosition(at);}}
            });
            timeline.addView(seek,new LinearLayout.LayoutParams(0,-1,1));timeline.addView(durationText,new LinearLayout.LayoutParams(dp(56),-1));playerPane.addView(timeline,new LinearLayout.LayoutParams(-1,dp(PlaybackControls.TIMELINE_DP)));
            shuffleIcon=new IconView(this,"repeat",colors.MUTED);View mode=smallControl(shuffleIcon,"播放模式",v->playbackMode());mode.setId(R.id.player_mode);
            downloadIcon=new IconView(this,"download",colors.MUTED);downloadButton=smallControl(downloadIcon,"下载到离线",v->currentDownload());
            playerPane.addView(new PlaybackControls(this,favorite,mode,previous,toggle,next,downloadButton,queueButton),new LinearLayout.LayoutParams(-1,dp(PlaybackControls.ROW_DP)));
        } else {
            FrameLayout frame=new FrameLayout(this);playerPane.addView(frame,new LinearLayout.LayoutParams(-1,dp(96)));
            View expand=iconButton("expand","展开全屏播放",v->showLyrics());expand.setId(R.id.player_expand);
            View miniCover=cover,miniQueue=queueButton;
            LinearLayout row=new LinearLayout(this){
                @Override protected void onMeasure(int ws,int hs){
                    int width=Math.max(0,MeasureSpec.getSize(ws)-getPaddingLeft()-getPaddingRight());
                    miniQueue.setVisibility(width>=dp(136)?VISIBLE:GONE);
                    previous.setVisibility(width>=dp(280)?VISIBLE:GONE);next.setVisibility(previous.getVisibility());
                    miniCover.setVisibility(width>=dp(344)?VISIBLE:GONE);
                    expand.setVisibility(width>=dp(404)?VISIBLE:GONE);
                    favorite.setVisibility(wide&&width>=dp(464)?VISIBLE:GONE);
                    toggle.getLayoutParams().width=Math.min(dp(56),width);
                    super.onMeasure(ws,hs);
                }
            };
            row.setGravity(Gravity.CENTER_VERTICAL);Ui.pad(row,16,8,8,8);frame.addView(row,new FrameLayout.LayoutParams(-1,-1));
            cover.setContentDescription("专辑封面，打开全屏播放");cover.setFocusable(true);cover.setOnClickListener(v->showLyrics());row.addView(cover,new LinearLayout.LayoutParams(dp(64),dp(64)));
            LinearLayout labels=Ui.column(this);Ui.pad(labels,16,0,8,0);labels.addView(playerTitle);Ui.gap(labels,4);labels.addView(playerArtist);labels.setFocusable(true);labels.setContentDescription("打开全屏播放和歌词");labels.setOnClickListener(v->showLyrics());row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            row.addView(previous,new LinearLayout.LayoutParams(dp(48),dp(56)));row.addView(toggle,new LinearLayout.LayoutParams(dp(56),dp(56)));row.addView(next,new LinearLayout.LayoutParams(dp(48),dp(56)));
            row.addView(favorite,new LinearLayout.LayoutParams(dp(48),dp(56)));
            row.addView(queueButton,new LinearLayout.LayoutParams(dp(48),dp(56)));row.addView(expand,new LinearLayout.LayoutParams(dp(48),dp(56)));
            miniProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);miniProgress.setMax(1000);
            android.graphics.drawable.LayerDrawable track=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{Ui.shape(colors.LINE,2,this),new android.graphics.drawable.ClipDrawable(Ui.shape(colors.ACCENT,2,this),Gravity.LEFT,android.graphics.drawable.ClipDrawable.HORIZONTAL)});track.setId(0,android.R.id.background);track.setId(1,android.R.id.progress);miniProgress.setProgressDrawable(track);
            FrameLayout.LayoutParams progress=new FrameLayout.LayoutParams(-1,dp(2),Gravity.BOTTOM);progress.setMargins(dp(24),0,dp(24),dp(2));frame.addView(miniProgress,progress);
            positionText=text("",16,colors.MUTED,false);durationText=text("",16,colors.MUTED,false);
        }
        renderedTrack="";renderedCover=null;tintPlayer(artworkTint);updatePlayer();
    }

    private void tintPlayer(int tint) {
        artworkTint=tint;boolean full="player".equals(section);
        boolean followCover=prefs.bool("cover_colors",true);
        playerColors=Ui.coverPalette(full?Ui.themePalette(prefs,false):colors,followCover?tint:AmbientDrawable.DEFAULT);
        if(full){colors=playerColors;if(ambience!=null)ambience.setTintColor(followCover&&tint!=AmbientDrawable.DEFAULT?tint:Ui.blend(Ui.BG,playerColors.ACCENT,.24f));}
        if(playButton!=null)playButton.setBackground(Ui.playButton(this,playerColors));
        if(playIcon!=null)playIcon.setIcon(playback!=null&&playback.wantsPlay()?"pause":"play",playerColors.ACCENT);
        if(playerPane!=null) {
            for(int id:new int[]{R.id.player_previous,R.id.player_next,R.id.queue_open,R.id.player_expand}) {
                View control=playerPane.findViewById(id);
                if(control instanceof ViewGroup&&((ViewGroup)control).getChildCount()>0&&((ViewGroup)control).getChildAt(0) instanceof IconView) {
                    String icon=id==R.id.player_previous?"previous":id==R.id.player_next?"next":id==R.id.queue_open?"queue":"expand";
                    ((IconView)((ViewGroup)control).getChildAt(0)).setIcon(icon,playerColors.ACCENT);
                    control.setBackground(Ui.background(this,android.graphics.Color.TRANSPARENT,playerColors.SOFT,playerColors));
                }
            }
        }
        for(IconView icon:new IconView[]{favoriteIcon,shuffleIcon,downloadIcon,effectsIcon})if(icon!=null&&icon.getParent() instanceof View)
            ((View)icon.getParent()).setBackground(Ui.background(this,android.graphics.Color.TRANSPARENT,playerColors.SOFT,playerColors));
        if(full&&effectsIcon!=null)effectsIcon.setIcon("equalizer",playerColors.ACCENT);
        if(full&&karaokeIcon!=null){karaokeIcon.setIcon("microphone",karaoke?playerColors.ACCENT:Ui.MUTED);((View)karaokeIcon.getParent()).setBackground(Ui.background(this,android.graphics.Color.TRANSPARENT,playerColors.SOFT,playerColors));}
        if(full&&nowQuality!=null){nowQuality.setTextColor(playerColors.ACCENT);nowQuality.setBackground(Ui.background(this,android.graphics.Color.TRANSPARENT,playerColors.SOFT,playerColors));}
        if(seek instanceof TimelineSeekBar)((TimelineSeekBar)seek).setPalette(playerColors);
        if(miniProgress!=null){
            android.graphics.drawable.LayerDrawable track=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{Ui.shape(colors.LINE,2,this),new android.graphics.drawable.ClipDrawable(Ui.shape(playerColors.ACCENT,2,this),Gravity.LEFT,android.graphics.drawable.ClipDrawable.HORIZONTAL)});
            track.setId(0,android.R.id.background);track.setId(1,android.R.id.progress);miniProgress.setProgressDrawable(track);
        }
        if(full&&nowLyrics!=null)nowLyrics.setPalette(playerColors);
        if(full&&nowTimer!=null)nowTimer.setTextColor(playerColors.ACCENT);
        if(drawerAdapter!=null)drawerAdapter.notifyDataSetChanged();
    }

    private void playbackMode() {
        if(playback==null)return;
        String[] labels={"顺序播放","列表循环","单曲循环","随机播放"};int selected=playback.shuffle()?3:playback.repeat();
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("播放模式").setSingleChoiceItems(labels,selected,(d,i)->{
            if(playback.shuffle()!=(i==3))playback.toggleShuffle();
            int repeat=i==3?1:i;while(playback.repeat()!=repeat)playback.cycleRepeat();d.dismiss();updatePlayer();
        }).setNegativeButton("关闭",null));
    }

    private void currentDownload() {
        MediaItem item=playback==null?null:playback.current();if(item==null)return;
        if(audioCache.saved(client,item)||audioCache.progress(client,item)>=0){trackMenu(item,-1,null);return;}
        if(!client.authenticated()){toast("联网并登录后可下载歌曲");return;}
        audioCache.download(client,item,prefs.audioQuality(),error->{if(!destroyed)toast(error.isEmpty()?"已保存到离线歌曲":error);});toast("已加入下载");updatePlayer();
    }

    private View smallControl(IconView icon,String label,View.OnClickListener listener) {
        LinearLayout root=Ui.row(this);root.setGravity(Gravity.CENTER);root.addView(icon,new LinearLayout.LayoutParams(dp(24),dp(24)));
        root.setBackground(Ui.background(this,android.graphics.Color.TRANSPARENT,colors.SOFT));root.setFocusable(true);root.setClickable(true);root.setContentDescription(label);root.setOnClickListener(listener);Ui.press(root);return root;
    }


    private void updatePlayer() {
        if(!shell||playback==null||playIcon==null)return;
        refreshAudioEffectsPanel();
        MediaItem item=playback.current();boolean full="player".equals(section);
        String track=item==null?"":item.id;
        if(!track.equals(paletteTrack)){paletteTrack=track;tintPlayer(AmbientDrawable.DEFAULT);}
        playIcon.setIcon(playback.wantsPlay()?"pause":"play",playerColors.ACCENT);playButton.setContentDescription(playback.wantsPlay()?"暂停":"播放");
        String status=item==null?"":!playback.error().isEmpty()?playback.error():playback.isBuffering()?"正在缓冲…":playback.isOfflinePlayback()?"正在离线播放":!playback.wantsPlay()?"已暂停":"";
        playerState.setText(status);playerState.setVisibility(status.isEmpty()?View.GONE:View.VISIBLE);
        if(full){updateNowPlaying(item);boolean attention=!playback.error().isEmpty()||playback.isBuffering();nowStatus.setText(status);nowStatus.setVisibility(attention?View.VISIBLE:View.GONE);updateNowQuality();}
        else {
            playerPane.setVisibility(item==null?View.GONE:View.VISIBLE);
            playerTitle.setText(item==null?"好音乐，在路上":item.name);
            if(item!=null&&!item.id.equals(renderedTrack)){renderedTrack=item.id;renderedCover=null;images.load(client,item,cover,(bitmap,tint)->{if(!destroyed&&!"player".equals(section)&&item.id.equals(paletteTrack)){tintPlayer(tint);updatePlayer();}});}
            if(item!=null&&playback.cover()!=null&&playback.cover()!=renderedCover){renderedCover=playback.cover();cover.setImageBitmap(renderedCover);}
        }
        String mode=playback.shuffle()?"随机播放":playback.allRandom()?"全库随机":playback.repeat()==0?"顺序播放":playback.repeat()==1?"列表循环":"单曲循环";
        repeatLabel.setText(mode);
        if(shuffleIcon!=null){shuffleIcon.setIcon(playback.shuffle()||playback.allRandom()?"shuffle":playback.repeat()==2?"repeat_one":"repeat",playerColors.ACCENT);((View)shuffleIcon.getParent()).setContentDescription("播放模式："+mode);}
        if(queueButton!=null)queueButton.setContentDescription("播放队列，"+playback.queueSize()+" 首"+(playback.hasMore()?"，将继续加载":""));
        favoriteIcon.setIcon(item!=null&&item.favorite?"heart_filled":"heart",playerColors.ACCENT);
        if(favoriteIcon.getParent() instanceof View)((View)favoriteIcon.getParent()).setContentDescription(item!=null&&item.favorite?"取消收藏当前歌曲":"收藏当前歌曲");
        updateDownloadControl();
        refreshDrawer();updateTimeline();
    }

    private void updateDownloadControl(){
        if(!shell||playback==null||downloadIcon==null)return;
        MediaItem item=playback.current();int progress=item==null?-1:audioCache.progress(client,item);boolean saved=item!=null&&audioCache.saved(client,item);
        downloadIcon.setIcon(saved?"check":progress>=0?"downloading":"download",playerColors.ACCENT);
        String label=saved?"已下载，歌曲操作":progress>=0?"下载中 "+progress+"%，歌曲操作":"下载到离线";
        if(!TextUtils.equals(downloadButton.getContentDescription(),label))downloadButton.setContentDescription(label);
    }

    private void updateTimeline() {
        if(playback==null||!shell||playerArtist==null)return;
        long duration=playback.duration(),position=playback.position();int progress=duration>0?(int)(Math.min(duration,position)*1000L/duration):0;
        if(seek!=null){
            if(!dragging){if(seek.getProgress()!=progress)seek.setProgress(progress);String label=MediaItem.time(position);if(!label.contentEquals(positionText.getText()))positionText.setText(label);}
            String length=MediaItem.time(duration);if(!length.contentEquals(durationText.getText()))durationText.setText(length);
            seek.setEnabled(playback.current()!=null&&duration>0);
        }
        if(miniProgress!=null){miniProgress.setProgress(progress);MediaItem item=playback.current();String state=playerState.getText().toString();String meta=item==null?"选择歌曲，开始聆听":(state.isEmpty()?"":state+" · ")+item.artist+" · "+MediaItem.time(position)+" / "+MediaItem.time(duration);if(!meta.contentEquals(playerArtist.getText()))playerArtist.setText(meta);playerArtist.setContentDescription(meta);}
        if("player".equals(section)&&nowLyrics!=null){
            nowLyrics.setLoopStatus(playback.practiceActive());
            nowLyrics.updatePosition(dragging&&seek!=null?duration*seek.getProgress()/1000L:position);
            long left=playback.sleepRemaining();String timer=playback.sleepAfterTrack()?"本曲结束":left>0?MediaItem.time(left):"";
            if(!timer.contentEquals(nowTimer.getText())){
                nowTimer.setText(timer);
                if(playerStage!=null)playerStage.requestLayout();
            }
        }
    }

    private void favorite(MediaItem item) {
        if(!client.authenticated()){toast("请重新登录 Emby");return;}
        final boolean desired=!item.favorite;final EmbyClient snapshot=client;
        toast(desired?"正在添加收藏…":"正在取消收藏…");
        async(()->{
            try { snapshot.favorite(item,desired);onUi(()->{
                if(!snapshot.currentSession())return;
                item.favorite=desired;
                if(playback!=null){MediaItem current=playback.current();if(current!=null&&current.id.equals(item.id))current.favorite=desired;playback.refreshMetadata();}
                if(adapter!=null)adapter.notifyDataSetChanged();toast(desired?"已添加到我喜欢":"已取消收藏");
            }); } catch(Exception e) { showError(snapshot,e); }
        });
    }


    private void settingsHeading(String value){Ui.gap(body,20);TextView title=text(value,22,colors.TEXT,true);Ui.pad(title,0,8,0,12);body.addView(title);}
    private TextView settingsAction(String label,View.OnClickListener action){TextView view=button(label,false,action);Ui.gap(body,8);body.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
    private void buildSettings() {
        ScrollView scroll=new ScrollView(this);body=Ui.column(this);Ui.pad(body,0,0,0,24);scroll.addView(body);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        settingsHeading("播放");
        settingsQuality=settingsAction("播放音质 · "+prefs.audioQuality().choice(),v->qualityMenu());settingsQuality.setId(R.id.settings_quality);
        TextView qualityHint=text("在线按所选档位播放，原始音质不可用时尝试 MP3 192 kbps。已有下载仍可离线使用。",15,colors.MUTED,false);
        Ui.pad(qualityHint,8,6,8,4);body.addView(qualityHint);
        settingsEffects=settingsAction("音效 · "+AudioEffects.Profile.read(prefs).mode.label,v->audioEffectsMenu());settingsEffects.setId(R.id.settings_effects);
        settingsAction("定时停止",v->sleepMenu());settingsAction("停止播放",v->{if(playback!=null)playback.stopPlayback(false);});
        settingsHeading("显示与歌词");setting("屏幕常亮","播放器在前台时保持屏幕亮起","keep_screen",true,()->onResume());
        TextView theme=button("外观模式 · "+(prefs.bool("light_theme",false)?"浅色":"深色"),false,v->chooseTheme());theme.setId(R.id.theme_toggle);body.addView(theme);Ui.gap(body,8);
        addThemeColorSetting();Ui.gap(body,8);
        setting("播放页跟随封面","随封面调整播放按钮与背景；关闭后使用所选主题色","cover_colors",true,()->tintPlayer(artworkTint)).setId(R.id.cover_colors);
        setting("界面动画","轻量过渡与触控反馈；关闭后立即切换，遵循系统动画设置","ui_motion",true,()->{if(!prefs.bool("ui_motion",true))motion.finishAll();}).setId(R.id.ui_motion);
        settingsAction("歌词字号",v->lyricFont());settingsAction("悬浮歌词 · "+(prefs.bool("floating_lyrics",false)?"已开启":"已关闭"),v->{toggleFloatingLyrics();navigate("settings");});settingsAction("翻译服务设置",v->translationSettings());
        settingsAction("桌面组件 · 竖版 / 卡片 / 控制条",v->widgetHelp()).setId(R.id.settings_widgets);
        setting("组件发音辅助","桌面歌词显示当前歌曲的粤拼或罗马音；歌词语言在播放页选择","widget_pronunciation",true,null);
        settingsAction("K 歌 · 跟唱与发音辅助",v->{karaoke=true;showLyrics();}).setId(R.id.settings_karaoke);
        settingsHeading("缓存与下载");audioCacheSetting=setting("自动缓存音频","播放时预缓存当前列表，满额后按最近播放时间替换最久未听的歌曲","audio_cache",true,()->{
            CachePreloader preloader=CachePreloader.get(this);
            if(!prefs.bool("audio_cache",true))preloader.suspend(false);
            else{preloader.resumeQueue();if(playback!=null)playback.refreshMetadata();}
        });
        settingsAction("自动缓存上限 · "+bytes(audioCache.limit(false)),v->cacheSize(false));settingsAction("离线下载上限 · "+bytes(audioCache.limit(true)),v->cacheSize(true));
        Ui.gap(body,12);libraryCacheStatus=text("",16,colors.MUTED,false);libraryCacheStatus.setId(R.id.library_cache_status);libraryCacheStatus.setLineSpacing(dp(4),1);Ui.pad(libraryCacheStatus,8,8,8,8);body.addView(libraryCacheStatus);
        libraryCacheButton=button("缓存曲库",false,v->{
            CachePreloader preloader=CachePreloader.get(this);CachePreloader.State state=preloader.state(client);
            if(state.running)preloader.pause();
            else if(!client.authenticated())toast("请重新登录后缓存曲库");
            else{if(!prefs.bool("audio_cache",true))audioCacheSetting.performClick();preloader.start(client);}
            updateLibraryCacheControls();
        });libraryCacheButton.setId(R.id.library_cache);body.addView(libraryCacheButton,new LinearLayout.LayoutParams(-1,-2));
        TextView cacheHint=text("逐页缓存曲库，跳过已有完整音频；满额自动暂停，可提高上限后继续。手动离线下载单独保留。",15,colors.MUTED,false);cacheHint.setLineSpacing(dp(3),1);Ui.pad(cacheHint,8,8,8,8);body.addView(cacheHint);updateLibraryCacheControls();
        settingsAction("离线歌曲与存储管理",v->navigate("downloads"));settingsAction("缓存与存储管理",v->downloadMenu());
        settingsHeading("车机与账号");setting("开机打开 NoMusic","收到系统开机广播后显示播放器","boot",false,null);
        TextView account=text(prefs.username()+"\n"+prefs.server(),18,colors.MUTED,false);account.setLineSpacing(dp(6),1);Ui.pad(account,0,8,0,16);body.addView(account);
        settingsAction("退出当前账号",v->dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("退出账号").setMessage("退出后停止播放，清除登录和播放队列；手动下载保留在此账号目录中。重新登录同一账号后可以继续使用。").setNegativeButton("取消",null).setPositiveButton("退出",(d,i)->logout())));
        settingsHeading("关于与更新");body.addView(text("NoMusic "+BuildConfig.VERSION_NAME,20,colors.TEXT,true));
        settingsAction("开源与词库许可",v->openSourceNotices());
        setting("启动时检查更新","每天最多检查一次，安装由系统确认","check_updates",true,null);settingsAction("检查更新",v->checkUpdate(true));
        settingsAction("车机适配信息",v->dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("车机适配").setMessage((getPackageName().equals("com.netease.cloudmusic")?"CS11 兼容版":"标准版")+"\nAndroid 4.1+ · 标准媒体按键\n"+getPackageName()).setPositiveButton("关闭",null)));
    }

    private void qualityMenu(){
        String[] choices=new String[AudioQuality.ALL.length];
        for(AudioQuality quality:AudioQuality.ALL)choices[quality.ordinal()]=quality.choice();
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK)
                .setTitle("播放音质").setSingleChoiceItems(choices,prefs.audioQuality().ordinal(),(d,i)->{
                    AudioQuality quality=AudioQuality.ALL[i];boolean changed=!prefs.hasChosenAudioQuality()||quality!=prefs.audioQuality();
                    prefs.setAudioQuality(quality);d.dismiss();
                    if(settingsQuality!=null&&"settings".equals(section))settingLabel(settingsQuality,"播放音质 · "+quality.choice());
                    if(changed&&playback!=null)playback.qualityChanged();
                    updateNowQuality();
                }).setNegativeButton("关闭",null));
    }

    private void updateNowQuality(){
        if(nowQuality==null)return;
        AudioQuality playing=playback==null?null:playback.activeQuality();
        AudioQuality quality=playing==null?prefs.audioQuality():playing;
        boolean disk=playing!=null&&playback.isOfflinePlayback();
        String caption=(disk?"本地 · ":"")+(quality.original()?"原始音质":quality.kbps+" kbps");
        if(!caption.contentEquals(nowQuality.getText()))nowQuality.setText(caption);
        String label=(playing==null?"所选音质：":disk?"本地音质：":"当前播放档位：")+quality.format()+"，选择播放音质";
        if(!TextUtils.equals(label,nowQuality.getContentDescription()))nowQuality.setContentDescription(label);
    }

    private void audioEffectsMenu(){
        if(destroyed||isFinishing())return;
        AudioEffectsPanel panel=new AudioEffectsPanel(this,colors,AudioEffects.Profile.read(prefs),profile->{
            profile.save(prefs);if(playback!=null)playback.audioEffectsChanged(profile);
            if(settingsEffects!=null&&"settings".equals(section))settingLabel(settingsEffects,"音效 · "+profile.mode.label);
            refreshAudioEffectsPanel();
        });
        effectsPanel=panel;refreshAudioEffectsPanel();
        ScrollView scroll=new ScrollView(this);scroll.addView(panel,new ScrollView.LayoutParams(-1,-2));
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK)
                .setTitle("音效").setView(scroll).setPositiveButton("完成",null));
        activeDialog.setOnDismissListener(d->{if(effectsPanel==panel)effectsPanel=null;});
    }

    private void settingLabel(TextView row,String label){row.setText(label);row.setContentDescription(label);}

    private void refreshAudioEffectsPanel(){
        if(effectsPanel==null)return;
        AudioEffects.Profile profile=AudioEffects.Profile.read(prefs);
        effectsPanel.status(playback!=null?playback.audioEffectsStatus(profile):profile.mode==AudioEffects.Mode.OFF?"音效已关闭":"播放音乐后应用");
    }
    private void chooseTheme() {
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("外观模式").setSingleChoiceItems(new String[]{"深色","浅色"},prefs.bool("light_theme",false)?1:0,(d,i)->{d.dismiss();prefs.setBool("light_theme",i==1);refreshAppearance();}).setNegativeButton("取消",null));
    }
    private void updateLibraryCacheControls(){
        if(!shell||!"settings".equals(section)||libraryCacheStatus==null)return;
        CachePreloader.State state=CachePreloader.get(this).state(client);long[] usage=audioCache.usage(client);
        libraryCacheStatus.setText(state.label()+"\n自动缓存 · "+bytes(usage[1])+" / "+bytes(audioCache.limit(false)));
        libraryCacheButton.setText(state.running?"暂停缓存曲库":state.finished?"重新扫描并缓存曲库":state.next>0||state.full?"继续缓存曲库":"缓存曲库");
        libraryCacheButton.setEnabled(client.authenticated()||state.running);
    }
    private void refreshAppearance(){
        int scrollY=content.getChildCount()>0?content.getChildAt(0).getScrollY():0;
        section="settings";showShell();
        View page=content.getChildAt(0);page.post(()->page.scrollTo(0,scrollY));
    }
    private void addThemeColorSetting(){
        LinearLayout entry=Ui.row(this);Ui.pad(entry,16,12,16,12);entry.setId(R.id.theme_color);
        entry.setBackground(Ui.background(this,colors.SURFACE,colors.SOFT));
        View swatch=new View(this);swatch.setBackground(Ui.shape(colors.ACCENT,20,this));entry.addView(swatch,new LinearLayout.LayoutParams(dp(28),dp(28)));
        TextView label=text("主题颜色 · "+Ui.themeColor(prefs).label,20,colors.TEXT,true);Ui.pad(label,12,0,8,0);
        label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.END);entry.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        entry.addView(new IconView(this,"forward",colors.MUTED),new LinearLayout.LayoutParams(dp(20),dp(20)));
        entry.setMinimumHeight(dp(64));entry.setContentDescription("主题颜色，"+Ui.themeColor(prefs).label);
        entry.setFocusable(true);entry.setClickable(true);entry.setOnClickListener(v->chooseThemeColor());body.addView(entry,new LinearLayout.LayoutParams(-1,-2));
        Ui.press(entry);
    }
    private void chooseThemeColor(){
        ScrollView scroll=new ScrollView(this);LinearLayout choices=Ui.column(this);Ui.pad(choices,20,8,20,8);
        choices.setId(R.id.theme_color_picker);scroll.setFillViewport(true);scroll.addView(choices);
        TextView hint=text("为首页、按钮和选中状态换一种颜色",14,colors.MUTED,false);hint.setLineSpacing(dp(3),1);choices.addView(hint);Ui.gap(choices,16);
        Ui.ThemeColor selected=Ui.themeColor(prefs);
        for(int start=0;start<Ui.THEME_COLORS.length;start+=2){
            LinearLayout row=Ui.row(this);
            for(int col=0;col<2&&start+col<Ui.THEME_COLORS.length;col++){
                Ui.ThemeColor choice=Ui.THEME_COLORS[start+col];boolean active=choice==selected;int accent=colors.light?choice.day:choice.night;
                LinearLayout option=Ui.row(this);Ui.pad(option,12,12,8,12);option.setTag("theme_choice_"+choice.id);
                option.setBackground(Ui.background(this,active?colors.SOFT:colors.PANEL,colors.SOFT));
                IconView swatch=new IconView(this,"check",active?colors.DARK:android.graphics.Color.TRANSPARENT);
                swatch.setBackground(Ui.shape(accent,20,this));Ui.pad(swatch,6,6,6,6);
                option.addView(swatch,new LinearLayout.LayoutParams(dp(32),dp(32)));
                TextView label=text(choice.label,18,colors.TEXT,active);label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.END);Ui.pad(label,10,0,0,0);
                option.addView(label,new LinearLayout.LayoutParams(0,-2,1));option.setSelected(active);
                option.setContentDescription(choice.label+(active?"，已选择":""));option.setFocusable(true);option.setClickable(true);
                option.setOnClickListener(v->{activeDialog.dismiss();prefs.setThemeColor(choice.id);refreshAppearance();});
                Ui.press(option);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(64),1);if(col==0)lp.rightMargin=dp(10);row.addView(option,lp);
            }
            choices.addView(row,new LinearLayout.LayoutParams(-1,-2));Ui.gap(choices,10);
        }
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("主题颜色").setView(scroll).setNegativeButton("取消",null));
    }
    private void cacheSize(boolean offline){
        long[] sizes={256*AppPrefs.MIB,512*AppPrefs.MIB,1024*AppPrefs.MIB,2*1024*AppPrefs.MIB,5*1024*AppPrefs.MIB,10*1024*AppPrefs.MIB,20*1024*AppPrefs.MIB};
        String[] labels=new String[sizes.length+1];int selected=-1;
        for(int i=0;i<sizes.length;i++){labels[i]=bytes(sizes[i]);if(audioCache.limit(offline)==sizes[i])selected=i;}labels[sizes.length]="自定义（256–20480 MB）";
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle(offline?"离线下载上限":"自动缓存上限").setSingleChoiceItems(labels,selected,(d,i)->{
            d.dismiss();if(i<sizes.length){saveCacheSize(offline,sizes[i]);return;}
            EditText size=field("256–20480 MB",InputType.TYPE_CLASS_NUMBER,View.NO_ID);size.setText(String.valueOf(audioCache.limit(offline)/AppPrefs.MIB));
            dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("自定义上限（MB）").setView(size).setNegativeButton("取消",null).setPositiveButton("保存",null));
            activeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{long mb=Long.parseLong(size.getText().toString().trim());if(mb<256||mb>20480)throw new NumberFormatException();activeDialog.dismiss();saveCacheSize(offline,mb*AppPrefs.MIB);}catch(NumberFormatException e){size.setError("请输入 256–20480 MB");}});
        }).setNegativeButton("取消",null));
    }
    private void saveCacheSize(boolean offline,long size){audioCache.setLimit(offline,size);toast(offline?"已保存上限，已有下载会保留":"已保存上限，超额缓存将在后台清理");if("settings".equals(section))navigate("settings");else if("downloads".equals(section))showDownloads();}
    private View setting(String title,String description,String key,boolean fallback,Runnable callback) {
        LinearLayout row=Ui.row(this);Ui.pad(row,8,12,8,12);row.setBackground(Ui.background(this,android.graphics.Color.TRANSPARENT,colors.SOFT));
        LinearLayout labels=Ui.column(this);labels.addView(text(title,20,colors.TEXT,true));Ui.gap(labels,6);TextView detail=text(description,16,colors.MUTED,false);detail.setLineSpacing(dp(3),1);labels.addView(detail);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        IconView toggle=new IconView(this,prefs.bool(key,fallback)?"toggle_on":"toggle_off",prefs.bool(key,fallback)?colors.ACCENT:colors.MUTED);Ui.pad(toggle,12,12,12,12);row.addView(toggle,new LinearLayout.LayoutParams(dp(64),dp(56)));
        row.setContentDescription(title+"，"+(prefs.bool(key,fallback)?"已开启":"已关闭"));row.setFocusable(true);row.setClickable(true);row.setOnClickListener(v->{boolean enabled=!prefs.bool(key,fallback);prefs.setBool(key,enabled);toggle.setIcon(enabled?"toggle_on":"toggle_off",enabled?colors.ACCENT:colors.MUTED);row.setContentDescription(title+"，"+(enabled?"已开启":"已关闭"));if(callback!=null)callback.run();});Ui.press(row);body.addView(row);return row;
    }

    private void logout() {
        ++requestId;
        if(playback!=null)playback.stopPlayback(true);
        prefs.logout();audioCache.cancelStale();stopService(new Intent(this,FloatingLyricsService.class));
        expiredMessage="";client=new EmbyClient(prefs);items.clear();images.close();images=new ImageLoader();showLogin();
    }

    private void showLyrics() {
        if(!shell||"player".equals(section))return;
        closeDrawer();motion.finishTree(content);
        hideKeyboard();
        if(!returningToPlayer) {
            playerBack=pageState();playerBackList=null;
            if(adapter!=null&&!loading&&loaded>0&&list!=null)playerBackList=browserSnapshot();
        }
        returningToPlayer=false;
        section="player";adapter=null;dragging=false;loading=false;++requestId;
        setFullscreen(true);buildNowPlaying();
    }

    private void leavePlayer(Runnable destination){
        if(playback!=null)playback.clearPracticeLoop();
        if(pendingPlayerExit!=null)return;
        if(nowRoot==null){destination.run();return;}
        pendingPlayerExit=destination;shellRoot.setVisibility(View.VISIBLE);
        ((ViewGroup)nowRoot).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);nowRoot.requestFocus();
        motion.playerClose(nowRoot,playerStage,this::finishPlayerExit);
    }

    private void finishPlayerExit(){
        Runnable destination=pendingPlayerExit;pendingPlayerExit=null;
        if(destination!=null)destination.run();
    }

    private void buildNowPlaying() {
        colors=Ui.themePalette(prefs,false);cancelLyrics();nowTrack="";baseLyrics=null;lyricEdits=LyricsEdits.EMPTY;restoreLyricBookmark=true;
        FrameLayout page=new FrameLayout(this){
            @Override public boolean onInterceptTouchEvent(MotionEvent event){return pendingPlayerExit!=null||super.onInterceptTouchEvent(event);}
        };
        nowRoot=page;ambience=new AmbientDrawable();page.setBackground(ambience);page.setFocusableInTouchMode(true);page.setClickable(true);
        LinearLayout top=Ui.row(this);
        top.addView(iconButton("down","退出全屏播放",v->onBackPressed()),new LinearLayout.LayoutParams(dp(48),-1));
        String source=playerBack==null?"播放队列":playerBack.name!=null?playerBack.name:"songs".equals(playerBack.section)?"全部歌曲":"playlists".equals(playerBack.section)?"歌单":"downloads".equals(playerBack.section)?"离线歌曲":"播放队列";
        TextView title=text("来自 · "+source,20,colors.MUTED,false);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);Ui.pad(title,12,0,6,0);top.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        View viewToggle=iconButton("lyrics","显示封面",v->{if(playerStage!=null)playerStage.toggleView();});top.addView(viewToggle,new LinearLayout.LayoutParams(dp(48),-1));
        karaokeIcon=new IconView(this,"microphone",colors.MUTED);
        View karaokeButton=smallControl(karaokeIcon,"K 歌跟唱",v->{karaoke=!karaoke;applyKaraoke();});karaokeButton.setId(R.id.player_karaoke);top.addView(karaokeButton,new LinearLayout.LayoutParams(dp(48),-1));
        nowQuality=text("",14,colors.ACCENT,true);nowQuality.setId(R.id.player_quality);nowQuality.setGravity(Gravity.CENTER);nowQuality.setSingleLine(true);nowQuality.setEllipsize(TextUtils.TruncateAt.END);nowQuality.setMinWidth(dp(48));nowQuality.setMaxWidth(dp(182));
        Ui.pad(nowQuality,10,0,10,0);nowQuality.setFocusable(true);nowQuality.setOnClickListener(v->qualityMenu());Ui.press(nowQuality);
        top.addView(nowQuality,new LinearLayout.LayoutParams(-2,-1));updateNowQuality();
        effectsIcon=new IconView(this,"equalizer",colors.ACCENT);
        View effects=smallControl(effectsIcon,"音效",v->audioEffectsMenu());effects.setId(R.id.player_effects);top.addView(effects,new LinearLayout.LayoutParams(dp(48),-1));
        nowTimer=text("",18,colors.ACCENT,false);nowTimer.setSingleLine(true);nowTimer.setMaxWidth(dp(96));nowTimer.setEllipsize(TextUtils.TruncateAt.END);top.addView(nowTimer);
        View timer=iconButton("recent","定时停止",v->sleepMenu());top.addView(timer,new LinearLayout.LayoutParams(dp(48),-1));
        top.addView(iconButton("more","当前歌曲操作",v->{if(playback!=null&&playback.current()!=null)trackMenu(playback.current(),-1,null);}),new LinearLayout.LayoutParams(dp(48),-1));
        nowCover=new ArtView(this,16);nowCover.setContentDescription("当前歌曲封面");
        nowTitle=text("好音乐，在路上",28,colors.TEXT,true);nowTitle.setMaxLines(2);nowTitle.setMinHeight(dp(40));nowTitle.setGravity(Gravity.CENTER);nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout links=Ui.row(this);links.setBaselineAligned(false);
        nowArtist=text("选择歌曲",18,colors.MUTED,false);nowArtist.setGravity(Gravity.CENTER);nowArtist.setSingleLine(true);nowArtist.setEllipsize(TextUtils.TruncateAt.END);nowArtist.setMinHeight(dp(48));nowArtist.setFocusable(true);nowArtist.setBackground(Ui.background(this,android.graphics.Color.TRANSPARENT,colors.SOFT));nowArtist.setOnClickListener(v->openCurrentArtist());
        nowBitrate=text("",14,colors.MUTED,true);nowBitrate.setId(R.id.player_bitrate);nowBitrate.setGravity(Gravity.CENTER);nowBitrate.setSingleLine(true);nowBitrate.setVisibility(View.GONE);Ui.pad(nowBitrate,4,0,4,0);
        nowAlbum=text("",18,colors.MUTED,false);nowAlbum.setGravity(Gravity.CENTER);nowAlbum.setSingleLine(true);nowAlbum.setEllipsize(TextUtils.TruncateAt.END);nowAlbum.setMinHeight(dp(48));nowAlbum.setFocusable(true);nowAlbum.setBackground(Ui.background(this,android.graphics.Color.TRANSPARENT,colors.SOFT));
        nowAlbum.setOnClickListener(v->{MediaItem item=playback==null?null:playback.current();if(item==null)return;if(!client.authenticated()){toast("联网后查看专辑");return;}if(item.albumId.isEmpty())songInfo(item);else openLinked("albums",item.albumId,"MusicAlbum",item.album);});
        links.addView(nowArtist,new LinearLayout.LayoutParams(0,-2,1));links.addView(nowBitrate,new LinearLayout.LayoutParams(-2,-2));links.addView(nowAlbum,new LinearLayout.LayoutParams(0,-2,1));
        nowStatus=text("",16,colors.MUTED,false);nowStatus.setGravity(Gravity.CENTER);nowStatus.setMaxLines(2);nowStatus.setEllipsize(TextUtils.TruncateAt.END);nowStatus.setVisibility(View.GONE);
        CoverPanel details=new CoverPanel(this,nowCover,nowTitle,links,nowStatus);
        nowLyrics=new LyricsView(this,prefs,SystemClock::elapsedRealtime,null);
        nowLyrics.setMoreListener(v->playerMenu());
        nowLyrics.setPronunciationListener(v->pronunciationMenu());
        nowLyrics.setSeekListener((p,v,index,id)->{long at=nowLyrics.seekPosition(index);if(at<0||playback==null)return false;playback.clearPracticeLoop();playback.seek(at);return true;});
        playerPane=new LinearLayout(this){
            @Override protected void onMeasure(int ws,int hs){
                int available=Math.max(0,MeasureSpec.getSize(hs)-getPaddingBottom());
                int buttons=Math.min(dp(PlaybackControls.ROW_DP),available);
                getChildAt(0).getLayoutParams().height=Math.min(dp(PlaybackControls.TIMELINE_DP),available-buttons);
                getChildAt(1).getLayoutParams().height=buttons;
                super.onMeasure(ws,hs);
            }
        };
        playerPane.setOrientation(LinearLayout.VERTICAL);buildPlayer();
        playerStage=new PlayerStage(this,top,details,nowLyrics,playerPane,viewToggle,nowQuality,timer,nowTimer);page.addView(playerStage,new FrameLayout.LayoutParams(-1,-1));
        applyKaraoke();
        shellFrame.addView(page,new FrameLayout.LayoutParams(-1,-1));
        motion.playerOpen(page,playerStage,()->{if(nowRoot==page&&pendingPlayerExit==null)shellRoot.setVisibility(View.GONE);});
        playButton.requestFocus();updatePlayer();
    }

    private void openCurrentArtist() {
        MediaItem item=playback==null?null:playback.current();if(item==null)return;
        if(!client.authenticated()){toast("联网后查看歌手");return;}
        LinkedHashMap<String,Runnable> options=new LinkedHashMap<>();
        for(int i=0;i<item.artistItems.length();i++){JSONObject artist=item.artistItems.optJSONObject(i);if(artist==null)continue;String id=artist.optString("Id"),name=artist.optString("Name");if(!id.isEmpty())options.put(name,()->openLinked("artists",id,"MusicArtist",name));}
        if(options.size()==1)options.values().iterator().next().run();else if(options.isEmpty())songInfo(item);else actions("选择歌手",options);
    }

    private void updateNowPlaying(MediaItem item) {
        if(nowTitle==null)return;
        if(item==null){cancelLyrics();nowTitle.setText("好音乐，在路上");nowArtist.setText("选择歌曲，开始聆听");showNowBitrate(SourceAudio.EMPTY);nowAlbum.setVisibility(View.GONE);nowStatus.setText("");nowLyrics.setMessage("选择歌曲后显示歌词");nowCover.setTag(null);nowCover.setImageResource(R.drawable.cover_placeholder);nowTrack="";baseLyrics=null;return;}
        nowTitle.setText(item.name);nowArtist.setText(item.artist+" ›");nowAlbum.setText(item.album+" ›");
        nowArtist.setContentDescription("查看歌手 "+item.artist);nowAlbum.setContentDescription("查看专辑 "+item.album);
        showNowBitrate(item.sourceAudio);
        nowAlbum.setVisibility(item.album.isEmpty()?View.GONE:View.VISIBLE);nowStatus.setText(playerState.getText());
        if(item.id.equals(nowTrack))return;
        nowTrack=item.id;baseLyrics=null;lyricEdits=LyricsEdits.EMPTY;
        nowLyrics.setMessage("正在加载歌词…");nowLyrics.setPronunciationScope(PronunciationRepository.scope(client,item));
        if(item.sourceAudio.bitrate==0)loadNowBitrate(item);
        images.load(client,item,nowCover,(bitmap,tint)->{if(!destroyed&&nowRoot!=null&&item.id.equals(nowTrack)){tintPlayer(tint);updatePlayer();nowCover.setAlpha(0f);nowCover.animate().alpha(1f).setDuration(200).start();}});
        nowLyrics.setOffset(0);loadLyrics(item,false);
    }

    private void showNowBitrate(SourceAudio audio){
        String label=audio.bitrateLabel();
        if(!label.contentEquals(nowBitrate.getText())){
            nowBitrate.setText(label);nowBitrate.setContentDescription(label.isEmpty()?null:"原文件码率 "+label);
        }
        nowBitrate.setVisibility(label.isEmpty()?View.GONE:View.VISIBLE);
    }

    private void loadNowBitrate(MediaItem item){
        // Saved queues from older releases may lack source facts. Only look up the selected track.
        EmbyClient snapshot=client;
        async(()->{
            try{
                SourceAudio facts=snapshot.sourceAudio(item);
                onUi(()->{
                    if(!snapshot.currentSession()||facts==SourceAudio.EMPTY)return;
                    boolean changed=item.fillSourceAudio(facts);
                    if("player".equals(section)&&item.id.equals(nowTrack)&&playback!=null&&playback.current()!=null){
                        MediaItem current=playback.current();
                        if(current.id.equals(item.id)){current.fillSourceAudio(facts);showNowBitrate(current.sourceAudio);}
                    }
                    if(changed)refreshVisibleCache();
                });
            }catch(Exception ignored){/* Leave bitrate hidden when the server has no available facts. */}
        });
    }

    private void loadLyrics(MediaItem item,boolean force) {
        cancelLyrics();final LyricsView target=nowLyrics;final EmbyClient snapshot=client;
        LyricsRepository.Result cached=lyrics.peek(snapshot,item);
        if(cached!=null){baseLyrics=cached;applyLyrics();}else if(baseLyrics==null)target.setMessage(force?"正在刷新歌词…":"正在加载歌词…");
        if(force)toast("正在刷新歌词…");
        lyricRequest=lyrics.load(snapshot,item,force,result->{
            if(!sameLyrics(target,item,snapshot))return;
            baseLyrics=result;applyLyrics();
            if(force)sendBroadcast(new Intent(MusicWidgets.REFRESH).setPackage(getPackageName()));
            if(force)toast(!result.error.isEmpty()?(result.text.isEmpty()?"刷新失败，请检查网络后重试":"刷新失败，已保留缓存歌词"):result.text.isEmpty()?"服务器暂无可用歌词":"歌词已刷新");
        });
        if(!force)async(()->{
            LyricsEdits edits=LyricsEdits.read(this,snapshot,item);
            onUi(()->{if(sameLyrics(target,item,snapshot)){lyricEdits=edits;applyLyrics();}});
        });
    }

    private boolean sameLyrics(LyricsView target,MediaItem item,EmbyClient snapshot) {
        return !destroyed&&shell&&"player".equals(section)&&target==nowLyrics&&item.id.equals(nowTrack)&&snapshot.currentSession();
    }

    private void applyLyrics() {
        if(nowLyrics==null)return;
        nowLyrics.setOffset(lyricEdits.offset);
        if(baseLyrics!=null||!lyricEdits.original.isEmpty())nowLyrics.setResult(lyricEdits.apply(baseLyrics==null?new LyricsRepository.Result("",""):baseLyrics));
        if(playback!=null)nowLyrics.updatePosition(playback.position());
        if(restoreLyricBookmark&&lyricBookmark!=null&&nowTrack.equals(lyricBookmarkTrack)&&baseLyrics!=null){restoreLyricBookmark=false;nowLyrics.restore(lyricBookmark);}
    }

    private void playerMenu() {
        MediaItem item=playback==null?null:playback.current();
        LinkedHashMap<String,Runnable> choices=new LinkedHashMap<>();
        choices.put(karaoke?"退出 K 歌跟唱":"进入 K 歌跟唱",()->{karaoke=!karaoke;applyKaraoke();});
        choices.put("发音辅助与歌词语言",this::pronunciationMenu);
        if(karaoke)choices.put(playback!=null&&playback.practiceActive()?"关闭单句循环":"循环练习当前句",this::togglePracticeLoop);
        if(item!=null) {
            choices.put("歌词时间偏移",()->lyricOffset(item));
            choices.put("编辑歌词与独立译文",()->editLyrics(item));
            choices.put("翻译这首歌",()->translate(item));
            if(client.authenticated())choices.put("从 Emby 刷新歌词",()->loadLyrics(item,true));
        }
        choices.put("歌词字号",this::lyricFont);
        choices.put(prefs.bool("floating_lyrics",false)?"关闭悬浮歌词":"开启悬浮歌词",this::toggleFloatingLyrics);
        actions("歌词设置",choices);
    }

    private void applyKaraoke(){
        if(nowLyrics==null||playerStage==null)return;
        nowLyrics.setKaraoke(karaoke);
        if(karaokeIcon!=null){karaokeIcon.setIcon("microphone",karaoke?playerColors.ACCENT:Ui.MUTED);((View)karaokeIcon.getParent()).setSelected(karaoke);((View)karaokeIcon.getParent()).setContentDescription(karaoke?"退出 K 歌跟唱":"进入 K 歌跟唱");}
        if(!karaoke&&playback!=null)playback.clearPracticeLoop();
        nowLyrics.setLoopStatus(playback!=null&&playback.practiceActive());
    }
    private void togglePracticeLoop(){
        if(playback==null||nowLyrics==null)return;
        if(playback.practiceActive()){playback.clearPracticeLoop();nowLyrics.setLoopStatus(false);return;}
        if(!playback.practiceLoop(nowLyrics.document(),nowLyrics.currentCue(),lyricEdits.offset)){toast(nowLyrics.document().timed?"播放到要练习的歌词后再开启循环":"纯文本歌词没有时间轴，无法单句循环");return;}
        nowLyrics.setLoopStatus(true);toast("已循环当前句，长按其他歌词可跳转");
    }
    private void pronunciationMenu(){
        MediaItem item=playback==null?null:playback.current();if(item==null){toast("请先选择歌曲");return;}
        String scope=PronunciationRepository.scope(client,item);
        PronunciationEngine.Language current=PronunciationRepository.language(prefs,scope);
        LinkedHashMap<String,Runnable> choices=new LinkedHashMap<>();
        for(PronunciationEngine.Language language:PronunciationEngine.Language.values())choices.put((language==current?"✓ ":"")+language.label,()->{
            PronunciationRepository.setLanguage(prefs,scope,language);prefs.setBool(karaoke?"karaoke_pronunciation":"lyrics_pronunciation",true);if(nowLyrics!=null)nowLyrics.refreshPronunciation();
        });
        String enabled=karaoke?"karaoke_pronunciation":"lyrics_pronunciation";
        choices.put(prefs.bool(enabled,karaoke)?"隐藏发音":"显示发音",()->{prefs.setBool(enabled,!prefs.bool(enabled,karaoke));if(nowLyrics!=null)nowLyrics.refreshPronunciation();});
        choices.put("发音说明",()->dialog(new AlertDialog.Builder(this).setTitle("跟唱发音辅助").setMessage("粤语显示粤拼（数字为声调），日语显示罗马音并按词组读取汉字，韩语显示含常见连音的罗马音。\n\n自动识别日语假名与韩文；汉字歌词请手动选粤语。语言按歌曲保存，发音在本机生成并缓存。\n\n专名、多音字和演唱中的特殊读音可能需要对照原唱。有逐词时间时同步高亮，普通 LRC 按句高亮。").setPositiveButton("知道了",null)));
        actions("发音语言 · "+current.label,choices);
    }
    private void widgetHelp(){
        dialog(new AlertDialog.Builder(this).setTitle("添加 NoMusic 桌面组件").setMessage("长按桌面空白处 → 小部件 → NoMusic，选择竖版歌词、歌词卡片或播放控制条。\n\nCS11 建议选竖版歌词，拖动边缘至约三分之一屏宽和桌面全高；桌面网格决定最终尺寸。支持拖动调整大小。\n\n组件支持播放、暂停、上下首，点击封面或歌词进入播放页；竖版和卡片可直接打开 K 歌。无需悬浮窗权限。\n\n若原厂桌面没有“小部件”入口，需要支持标准 Android 小部件的桌面才能添加。").setPositiveButton("知道了",null));
    }
    private void openSourceNotices(){
        async(()->{
            try(java.io.InputStream input=getAssets().open("licenses/pronunciation-notices.txt")){
                String notice=new String(StorageFiles.read(input,64*1024),"UTF-8");
                onUi(()->dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("开源与词库许可").setMessage(notice).setPositiveButton("知道了",null)));
            }catch(Exception failure){onUi(()->toast("无法读取许可说明"));}
        });
    }

    private void lyricOffset(MediaItem item) {
        LinearLayout box=Ui.column(this);Ui.pad(box,20,12,20,16);
        box.addView(text("正数让歌词提前，负数让歌词延后。",15,colors.MUTED,false));
        final int initial=lyricEdits.offset;
        TextView value=text("",22,colors.TEXT,true);value.setGravity(Gravity.CENTER);box.addView(value,new LinearLayout.LayoutParams(-1,dp(48)));
        SeekBar shift=new SeekBar(this);shift.setMax(200);shift.setProgress((initial+10000)/100);box.addView(shift);
        value.setText((initial>0?"+":"")+initial+" ms");
        shift.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
            @Override public void onProgressChanged(SeekBar b,int progress,boolean fromUser){
                int offset=progress*100-10000;value.setText((offset>0?"+":"")+offset+" ms");
            }
        });
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("歌词时间偏移").setView(box).setNegativeButton("取消",null)
                .setNeutralButton("重置",(d,i)->saveEdits(item,new LyricsEdits(lyricEdits.original,lyricEdits.translation,0)))
                .setPositiveButton("保存",(d,i)->saveEdits(item,new LyricsEdits(lyricEdits.original,lyricEdits.translation,shift.getProgress()*100-10000))));
    }

    private void lyricFont() {
        int[] sizes={26,30,34};String[] labels={"标准 · 26","舒适 · 30","大字 · 34"};
        int selected=-1,font=prefs.store.getInt("lyrics_font",30);
        for(int i=0;i<sizes.length;i++)if(sizes[i]==font)selected=i;
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("歌词字号").setSingleChoiceItems(labels,selected,(d,i)->{
            prefs.store.edit().putInt("lyrics_font",sizes[i]).apply();if(nowLyrics!=null)nowLyrics.setFont(sizes[i]);d.dismiss();
        }).setNegativeButton("关闭",null));
    }

    private void editLyrics(MediaItem item) {
        ScrollView scroll=new ScrollView(this);LinearLayout box=Ui.column(this);Ui.pad(box,20,8,20,12);scroll.addView(box);
        TextView note=text("支持 LRC 或纯文本。译文可使用自己的时间轴，纯文本译文需与原文行数相同。",14,colors.MUTED,false);
        note.setLineSpacing(dp(4),1);box.addView(note);
        Ui.gap(box,12);box.addView(text("原文",16,colors.TEXT,true));
        EditText original=lyricInput(lyricEdits.original.isEmpty()&&baseLyrics!=null?baseLyrics.text:lyricEdits.original);
        box.addView(original,new LinearLayout.LayoutParams(-1,dp(136)));
        Ui.gap(box,12);box.addView(text("独立译文（可选）",16,colors.TEXT,true));
        EditText translation=lyricInput(lyricEdits.translation);box.addView(translation,new LinearLayout.LayoutParams(-1,dp(100)));
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("编辑本地歌词").setView(scroll).setNegativeButton("取消",null)
                .setNeutralButton("恢复服务器歌词",(d,i)->saveEdits(item,new LyricsEdits("","",lyricEdits.offset)))
                .setPositiveButton("保存",(d,i)->{
                    String edited=original.getText().toString().trim(),translated=translation.getText().toString().trim();
                    if(!translated.isEmpty()) {
                        String source=edited.isEmpty()&&baseLyrics!=null?baseLyrics.text:edited;
                        if(LyricsDocument.merge(source,translated).equals(source)) {toast("译文与原文时间轴或行数不匹配");return;}
                    }
                    saveEdits(item,new LyricsEdits(edited,translated,lyricEdits.offset));
                }));
    }

    private EditText lyricInput(String value) {
        EditText edit=new EditText(this);edit.setTextSize(15);edit.setTextColor(colors.TEXT);edit.setGravity(Gravity.TOP);
        edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setBackground(Ui.shape(colors.SURFACE,10,this));Ui.pad(edit,12,8,12,8);edit.setText(value);return edit;
    }

    private void saveEdits(MediaItem item,LyricsEdits edits) {
        EmbyClient snapshot=client;LyricsView target=nowLyrics;
        async(()->{
            try {
                edits.write(this,snapshot,item);
                onUi(()->{if(sameLyrics(target,item,snapshot)){lyricEdits=edits;applyLyrics();toast("歌词设置已保存");}});
            } catch(Exception e){onUi(()->{if(snapshot.currentSession())toast("保存歌词失败，请检查存储空间");});}
        });
    }

    private void translationSettings() {
        LinearLayout box=Ui.column(this);Ui.pad(box,20,10,20,14);
        TextView note=text("填写 LibreTranslate 的 HTTPS /translate 地址。点「翻译这首歌」时才会向该服务发送歌词。",14,colors.MUTED,false);
        note.setLineSpacing(dp(4),1);box.addView(note);Ui.gap(box,12);
        EditText endpoint=field("https://…/translate",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI,View.NO_ID);
        EditText key=field("API 密钥（可选）",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,View.NO_ID);
        endpoint.setText(prefs.store.getString("translate_url",""));key.setText(prefs.store.getString("translate_key",""));
        box.addView(endpoint);Ui.gap(box,8);box.addView(key);
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("翻译服务设置").setView(box).setNegativeButton("取消",null)
                .setPositiveButton("保存",(d,i)->{
                    String url=endpoint.getText().toString().trim();
                    if(!url.isEmpty()&&!url.startsWith("https://")){toast("请输入 HTTPS 翻译接口地址");return;}
                    prefs.store.edit().putString("translate_url",url).putString("translate_key",key.getText().toString().trim()).apply();
                }));
    }

    private void translate(MediaItem item) {
        String url=prefs.store.getString("translate_url",""),key=prefs.store.getString("translate_key","");
        if(url.isEmpty()){translationSettings();return;}
        LyricsEdits existing=lyricEdits;LyricsView target=nowLyrics;EmbyClient snapshot=client;
        LyricsDocument document=existing.original.isEmpty()?(baseLyrics==null?LyricsDocument.EMPTY:baseLyrics.document):LyricsDocument.parse(existing.original);
        if(document.cues.isEmpty()){toast("还没有可翻译的歌词");return;}
        toast("正在翻译歌词…");
        async(()->{
            try {
                String translated=LyricTranslation.run(url,key,document);
                onUi(()->{if(sameLyrics(target,item,snapshot)&&existing==lyricEdits)saveEdits(item,new LyricsEdits(existing.original,translated,existing.offset));});
            } catch(Exception e){onUi(()->{
                if(sameLyrics(target,item,snapshot))toast(e.getMessage()!=null&&e.getMessage().startsWith("翻译")?e.getMessage():"翻译失败，请检查服务地址和密钥");
            });}
        });
    }

    private void toggleFloatingLyrics() {
        if(prefs.bool("floating_lyrics",false)) {
            prefs.setBool("floating_lyrics",false);stopService(new Intent(this,FloatingLyricsService.class));toast("悬浮歌词已关闭");return;
        }
        if(!FloatingLyricsService.allowed(this)&&Build.VERSION.SDK_INT>=23) {
            try {startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:"+getPackageName())));}
            catch(RuntimeException e){toast("设备没有悬浮窗权限设置");}
            toast("允许悬浮窗后，再开启悬浮歌词");return;
        }
        prefs.setBool("floating_lyrics",true);startService(new Intent(this,FloatingLyricsService.class));toast("离开应用后显示悬浮歌词");
    }

    private void checkUpdate(boolean manual) {
        if(updateBusy){if(manual)toast("正在处理更新，请稍候");return;}
        if(manual&&pendingRelease!=null&&pendingApk!=null&&pendingApk.isFile()) {
            promptInstall(pendingApk);return;
        }
        updateBusy=true;if(manual)toast("正在检查更新…");
        UpdateManager.check(this,(release,error)->{
            if(destroyed)return;
            updateBusy=false;
            if(!error.isEmpty()){if(manual)toast(error);return;}
            prefs.store.edit().putLong("update_checked",System.currentTimeMillis()).apply();
            if(release==null){if(manual)toast("已是最新版本："+BuildConfig.VERSION_NAME);return;}
            if(manual)showUpdate(release);
            else {pendingRelease=release;toast("发现新版本 "+release.name+"，可在设置中更新");}
        });
    }
    private void showUpdate(UpdateManager.Release release) {
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("NoMusic "+release.name+" 可更新")
                .setMessage(bytes(release.size)+"\n\n"+(release.notes.isEmpty()?"下载后可通过系统安装器更新。":release.notes))
                .setNegativeButton("稍后",null).setPositiveButton("下载更新",(d,i)->downloadUpdate(release)));
    }

    @SuppressWarnings("deprecation")
    private void downloadUpdate(UpdateManager.Release release) {
        if(updateBusy)return;
        updateBusy=true;
        android.app.ProgressDialog progress=new android.app.ProgressDialog(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK);
        progress.setTitle("正在下载更新");progress.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);progress.setCancelable(false);activeDialog=progress;progress.show();
        UpdateManager.download(this,release,percent->{if(!destroyed&&progress.isShowing()){if(percent<0){progress.setTitle("正在校验安装包");progress.setIndeterminate(true);progress.setMessage("请稍候，校验完成后可以安装");}else{progress.setProgress(percent);progress.setMessage(bytes(release.size*percent/100)+" / "+bytes(release.size));}}},(apk,error)->{
            if(destroyed)return;
            updateBusy=false;progress.dismiss();
            if(!error.isEmpty()){dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("更新未完成").setMessage(error).setNegativeButton("关闭",null).setPositiveButton("重试",(d,i)->downloadUpdate(release)));return;}
            pendingRelease=release;pendingApk=apk;promptInstall(apk);
        });
    }

    private void promptInstall(java.io.File apk) {
        dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("安装包已验证").setMessage("打开系统安装器更新 NoMusic。")
                .setNegativeButton("稍后",null).setPositiveButton("安装",(d,i)->installUpdate(apk)));
    }

    private void installUpdate(java.io.File apk) {
        if(UpdateManager.needsInstallerPermission(this)&&Build.VERSION.SDK_INT>=26) {
            try {awaitingInstallPermission=true;startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:"+getPackageName())));}
            catch(RuntimeException e){awaitingInstallPermission=false;toast("请在系统设置中允许安装应用");}
            return;
        }
        if(Build.VERSION.SDK_INT<24&&android.os.Environment.MEDIA_MOUNTED.equals(android.os.Environment.getExternalStorageState())&&
                checkCallingOrSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},81);
            else toast("系统需要允许 NoMusic 访问存储来安装更新");
            pendingApk=apk;return;
        }
        updateBusy=true;
        async(()->{
            try {
                Intent intent=UpdateManager.installer(this,apk);
                onUi(()->{updateBusy=false;try {startActivity(intent);}catch(RuntimeException e){toast("此设备未提供可用的系统安装器");}});
            } catch(Exception e){onUi(()->{updateBusy=false;toast("无法交给系统安装器，请检查外部存储");});}
        });
    }

    private java.io.File pendingApk;
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==81&&pendingApk!=null) {
            java.io.File apk=pendingApk;
            if(grants.length>0&&grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)installUpdate(apk);
            else toast("需要存储权限来打开旧版安装器");
        }
    }

    private void cancelLyrics() {
        if(lyricRequest!=null){lyricRequest.cancel();lyricRequest=null;}
    }

    private void hideKeyboard() { View focus=getCurrentFocus();if(focus!=null)((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        String command=MediaButtonReceiver.commandForKey(event.getKeyCode());
        if(command!=null) {
            if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)startService(new Intent(this,PlaybackService.class).setAction(command));
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        if(queueOverlay!=null){if(drawerClosing)closeDrawer();else dismissDrawer();return;}
        if(shell&&"player".equals(section)) {
            leavePlayer(()->{if(playerBack==null)navigate("home");else showPage(playerBack,playerBackList);});
        }
        else if(shell&&returningToPlayer)showLyrics();
        else if(shell&&!browseTrail.isEmpty()){BrowseReturn back=browseTrail.remove(browseTrail.size()-1);showPage(back.state,back.snapshot);}
        else if(shell&&"folders".equals(section)&&!folderTrail.isEmpty())showPage(folderTrail.remove(folderTrail.size()-1));
        else if(shell&&parentId!=null)navigate(section);
        else if(shell&&!query.isEmpty())navigate("songs");
        else if(shell&&!client.authenticated())showLogin();
        else if(shell&&!"home".equals(section))navigate("home");
        else moveTaskToBack(true);
    }

    @Override public void onDestroy() {
        closeDrawer();cancelLyrics();pendingPlayerExit=null;
        if(motion!=null)motion.finishAll();
        destroyed=true;handler.removeCallbacksAndMessages(null);io.shutdownNow();images.close();unregisterReceiver(changes);unregisterReceiver(appChanges);
        if(activeDialog!=null)activeDialog.dismiss();
        if(bound)unbindService(connection);super.onDestroy();
    }

    private final class LibraryAdapter extends BaseAdapter {
        @Override public int getCount(){return gridColumns>0?(items.size()+gridColumns-1)/gridColumns:items.size();}
        @Override public MediaItem getItem(int position){return items.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public View getView(int position,View recycled,ViewGroup parent) {
            if(gridColumns>0)return gridRow(position);
            Holder holder;
            if(recycled==null) {
                LinearLayout row=Ui.row(MainActivity.this);Ui.pad(row,0,8,0,8);row.setBackground(Ui.background(MainActivity.this,colors.BG,colors.SOFT));
                row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                holder=new Holder();holder.number=text("",16,colors.FAINT,false);holder.number.setGravity(Gravity.CENTER);row.addView(holder.number,new LinearLayout.LayoutParams(dp("queue".equals(section)?56:32),dp(56)));
                holder.image=new ArtView(MainActivity.this,8);holder.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(holder.image,new LinearLayout.LayoutParams(dp(52),dp(52)));
                LinearLayout labels=Ui.column(MainActivity.this);Ui.pad(labels,14,0,4,0);
                holder.title=text("",22,colors.TEXT,false);holder.title.setSingleLine(true);holder.title.setEllipsize(TextUtils.TruncateAt.END);labels.addView(holder.title);Ui.gap(labels,4);
                holder.detail=text("",18,colors.MUTED,false);holder.detail.setSingleLine(true);holder.detail.setEllipsize(TextUtils.TruncateAt.END);labels.addView(holder.detail);
                row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                holder.album=text("",18,colors.MUTED,false);holder.album.setSingleLine(true);holder.album.setEllipsize(TextUtils.TruncateAt.END);if(wide)row.addView(holder.album,new LinearLayout.LayoutParams(dp(192),dp(56)));
                holder.time=text("",16,colors.FAINT,false);holder.time.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);row.addView(holder.time,new LinearLayout.LayoutParams(dp(72),dp(56)));
                holder.options=new IconView(MainActivity.this,"more",colors.MUTED);holder.options.setPadding(dp(16),dp(16),dp(16),dp(16));
                holder.options.setBackground(Ui.background(MainActivity.this,android.graphics.Color.TRANSPARENT,colors.SOFT));holder.options.setFocusable(true);holder.options.setClickable(true);
                row.addView(holder.options,new LinearLayout.LayoutParams(dp(56),dp(56)));row.setTag(holder);row.setMinimumHeight(dp(72));recycled=row;
            } else holder=(Holder)recycled.getTag();
            MediaItem item=getItem(position);holder.item=item;holder.title.setText(item.name);
            boolean queuePage="queue".equals(section);
            boolean active=playback!=null&&playback.current()!=null&&(queuePage?position==playback.index():playback.current().id.equals(item.id));
            holder.title.setTextColor(active?colors.ACCENT:colors.TEXT);recycled.setBackground(Ui.background(MainActivity.this,active?colors.SOFT:colors.BG,colors.SOFT));recycled.setSelected(active);holder.title.setTypeface(android.graphics.Typeface.create("sans-serif",active?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL));
            holder.number.setText(queuePage?"≡":active?"♪":String.valueOf(position+1));holder.number.setTextColor(active?colors.ACCENT:colors.MUTED);
            holder.number.setContentDescription(queuePage?"拖动调整 "+item.name:null);
            holder.number.setOnTouchListener(queuePage?(v,event)->dragQueue(position,event):null);
            cacheLabels(holder);
            holder.time.setText(item.isAudio()?MediaItem.time(item.durationMs):"›");
            holder.options.setVisibility(item.isAudio()||"Playlist".equals(item.type)?View.VISIBLE:View.INVISIBLE);
            holder.options.setIcon("more",item.favorite?colors.ACCENT:colors.MUTED);
            holder.options.setContentDescription(item.name+" 的操作");
            holder.options.setOnClickListener(v->{if(item.isAudio())trackMenu(item,position,v);else playlistMenu(item.id,item.name);});
            images.load(client,item,holder.image);return recycled;
        }
    }

    private int dragRevision;
    private View dragIndicator;
    private boolean dragQueue(int position,MotionEvent event) {
        if(playback==null||list==null)return false;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            queueDrag=queueDrop=position;dragRevision=playback.queueRevision();list.requestDisallowInterceptTouchEvent(true);
        } else if(action==MotionEvent.ACTION_MOVE&&queueDrag>=0) {
            int[] origin=new int[2];list.getLocationOnScreen(origin);
            int y=(int)event.getRawY()-origin[1],x=(int)event.getRawX()-origin[0];
            if(y<dp(24))list.smoothScrollBy(-dp(54),110);
            else if(y>list.getHeight()-dp(24))list.smoothScrollBy(dp(54),110);
            int target=list.pointToPosition(Math.max(1,Math.min(list.getWidth()-1,x)),Math.max(1,Math.min(list.getHeight()-1,y)));
            if(target>=0&&target<items.size()) {
                queueDrop=target;
                if(dragIndicator!=null)dragIndicator.setSelected(false);
                dragIndicator=list.getChildAt(target-list.getFirstVisiblePosition());
                if(dragIndicator!=null)dragIndicator.setSelected(true);
            }
        } else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL) {
            if(dragIndicator!=null)dragIndicator.setSelected(false);dragIndicator=null;
            int from=queueDrag,to=queueDrop;queueDrag=queueDrop=-1;list.requestDisallowInterceptTouchEvent(false);
            if(action==MotionEvent.ACTION_UP&&from>=0&&to>=0&&from!=to)moveQueue(from,to,dragRevision);
            else showQueue();
        }
        return true;
    }


    private View gridRow(int rowIndex) {
        LinearLayout row=Ui.row(this);row.setGravity(Gravity.TOP);Ui.pad(row,0,0,0,24);
        int rail=screenWidth<900?80:wide?192:160;int size=Math.max(80,(screenWidth-rail-48-(gridColumns-1)*16)/gridColumns);
        for(int column=0;column<gridColumns;column++){
            int at=rowIndex*gridColumns+column;
            if(at>=items.size()){row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));continue;}
            MediaItem item=items.get(at);LinearLayout tile=Ui.column(this);tile.setBackground(Ui.background(this,colors.BG,colors.SOFT));
            ImageView image=new ArtView(this,"MusicArtist".equals(item.type)?100:12);tile.addView(image,new LinearLayout.LayoutParams(-1,dp(size)));images.load(client,item,image);Ui.gap(tile,12);
            TextView title=text(item.name,20,colors.TEXT,true);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);tile.addView(title);
            TextView detail=text("MusicArtist".equals(item.type)?"歌手":"Playlist".equals(item.type)?"歌单 · 长按管理":item.artist,16,colors.MUTED,false);detail.setSingleLine(true);detail.setEllipsize(TextUtils.TruncateAt.END);tile.addView(detail);
            tile.setFocusable(true);tile.setClickable(true);tile.setContentDescription(item.name+("Playlist".equals(item.type)?"，歌单，长按管理":"，打开详情"));tile.setOnClickListener(v->openFolder(item));
            if("Playlist".equals(item.type))tile.setOnLongClickListener(v->{playlistMenu(item.id,item.name);return true;});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(column<gridColumns-1)lp.rightMargin=dp(16);row.addView(tile,lp);
        }
        return row;
    }

    private void openDrawer() {
        if(queueOverlay!=null){
            if(drawerClosing){drawerClosing=false;drawerList.setEnabled(true);motion.drawerOpen(queueOverlay,drawerPanel);drawerList.requestFocus();}
            else dismissDrawer();
            return;
        }
        if(playback==null)return;
        drawerClosing=false;drawerRevision=-1;drawerIndex=-2;queueOverlay=new FrameLayout(this);queueOverlay.setId(R.id.queue_panel);queueOverlay.setBackgroundColor(0x60000000);queueOverlay.setOnClickListener(v->dismissDrawer());
        LinearLayout panel=Ui.column(this);drawerPanel=panel;Ui.pad(panel,16,8,16,8);panel.setBackground(Ui.surface(this,24));panel.setClickable(true);
        LinearLayout header=Ui.row(this);drawerSummary=text("播放队列",20,colors.TEXT,true);drawerSummary.setMaxLines(2);header.addView(drawerSummary,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(iconButton("more","队列管理",v->drawerMenu()),new LinearLayout.LayoutParams(dp(56),dp(56)));header.addView(iconButton("close","关闭播放队列",v->dismissDrawer()),new LinearLayout.LayoutParams(dp(56),dp(56)));panel.addView(header,new LinearLayout.LayoutParams(-1,dp(64)));
        drawerList=new ListView(this);drawerList.setId(R.id.queue_list);drawerList.setDivider(null);drawerList.setCacheColorHint(colors.SURFACE);drawerList.setSelector(Ui.background(this,colors.SURFACE,colors.SOFT));drawerAdapter=new QueueAdapter();drawerList.setAdapter(drawerAdapter);panel.addView(drawerList,new LinearLayout.LayoutParams(-1,0,1));
        TextView noTracks=text("队列为空 · 前往曲库选择歌曲",18,colors.MUTED,false);noTracks.setGravity(Gravity.CENTER);noTracks.setMinHeight(dp(80));noTracks.setFocusable(true);noTracks.setOnClickListener(v->{closeDrawer();navigate("songs");});panel.addView(noTracks);drawerList.setEmptyView(noTracks);
        drawerList.setOnItemClickListener((p,v,at,id)->{if(!drawerClosing&&at<drawerItems.size())playback.select(at);});drawerList.setOnItemLongClickListener((p,v,at,id)->{drawerTrackMenu(at,null);return true;});
        FrameLayout.LayoutParams drawer=new FrameLayout.LayoutParams(dp(Math.min(520,screenWidth-48)),-1,Gravity.RIGHT);drawer.rightMargin=dp(24);queueOverlay.addView(panel,drawer);
        FrameLayout.LayoutParams overlay=new FrameLayout.LayoutParams(-1,-1);overlay.topMargin=dp("player".equals(section)?88:88);overlay.bottomMargin=dp("player".equals(section)?152:compactPane.getVisibility()==View.VISIBLE?136:24);shellFrame.addView(queueOverlay,overlay);
        collectDrawerFocus(shellFrame);refreshDrawer();drawerList.requestFocus();
        motion.drawerOpen(queueOverlay,panel);
    }
    private void collectDrawerFocus(View view) {
        if(view==queueOverlay||view==playerPane)return;
        if(view.isFocusable()){drawerFocus.add(view);view.setFocusable(false);}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collectDrawerFocus(group.getChildAt(i));}
    }
    private void closeDrawer() {
        if(queueOverlay==null)return;motion.finishTree(queueOverlay);shellFrame.removeView(queueOverlay);queueOverlay=null;drawerPanel=null;drawerClosing=false;drawerList=null;drawerAdapter=null;drawerDrag=-1;
        for(View view:drawerFocus)view.setFocusable(true);drawerFocus.clear();if(queueButton!=null)queueButton.requestFocus();
    }

    private void cacheText(TextView view,String metadata,boolean cached,String badge){
        String check=cached?"✓  ":"",quality=badge.isEmpty()?"":badge+"  ";
        String value=check+quality+metadata;
        if(TextUtils.equals(view.getText(),value))return;
        if(cached||!badge.isEmpty()){
            android.text.SpannableString label=new android.text.SpannableString(value);int flags=android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            if(cached)label.setSpan(new android.text.style.ForegroundColorSpan(colors.light?0xff247a42:0xff6ad98e),0,1,flags);
            if(!badge.isEmpty()){
                label.setSpan(new android.text.style.ForegroundColorSpan(colors.ACCENT),check.length(),check.length()+badge.length(),flags);
                label.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),check.length(),check.length()+badge.length(),flags);
            }
            view.setText(label);
        }else view.setText(value);
        view.setContentDescription(cached||!badge.isEmpty()?(cached?"已缓存，":"")+(badge.isEmpty()?"":"原文件音质 "+badge+"，")+metadata:null);
    }
    private void cacheLabels(Holder holder){
        MediaItem item=holder.item;int progress=item.isAudio()?audioCache.progress(client,item):-1;
        String detail=item.isAudio()?item.artist+(!wide&&!item.album.isEmpty()?" · "+item.album:""):
                "MusicAlbum".equals(item.type)?item.artist+" · 专辑":"MusicArtist".equals(item.type)?"歌手 · 查看歌曲":
                "Playlist".equals(item.type)?"歌单 · 查看歌曲":"MusicGenre".equals(item.type)?"曲风 · 查看歌曲":"文件夹";
        if(item.isAudio()&&"downloads".equals(section)){
            if(!audioCache.failure(client,item).isEmpty())detail+=" · 下载失败";
            else if(progress>=0)detail+=" · 下载中 "+(progress>0?progress+"%":"…");
        }
        boolean cached=audioCache.cached(client,item);
        String badge=item.sourceAudio.badge;
        cacheText(holder.detail,detail,cached,badge);
        if(wide)holder.album.setText(item.album);
    }
    private void refreshCacheLabel(CacheLabel label){cacheText(label.view,label.text,audioCache.cached(client,label.item),label.item.sourceAudio.badge);}
    private void refreshVisibleCache(){
        if(list!=null&&adapter!=null)for(int at=0;at<list.getChildCount();at++){Object tag=list.getChildAt(at).getTag();if(tag instanceof Holder)cacheLabels((Holder)tag);}
        if(drawerList!=null)for(int at=0;at<drawerList.getChildCount();at++){Object tag=drawerList.getChildAt(at).getTag();if(tag instanceof CacheLabel)refreshCacheLabel((CacheLabel)tag);}
        if("home".equals(section))for(CacheLabel label:homeCacheLabels)refreshCacheLabel(label);
    }
    private void dismissDrawer(){
        if(queueOverlay==null||drawerClosing)return;
        if(!motion.enabled()){closeDrawer();return;}
        drawerClosing=true;drawerDrag=-1;drawerList.setEnabled(false);
        FrameLayout closing=queueOverlay;
        motion.drawerClose(closing,drawerPanel,()->{if(queueOverlay==closing&&drawerClosing)closeDrawer();});
    }
    private void refreshDrawer() {
        if(queueOverlay==null||playback==null||drawerDrag>=0)return;
        if(drawerRevision!=playback.queueRevision()||drawerIndex!=playback.index()){
            drawerRevision=playback.queueRevision();drawerIndex=playback.index();drawerItems.clear();drawerItems.addAll(playback.queue());drawerAdapter.notifyDataSetChanged();
            drawerSummary.setText("播放队列 · "+drawerItems.size()+" 首"+(playback.hasMore()?"\n将继续加载":""));
        }
    }
    private void drawerMenu() {
        if(drawerClosing)return;
        LinkedHashMap<String,Runnable> choices=new LinkedHashMap<>();choices.put("展开完整队列",()->{closeDrawer();navigate("queue");});
        choices.put("定时停止",this::sleepMenu);choices.put("清空播放队列",()->dialog(new AlertDialog.Builder(this,colors.light?AlertDialog.THEME_HOLO_LIGHT:AlertDialog.THEME_HOLO_DARK).setTitle("清空播放队列？").setMessage("播放会停止。").setNegativeButton("取消",null).setPositiveButton("清空",(d,i)->{if(playback!=null)playback.stopPlayback(true);refreshDrawer();})));actions("队列管理",choices);
    }
    private void drawerTrackMenu(int at,View anchor) {
        if(drawerClosing||at<0||at>=drawerItems.size()||playback==null)return;MediaItem item=drawerItems.get(at);int revision=playback.queueRevision();
        LinkedHashMap<String,Runnable> choices=new LinkedHashMap<>();if(at>0)choices.put("上移",()->moveQueue(at,at-1,revision));if(at+1<drawerItems.size())choices.put("下移",()->moveQueue(at,at+1,revision));choices.put("从队列移除",()->{if(validQueue(revision))playback.remove(at);});choices.put("歌曲操作",()->trackMenu(item,-1,null));choices.put("歌曲信息",()->{closeDrawer();songInfo(item);});actions(item.name,choices);
    }
    private boolean dragDrawer(int at,MotionEvent event) {
        if(drawerClosing)return true;
        if(playback==null||drawerList==null)return false;int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){drawerDrag=drawerDrop=at;drawerDragRevision=playback.queueRevision();drawerList.requestDisallowInterceptTouchEvent(true);}
        else if(action==MotionEvent.ACTION_MOVE&&drawerDrag>=0){int[] origin=new int[2];drawerList.getLocationOnScreen(origin);int y=(int)event.getRawY()-origin[1];if(y<dp(24))drawerList.smoothScrollBy(-dp(54),110);else if(y>drawerList.getHeight()-dp(24))drawerList.smoothScrollBy(dp(54),110);int target=drawerList.pointToPosition(dp(60),Math.max(1,Math.min(drawerList.getHeight()-1,y)));if(target>=0&&target<drawerItems.size())drawerDrop=target;}
        else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){int from=drawerDrag,to=drawerDrop;drawerDrag=-1;drawerList.requestDisallowInterceptTouchEvent(false);if(action==MotionEvent.ACTION_UP&&from>=0&&to>=0&&from!=to)moveQueue(from,to,drawerDragRevision);refreshDrawer();}return true;
    }
    private final class QueueAdapter extends BaseAdapter {
        @Override public int getCount(){return drawerItems.size();}
        @Override public Object getItem(int at){return drawerItems.get(at);}
        @Override public long getItemId(int at){return at;}
        @Override public View getView(int at,View recycled,ViewGroup parent){
            MediaItem item=drawerItems.get(at);boolean current=playback!=null&&at==playback.index();LinearLayout row=Ui.row(MainActivity.this);row.setMinimumHeight(dp(80));row.setBackground(Ui.background(MainActivity.this,current?colors.SOFT:colors.SURFACE,colors.SOFT));
            TextView handle=text("≡",24,colors.MUTED,false);handle.setGravity(Gravity.CENTER);handle.setContentDescription("拖动调整 "+item.name);handle.setOnTouchListener((v,e)->dragDrawer(at,e));row.addView(handle,new LinearLayout.LayoutParams(dp(56),dp(64)));
            LinearLayout labels=Ui.column(MainActivity.this);TextView title=text((current?"♪ ":"")+item.name,20,current?colors.ACCENT:colors.TEXT,current);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);labels.addView(title);TextView artist=text(item.artist,16,colors.MUTED,false);artist.setSingleLine(true);artist.setEllipsize(TextUtils.TruncateAt.END);labels.addView(artist);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            CacheLabel label=new CacheLabel(item,artist,item.artist+(!item.album.isEmpty()?" · "+item.album:""));refreshCacheLabel(label);row.setTag(label);
            row.addView(iconButton("more",item.name+" 的队列操作",v->drawerTrackMenu(at,v)),new LinearLayout.LayoutParams(dp(56),dp(56)));return row;
        }
    }

    private static final class CacheLabel {final MediaItem item;final TextView view;final String text;CacheLabel(MediaItem item,TextView view,String text){this.item=item;this.view=view;this.text=text;}}
    private static final class Holder { MediaItem item;ImageView image;TextView title,detail,time,album,number;IconView options; }
}
