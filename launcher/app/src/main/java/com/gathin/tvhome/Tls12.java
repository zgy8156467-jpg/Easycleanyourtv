package com.gathin.tvhome;

import android.os.Build;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Android 4.1–4.4 speak TLS 1.2 but ship it switched off, and the picture hosts only offer 1.2.
 * This turns it on for those versions. Below 4.1 there is no TLS 1.2 to enable and the request just
 * fails the way it already did.
 */
final class Tls12 {

    private static SSLSocketFactory factory;
    private static boolean failed;

    private Tls12() {
    }

    /** Call right after openConnection, before connecting. No-op on 5.0 and up. */
    static void apply(URLConnection c) {
        if (Build.VERSION.SDK_INT < 16 || Build.VERSION.SDK_INT >= 21) return;
        if (!(c instanceof HttpsURLConnection)) return;
        SSLSocketFactory f = factory();
        if (f != null) ((HttpsURLConnection) c).setSSLSocketFactory(f);
    }

    private static synchronized SSLSocketFactory factory() {
        if (factory != null || failed) return factory;
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, null, null);
            factory = new Forced(context.getSocketFactory());
        } catch (Exception e) {
            failed = true;   // nothing to do below 4.1; the request fails on its own
        }
        return factory;
    }

    /** Sets the protocol on every socket the platform factory hands out. */
    private static final class Forced extends SSLSocketFactory {
        private final SSLSocketFactory base;

        Forced(SSLSocketFactory base) {
            this.base = base;
        }

        private Socket force(Socket s) {
            if (s instanceof SSLSocket) {
                try {
                    ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.2"});
                } catch (Exception ignored) {
                }
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return force(base.createSocket()); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return force(base.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return force(base.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return force(base.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return force(base.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
            return force(base.createSocket(host, port, local, localPort));
        }
    }
}
