package org.waldur.keycloak.mapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.jboss.logging.Logger;

/**
 * Thin wrapper around the JDK HttpClient that all Waldur mappers share.
 * Centralises connect/request timeouts, the optional trust-all SSL
 * context for non-validated TLS, and the Token-auth header.
 */
final class WaldurHttpClient {

    private static final Logger LOGGER = Logger.getLogger(WaldurHttpClient.class.getName());

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client;
    private final String token;

    WaldurHttpClient(String token, boolean tlsValidationEnabled) {
        this.client = build(tlsValidationEnabled);
        this.token = token;
    }

    /** Convenience overload for callers that don't need to flip TLS validation. */
    WaldurHttpClient(String token) {
        this(token, true);
    }

    /**
     * GET the URL and return the response body as a String, or an empty string when the call fails
     * or the server returns a non-200 status. All exceptions are caught and logged so a flaky
     * Waldur API never throws into the mapper.
     */
    String get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .setHeader("Authorization", "Token " + token)
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            LOGGER.infof("Waldur GET %s -> %d", url, statusCode);
            if (statusCode != 200) {
                return "";
            }
            return response.body();
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            return "";
        }
    }

    private static HttpClient build(boolean tlsValidationEnabled) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT);
        if (tlsValidationEnabled) {
            return builder.build();
        }
        try {
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            return builder.sslContext(sslContext).build();
        } catch (Exception e) {
            LOGGER.warn("Failed to create permissive SSL context, falling back to default validation");
            return builder.build();
        }
    }
}
