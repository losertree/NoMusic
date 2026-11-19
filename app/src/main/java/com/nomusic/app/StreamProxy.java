package com.nomusic.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Old MediaPlayer HTTPS stacks cannot reliably negotiate modern TLS. Relay over
 * loopback using our certificate-validated Java HTTPS connection.
 * Each instance is scoped to one track and accepts only its random local path.
 */
final class StreamProxy {
    interface Failure { void failed(int status); }
    private final ServerSocket server;
    private final String localPath = "/" + UUID.randomUUID();
    private final EmbyClient client;
    private final String remotePath;
    private final Failure failure;
    private final AudioCache cache;
    private final MediaItem item;
    private final AudioQuality quality;
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<Socket>());
    private final Set<HttpURLConnection> connections = Collections.synchronizedSet(new HashSet<HttpURLConnection>());
    private volatile boolean closed;

    StreamProxy(EmbyClient client, String remotePath) throws IOException {
        this(client,remotePath,null,null,AudioQuality.BALANCED,null);
    }

    StreamProxy(EmbyClient client,String remotePath,AudioCache cache,MediaItem item,AudioQuality quality,Failure failure) throws IOException {
        this.client = client;
        this.remotePath = remotePath;
        this.cache=cache;this.item=item;this.quality=quality;this.failure=failure;
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(() -> {
            while (!closed) {
                Socket accepted = null;
                try {
                    accepted = server.accept();
                    if (closed) { accepted.close(); break; }
                    sockets.add(accepted);
                    final Socket socket = accepted;
                    workers.execute(() -> relay(socket));
                } catch (IOException | java.util.concurrent.RejectedExecutionException ignored) {
                    if (accepted != null) {
                        sockets.remove(accepted);
                        try { accepted.close(); } catch (IOException ignoredClose) { }
                    }
                    if (!closed) close();
                }
            }
        }, "nomusic-stream");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String url() { return "http://127.0.0.1:" + server.getLocalPort() + localPath; }

    private void relay(Socket socket) {
        HttpURLConnection connection = null;
        AudioCache.Capture capture=null;
        boolean eof=false;
        int upstream=0;
        boolean fetching=false;
        try {
            socket.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
            String line = reader.readLine();
            OutputStream output = socket.getOutputStream();
            if (line == null || !(line.startsWith("GET " + localPath + " ") || line.startsWith("HEAD " + localPath + " "))) {
                output.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes("US-ASCII"));
                return;
            }
            boolean head = line.startsWith("HEAD ");
            String range = null;
            int headerLength = line.length();
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                headerLength += line.length();
                if (headerLength > 16384) return;
                if (line.regionMatches(true, 0, "Range:", 0, 6)) range = line.substring(6).trim();
            }
            connection = client.open(remotePath);
            connections.add(connection);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept-Encoding","identity");
            connection.setRequestProperty("Connection","close");
            if (range != null) connection.setRequestProperty("Range", range);
            if (head) connection.setRequestMethod("HEAD");
            fetching=true;
            int status = client.response(connection);
            upstream=status;
            if(!closed&&failure!=null&&!head&&status!=416)failure.failed(status);
            StringBuilder headers = new StringBuilder("HTTP/1.0 ").append(status).append(" ")
                    .append(status == 206 ? "Partial Content" : status == 200 ? "OK" : "Upstream Error").append("\r\n");
            for (String name : new String[]{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"}) {
                String value = connection.getHeaderField(name);
                if (value != null && !value.contains("\r") && !value.contains("\n")) headers.append(name).append(": ").append(value).append("\r\n");
            }
            headers.append("Connection: close\r\n\r\n");
            output.write(headers.toString().getBytes("US-ASCII"));
            if (head || status < 200 || status >= 300) return;
            if(cache!=null)capture=cache.capture(client,item,quality,connection,status);
            InputStream input = connection.getInputStream();
            try {
                byte[] buffer = new byte[32768];
                while(!closed) {
                    int count;
                    try { count=input.read(buffer); }
                    catch(IOException e) { if(!closed&&failure!=null)failure.failed(0);throw e; }
                    if(count==-1)break;
                    if(capture!=null)capture.write(buffer,count);
                    output.write(buffer, 0, count);
                }
                eof=!closed;
                output.flush();
            } finally { input.close(); }
        } catch (Exception ignored) {
            // MediaPlayer surfaces a disconnected stream; never log a token-bearing URL.
            if(!closed&&failure!=null&&fetching&&upstream==0)failure.failed(0);
        } finally {
            if(capture!=null)capture.close(eof);
            if (connection != null) { connections.remove(connection); connection.disconnect(); }
            sockets.remove(socket);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        workers.shutdownNow();
        // TLS close sends a close_notify record and can wait on network I/O.
        // Never disconnect an HTTPS connection on the Android main thread.
        Thread cleanup = new Thread(() -> {
            try { server.close(); } catch (IOException ignored) { }
            Socket[] activeSockets;
            synchronized (sockets) { activeSockets = sockets.toArray(new Socket[0]); }
            for (Socket socket : activeSockets) try { socket.close(); } catch (IOException ignored) { }
            HttpURLConnection[] activeConnections;
            synchronized (connections) { activeConnections = connections.toArray(new HttpURLConnection[0]); }
            for (HttpURLConnection connection : activeConnections) connection.disconnect();
        }, "nomusic-stream-close");
        cleanup.setDaemon(true);
        cleanup.start();
    }
}
