package com.nomusic.app;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** TLS, whole-file digest, Android package identity, version and signing certificate are all required. */
final class UpdateManager {
    static final String BASE="https://nomusic.losertree.de", MANIFEST=BASE+"/updates/latest.json";
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final long MAX_APK=100L*1024*1024;
    interface Checked { void done(Release release,String error); }
    interface Downloaded { void done(File apk,String error); }
    interface Progress { void changed(int percent); }

    static final class Release {
        final int code,minSdk;
        final long size;
        final String name,url,sha256,notes,packageName;
        Release(String packageName,JSONObject json) throws Exception {
            this.packageName=packageName;code=json.getInt("versionCode");name=json.getString("versionName");
            minSdk=json.getInt("minSdk");url=json.getString("url");sha256=json.getString("sha256");
            size=json.getLong("size");notes=json.optString("notes");
            Uri uri=Uri.parse(url);
            if(code<1||minSdk<16||!sha256.matches("[a-f0-9]{64}")||size<1||size>MAX_APK||
                    !url.startsWith(BASE+"/downloads/")||!BASE.equals(uri.getScheme()+"://"+uri.getAuthority())||
                    uri.getUserInfo()!=null||uri.getFragment()!=null||name.length()>80||notes.length()>8192)
                throw new IOException("更新清单无效");
        }
    }

    static void check(android.content.Context context,Checked callback) {
        android.content.Context app=context.getApplicationContext();
        IO.execute(()->{
            Release release=null;String error="";
            HttpURLConnection connection=null;
            try {
                connection=open(MANIFEST);
                if(connection.getResponseCode()!=200)throw new IOException("更新服务暂时不可用");
                JSONObject manifest;
                try(InputStream input=connection.getInputStream()) {
                    manifest=new JSONObject(new String(StorageFiles.read(input,64*1024),"UTF-8"));
                }
                if(manifest.optInt("schemaVersion")!=1)throw new IOException("更新清单版本不兼容");
                release=new Release(app.getPackageName(),manifest.getJSONObject("packages").getJSONObject(app.getPackageName()));
                if(release.minSdk>Build.VERSION.SDK_INT)throw new IOException("新版本不支持当前安卓版本");
                if(release.code<=app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionCode)release=null;
            } catch(Exception e) {error=message(e);}
            finally {if(connection!=null)connection.disconnect();}
            Release result=release;String problem=error;MAIN.post(()->callback.done(result,problem));
        });
    }

    static void download(android.content.Context context,Release release,Progress progress,Downloaded callback) {
        android.content.Context app=context.getApplicationContext();
        IO.execute(()->{
            File dir=new File(app.getFilesDir(),"updates"),part=new File(dir,"download.apk"),apk=new File(dir,"update.apk");
            String error="";
            HttpURLConnection connection=null;
            try {
                if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法写入安装包");
                apk.delete();
                connection=open(release.url);
                if(connection.getResponseCode()!=200)throw new IOException("安装包下载失败");
                String length=connection.getHeaderField("Content-Length");
                if(length!=null&&Long.parseLong(length)!=release.size)throw new IOException("安装包大小不符");
                MessageDigest hash=MessageDigest.getInstance("SHA-256");long total=0;int previous=-1;
                try(InputStream input=connection.getInputStream();FileOutputStream output=new FileOutputStream(part)) {
                    byte[] buffer=new byte[32768];int n;
                    while(total<release.size) {
                        n=input.read(buffer,0,(int)Math.min(buffer.length,release.size-total));
                        if(n==-1)break;
                        total+=n;
                        if(total>release.size)throw new IOException("安装包大小不符");
                        output.write(buffer,0,n);hash.update(buffer,0,n);
                        int percent=(int)(total*100/release.size);
                        if(percent/5!=previous/5){previous=percent;MAIN.post(()->progress.changed(percent));}
                    }
                    if(total!=release.size||length==null&&input.read()!=-1||!StorageFiles.hex(hash.digest()).equals(release.sha256))
                        throw new IOException("安装包完整性校验失败，请重新下载");
                    output.getFD().sync();
                }
                MAIN.post(()->progress.changed(-1));
                validate(app,part,release);
                if(!part.renameTo(apk))throw new IOException("无法保存已验证的安装包");
            } catch(Exception e) {error=message(e);apk.delete();}
            finally {part.delete();if(connection!=null)connection.disconnect();}
            String problem=error;MAIN.post(()->callback.done(problem.isEmpty()?apk:null,problem));
        });
    }

    @SuppressWarnings("deprecation")
    static void validate(android.content.Context context,File apk,Release release) throws Exception {
        PackageManager pm=context.getPackageManager();
        PackageInfo candidate=pm.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNATURES);
        PackageInfo installed=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNATURES);
        if(candidate==null||!release.packageName.equals(candidate.packageName)||!installed.packageName.equals(candidate.packageName)||
                candidate.versionCode!=release.code||candidate.versionCode<=installed.versionCode)
            throw new IOException("安装包与当前版本或软件包不匹配");
        List<String> expected=signatures(installed.signatures),actual=signatures(candidate.signatures);
        if(expected.isEmpty()||!expected.equals(actual))throw new IOException("安装包签名不匹配，已拒绝安装");
    }

    private static List<String> signatures(Signature[] signatures) throws Exception {
        List<String> hashes=new ArrayList<>();
        if(signatures!=null)for(Signature signature:signatures)
            hashes.add(StorageFiles.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        Collections.sort(hashes);return hashes;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(url).openConnection();
        connection.setSSLSocketFactory(TlsSocketFactory.get());connection.setConnectTimeout(12000);connection.setReadTimeout(25000);
        connection.setInstanceFollowRedirects(false);connection.setRequestProperty("Accept-Encoding","identity");
        connection.setRequestProperty("Connection","close");connection.setUseCaches(false);
        return connection;
    }

    @SuppressWarnings("deprecation")
    static Intent installer(android.content.Context context,File file) throws Exception {
        Uri uri;
        if(Build.VERSION.SDK_INT<24) {
            File dir=Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())?context.getExternalFilesDir(null):null;
            if(dir==null)dir=context.getFilesDir();
            boolean privateDir=dir.equals(context.getFilesDir());
            String name="NoMusic-"+java.util.UUID.randomUUID()+".apk";
            File legacy=new File(dir,name);
            boolean complete=false;
            try {
                // Pre-N PackageInstaller only accepts file URIs. Its UID needs read access
                // to this signed public APK; every account, token and download stays private.
                try(FileInputStream input=new FileInputStream(file);FileOutputStream output=privateDir?
                        context.openFileOutput(name,android.content.Context.MODE_WORLD_READABLE):new FileOutputStream(legacy)) {
                    byte[] buffer=new byte[32768];int n;
                    while((n=input.read(buffer))!=-1)output.write(buffer,0,n);
                    output.getFD().sync();
                }
                complete=true;
            } finally {if(!complete)legacy.delete();}
            File[] obsolete=dir.listFiles((d,n)->n.startsWith("NoMusic-")&&n.endsWith(".apk")&&!n.equals(legacy.getName()));
            if(obsolete!=null)for(File old:obsolete)old.delete();
            uri=Uri.fromFile(legacy);
        } else uri=new Uri.Builder().scheme("content").authority(context.getPackageName()+".updates").appendPath("update.apk").build();
        return new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    static boolean needsInstallerPermission(android.content.Context context) {
        return Build.VERSION.SDK_INT>=26&&!context.getPackageManager().canRequestPackageInstalls();
    }

    private static String message(Exception e) {
        if(e instanceof javax.net.ssl.SSLException)return "更新服务器 HTTPS 校验失败，请检查设备时间";
        if(e instanceof IOException&&e.getMessage()!=null&&e.getMessage().matches(".*[\u4e00-\u9fff].*"))return e.getMessage();
        return "无法连接更新服务器，请稍后重试";
    }
}
