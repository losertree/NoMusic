package com.nomusic.app;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded retries for reads only. Authentication and write requests are never replayed. */
final class RetryPolicy {
    interface Call<T> { T run() throws Exception; }
    interface Check { void run() throws IOException; }
    /** Disconnect off the main thread: old TLS stacks can block while closing a socket. */
    static final class Token implements Check {
        private static final ExecutorService closes=Executors.newSingleThreadExecutor();
        private volatile boolean cancelled;
        private HttpURLConnection connection;
        @Override public void run()throws IOException {interrupted();if(cancelled)throw new InterruptedIOException("Cancelled");}
        synchronized void attach(HttpURLConnection value)throws IOException {run();connection=value;}
        synchronized void detach(HttpURLConnection value){if(connection==value)connection=null;}
        synchronized void cancel(){cancelled=true;HttpURLConnection value=connection;connection=null;if(value!=null)closes.execute(value::disconnect);}
        boolean cancelled(){return cancelled;}
    }
    static boolean transientFailure(Exception failure) {
        if(failure instanceof EmbyClient.ApiException) {
            int status=((EmbyClient.ApiException)failure).status;
            return status==408||status==429||status==500||status==502||status==503||status==504;
        }
        return failure instanceof EOFException||failure instanceof SocketException||failure instanceof SocketTimeoutException
                ||failure instanceof UnknownHostException||failure instanceof NoRouteToHostException
                ||failure instanceof ConnectException||failure instanceof ProtocolException
                ||failure instanceof IOException&&"unexpected end of stream".equals(failure.getMessage());
    }
    static <T>T read(Call<T> task,Check check)throws Exception {
        for(int attempt=0;;attempt++) {
            check.run();
            try {return task.run();}
            catch(Exception failure) {
                if(attempt>=2||!transientFailure(failure))throw failure;
                waitFor(attempt==0?750:2000,check);
            }
        }
    }
    static void waitFor(long millis,Check check)throws IOException {
        long until=android.os.SystemClock.elapsedRealtime()+millis;
        while(android.os.SystemClock.elapsedRealtime()<until) {
            check.run();
            try {Thread.sleep(Math.min(100,Math.max(1,until-android.os.SystemClock.elapsedRealtime())));}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("Cancelled");}
        }
        check.run();
    }
    static void interrupted()throws IOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled");}
}
