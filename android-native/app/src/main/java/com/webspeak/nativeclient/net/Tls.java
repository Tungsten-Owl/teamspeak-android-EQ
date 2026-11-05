package com.webspeak.nativeclient.net;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS setup for gateways with a self-signed certificate.
 *
 * <p>The gateway only serves HTTPS when the operator supplies a certificate, and
 * those certificates are often self-signed on private networks (the browser shows
 * a warning and lets the user continue). The native client offers the same
 * explicit opt-in: it is off by default and the UI warns that it disables
 * certificate validation.
 */
public final class Tls {

    private static volatile SSLSocketFactory trustAllFactory;
    private static volatile HostnameVerifier trustAllVerifier;

    private Tls() {
    }

    public static SSLSocketFactory socketFactory(boolean trustSelfSigned) {
        if (!trustSelfSigned) return (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocketFactory factory = trustAllFactory;
        if (factory == null) {
            synchronized (Tls.class) {
                factory = trustAllFactory;
                if (factory == null) {
                    try {
                        SSLContext context = SSLContext.getInstance("TLS");
                        context.init(null, new TrustManager[]{new PermissiveTrustManager()}, new SecureRandom());
                        factory = context.getSocketFactory();
                        trustAllFactory = factory;
                    } catch (GeneralSecurityException error) {
                        throw new IllegalStateException("TLS initialization failed", error);
                    }
                }
            }
        }
        return factory;
    }

    public static HostnameVerifier hostnameVerifier(boolean trustSelfSigned) {
        if (!trustSelfSigned) return HttpsURLConnection.getDefaultHostnameVerifier();
        HostnameVerifier verifier = trustAllVerifier;
        if (verifier == null) {
            verifier = (hostname, session) -> true;
            trustAllVerifier = verifier;
        }
        return verifier;
    }

    public static void configure(HttpsURLConnection connection, boolean trustSelfSigned) {
        connection.setSSLSocketFactory(socketFactory(trustSelfSigned));
        connection.setHostnameVerifier(hostnameVerifier(trustSelfSigned));
    }

    /** Applies SNI and hostname verification to a raw TLS socket. */
    public static void prepare(SSLSocket socket, String host, boolean trustSelfSigned) throws IOException {
        SSLParameters parameters = socket.getSSLParameters();
        if (!isIpLiteral(host)) {
            parameters.setServerNames(java.util.Collections.singletonList(new javax.net.ssl.SNIHostName(host)));
        }
        if (!trustSelfSigned) parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
    }

    private static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) return true;
        boolean onlyDigitsAndDots = true;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                onlyDigitsAndDots = false;
                break;
            }
        }
        return onlyDigitsAndDots || host.indexOf(':') >= 0;
    }

    public static Socket wrap(Socket raw, String host, int port, boolean trustSelfSigned) throws IOException {
        SSLSocket socket = (SSLSocket) socketFactory(trustSelfSigned).createSocket(raw, host, port, true);
        prepare(socket, host, trustSelfSigned);
        return socket;
    }

    private static final class PermissiveTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
