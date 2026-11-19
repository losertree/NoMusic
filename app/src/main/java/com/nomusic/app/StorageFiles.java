package com.nomusic.app;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;

final class StorageFiles {
    static String key(String text) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }

    static String hex(byte[] bytes) {
        StringBuilder result=new StringBuilder(bytes.length*2);
        for(byte value:bytes)result.append(Character.forDigit((value>>4)&15,16)).append(Character.forDigit(value&15,16));
        return result.toString();
    }

    static JSONObject readJson(File file,int limit) throws Exception {
        try(FileInputStream input=new FileInputStream(file)) {
            return new JSONObject(new String(read(input,limit),"UTF-8"));
        }
    }

    static byte[] read(java.io.InputStream input,int limit) throws IOException {
        ByteArrayOutputStream result=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1) {
            if(result.size()+n>limit)throw new IOException("Response is too large");
            result.write(buffer,0,n);
        }
        return result.toByteArray();
    }

    static void writeJson(File file,JSONObject value) throws IOException {
        File dir=file.getParentFile();
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create directory");
        File part=new File(file.getPath()+".tmp");
        try {
            try(FileOutputStream output=new FileOutputStream(part)) {
                output.write(value.toString().getBytes("UTF-8"));output.getFD().sync();
            }
            if(!part.renameTo(file))throw new IOException("Cannot replace file");
        } finally { part.delete(); }
    }
}
