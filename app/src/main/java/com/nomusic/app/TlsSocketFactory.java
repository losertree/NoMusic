package com.nomusic.app;

import android.content.Context;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.conscrypt.Conscrypt;

/** Enable supported TLS 1.2 on KitKat/Jelly Bean, preserving system certificate checks. */
final class TlsSocketFactory extends SSLSocketFactory {
    private static SSLSocketFactory verifiedFactory;
    private static TlsSocketFactory sharedFactory;
    private final SSLSocketFactory delegate;

    static synchronized void initialize(Context app) throws Exception {
        if(verifiedFactory!=null) return;
        TrustManagerFactory system=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        system.init((KeyStore)null);
        KeyStore roots=KeyStore.getInstance(KeyStore.getDefaultType());
        roots.load(null,null);
        CertificateFactory certificates=CertificateFactory.getInstance("X.509");
        int[] resources={R.raw.isrg_root_x1,R.raw.isrg_root_x2};
        for(int i=0;i<resources.length;i++) {
            InputStream stream=app.getResources().openRawResource(resources[i]);
            try { roots.setCertificateEntry("isrg-"+i,certificates.generateCertificate(stream)); }
            finally { stream.close(); }
        }
        TrustManagerFactory extra=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());extra.init(roots);
        final X509TrustManager normal=find(system),additional=find(extra);
        X509TrustManager trust=new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain,String auth) throws CertificateException { normal.checkClientTrusted(chain,auth); }
            @Override public void checkServerTrusted(X509Certificate[] chain,String auth) throws CertificateException {
                try { normal.checkServerTrusted(chain,auth); }
                catch(CertificateException original) { additional.checkServerTrusted(chain,auth); }
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] first=normal.getAcceptedIssuers(),second=additional.getAcceptedIssuers();
                X509Certificate[] result=new X509Certificate[first.length+second.length];
                System.arraycopy(first,0,result,0,first.length);System.arraycopy(second,0,result,first.length,second.length);return result;
            }
        };
        SSLContext tls=SSLContext.getInstance("TLS",Conscrypt.newProvider());
        tls.init(null,new TrustManager[]{trust},null);verifiedFactory=tls.getSocketFactory();
    }

    private static X509TrustManager find(TrustManagerFactory factory) throws CertificateException {
        for(TrustManager manager:factory.getTrustManagers())if(manager instanceof X509TrustManager)return (X509TrustManager)manager;
        throw new CertificateException("No X509 trust manager available");
    }

    // Android 4.1 keys its HTTPS pools by factory identity and retains those pools.
    // A factory per request strands one TLS socket and its two wakeup-pipe FDs.
    static synchronized TlsSocketFactory get() throws IOException {
        if(sharedFactory==null)sharedFactory=new TlsSocketFactory();
        return sharedFactory;
    }

    private TlsSocketFactory() throws IOException {
        if(verifiedFactory==null) throw new IOException("TLS provider was not initialized");
        delegate = verifiedFactory;
    }

    private Socket configure(Socket socket) throws IOException {
        if (socket instanceof SSLSocket) {
            SSLSocket ssl = (SSLSocket) socket;
            List<String> protocols = new ArrayList<>();
            for (String protocol : ssl.getSupportedProtocols()) {
                if ("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol)) protocols.add(protocol);
            }
            try {
                if(protocols.isEmpty())throw new IOException("No supported TLS protocol");
                return new TlsSocket(ssl,protocols.toArray(new String[0]));
            } catch(IOException | RuntimeException failure) {
                try {ssl.close();}catch(IOException ignored){}
                throw failure;
            }
        }
        return socket;
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket() throws IOException { return configure(delegate.createSocket()); }
    @Override public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException { return configure(delegate.createSocket(s,h,p,a)); }
    @Override public Socket createSocket(String h, int p) throws IOException { return configure(delegate.createSocket(h,p)); }
    @Override public Socket createSocket(String h,int p,InetAddress l,int lp) throws IOException { return configure(delegate.createSocket(h,p,l,lp)); }
    @Override public Socket createSocket(InetAddress h,int p) throws IOException { return configure(delegate.createSocket(h,p)); }
    @Override public Socket createSocket(InetAddress h,int p,InetAddress l,int lp) throws IOException { return configure(delegate.createSocket(h,p,l,lp)); }
}
