package ai.core.cli.http;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Trust-all SSL context for the explicit TLS opt-in (a core-ai-server running with its
 * self-signed local certificate). Never applied implicitly: callers pass the flag the user
 * set — {@code --insecure} or the login that stored it.
 * <p>
 * The manager must extend {@link X509ExtendedTrustManager}: a plain {@code X509TrustManager}
 * gets wrapped by JSSE and would still trigger endpoint identification (hostname) checks.
 *
 * @author stephen
 */
public final class TlsTrust {
    public static SSLContext trustAllContext() {
        try {
            var context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to create trust-all SSL context", e);
        }
    }

    private TlsTrust() {
    }

    private static final class TrustAllManager extends X509ExtendedTrustManager {
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) { }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) { }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) { }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) { }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
    }
}
