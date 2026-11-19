package com.nomusic.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/** Old Android's HTTP retry code requests SSLv3; never let it disable every modern TLS protocol. */
final class TlsSocket extends SSLSocket {
    private final SSLSocket socket;
    private final String[] protocols;
    TlsSocket(SSLSocket socket,String[] protocols) throws IOException {
        this.socket=socket;this.protocols=protocols.clone();socket.setEnabledProtocols(protocols);
    }
    @Override public String[] getSupportedProtocols(){return protocols.clone();}
    @Override public String[] getEnabledProtocols(){return socket.getEnabledProtocols();}
    @Override public void setEnabledProtocols(String[] requested) {
        if(requested==null)throw new IllegalArgumentException("protocols == null");
        ArrayList<String> enabled=new ArrayList<>();
        for(String candidate:requested)for(String allowed:protocols)if(candidate.equals(allowed))enabled.add(allowed);
        socket.setEnabledProtocols(enabled.isEmpty()?protocols:enabled.toArray(new String[0]));
    }
    @Override public String[] getSupportedCipherSuites(){return socket.getSupportedCipherSuites();}
    @Override public String[] getEnabledCipherSuites(){return socket.getEnabledCipherSuites();}
    @Override public void setEnabledCipherSuites(String[] values){socket.setEnabledCipherSuites(values);}
    @Override public SSLSession getSession(){return socket.getSession();}
    @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener){socket.addHandshakeCompletedListener(listener);}
    @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener){socket.removeHandshakeCompletedListener(listener);}
    @Override public void startHandshake() throws IOException{socket.startHandshake();}
    @Override public void setUseClientMode(boolean value){socket.setUseClientMode(value);}
    @Override public boolean getUseClientMode(){return socket.getUseClientMode();}
    @Override public void setNeedClientAuth(boolean value){socket.setNeedClientAuth(value);}
    @Override public boolean getNeedClientAuth(){return socket.getNeedClientAuth();}
    @Override public void setWantClientAuth(boolean value){socket.setWantClientAuth(value);}
    @Override public boolean getWantClientAuth(){return socket.getWantClientAuth();}
    @Override public void setEnableSessionCreation(boolean value){socket.setEnableSessionCreation(value);}
    @Override public boolean getEnableSessionCreation(){return socket.getEnableSessionCreation();}
    @Override public SSLParameters getSSLParameters(){return socket.getSSLParameters();}
    @Override public void setSSLParameters(SSLParameters parameters) {
        String[] requested=parameters.getProtocols();
        if(requested==null)parameters.setProtocols(protocols);
        else {setEnabledProtocols(requested);parameters.setProtocols(getEnabledProtocols());}
        socket.setSSLParameters(parameters);
    }
    @Override public void connect(SocketAddress address) throws IOException{socket.connect(address);}
    @Override public void connect(SocketAddress address,int timeout) throws IOException{socket.connect(address,timeout);}
    @Override public void bind(SocketAddress address) throws IOException{socket.bind(address);}
    @Override public InetAddress getInetAddress(){return socket.getInetAddress();}
    @Override public InetAddress getLocalAddress(){return socket.getLocalAddress();}
    @Override public int getPort(){return socket.getPort();}
    @Override public int getLocalPort(){return socket.getLocalPort();}
    @Override public SocketAddress getRemoteSocketAddress(){return socket.getRemoteSocketAddress();}
    @Override public SocketAddress getLocalSocketAddress(){return socket.getLocalSocketAddress();}
    @Override public SocketChannel getChannel(){return socket.getChannel();}
    @Override public InputStream getInputStream() throws IOException{return socket.getInputStream();}
    @Override public OutputStream getOutputStream() throws IOException{return socket.getOutputStream();}
    @Override public void setTcpNoDelay(boolean value) throws SocketException{socket.setTcpNoDelay(value);}
    @Override public boolean getTcpNoDelay() throws SocketException{return socket.getTcpNoDelay();}
    @Override public void setSoLinger(boolean on,int seconds) throws SocketException{socket.setSoLinger(on,seconds);}
    @Override public int getSoLinger() throws SocketException{return socket.getSoLinger();}
    @Override public void sendUrgentData(int data) throws IOException{socket.sendUrgentData(data);}
    @Override public void setOOBInline(boolean value) throws SocketException{socket.setOOBInline(value);}
    @Override public boolean getOOBInline() throws SocketException{return socket.getOOBInline();}
    @Override public void setSoTimeout(int value) throws SocketException{socket.setSoTimeout(value);}
    @Override public int getSoTimeout() throws SocketException{return socket.getSoTimeout();}
    @Override public void setSendBufferSize(int value) throws SocketException{socket.setSendBufferSize(value);}
    @Override public int getSendBufferSize() throws SocketException{return socket.getSendBufferSize();}
    @Override public void setReceiveBufferSize(int value) throws SocketException{socket.setReceiveBufferSize(value);}
    @Override public int getReceiveBufferSize() throws SocketException{return socket.getReceiveBufferSize();}
    @Override public void setKeepAlive(boolean value) throws SocketException{socket.setKeepAlive(value);}
    @Override public boolean getKeepAlive() throws SocketException{return socket.getKeepAlive();}
    @Override public void setTrafficClass(int value) throws SocketException{socket.setTrafficClass(value);}
    @Override public int getTrafficClass() throws SocketException{return socket.getTrafficClass();}
    @Override public void setReuseAddress(boolean value) throws SocketException{socket.setReuseAddress(value);}
    @Override public boolean getReuseAddress() throws SocketException{return socket.getReuseAddress();}
    @Override public void close() throws IOException{socket.close();}
    @Override public void shutdownInput() throws IOException{socket.shutdownInput();}
    @Override public void shutdownOutput() throws IOException{socket.shutdownOutput();}
    @Override public boolean isConnected(){return socket.isConnected();}
    @Override public boolean isBound(){return socket.isBound();}
    @Override public boolean isClosed(){return socket.isClosed();}
    @Override public boolean isInputShutdown(){return socket.isInputShutdown();}
    @Override public boolean isOutputShutdown(){return socket.isOutputShutdown();}
    @Override public void setPerformancePreferences(int connection,int latency,int bandwidth){socket.setPerformancePreferences(connection,latency,bandwidth);}
}
