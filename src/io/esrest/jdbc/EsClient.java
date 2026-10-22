package io.esrest.jdbc;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Thin HTTP client for the Elasticsearch REST API (no external dependencies). */
final class EsClient {
    private final String baseUrl;
    private final String authorization;
    private final SSLSocketFactory sslSocketFactory;
    private final boolean trustAll;
    final int connectTimeoutMillis;
    volatile int readTimeoutMillis;

    EsClient(EsConfig config) throws SQLException {
        this.baseUrl = config.baseUrl;
        this.authorization = authorization(config);
        this.sslSocketFactory = sslSocketFactory(config);
        this.trustAll = config.trustAll;
        this.connectTimeoutMillis = config.connectTimeoutMillis;
        this.readTimeoutMillis = config.readTimeoutMillis;
    }

    /** POST /_sql?format=json and return the parsed response. */
    Map<String, Object> sql(Map<String, Object> body) throws SQLException {
        String response = send("POST", "/_sql?format=json", Json.write(body));
        try {
            return Json.parseObject(response);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Invalid JSON from Elasticsearch: " + e.getMessage(), "HY000", e);
        }
    }

    void closeCursor(String cursor) {
        try {
            send("POST", "/_sql/close", Json.write(Map.of("cursor", cursor)));
        } catch (SQLException ignored) {
            // the cursor may already have expired; nothing to clean up
        }
    }

    /** Sends a request and parses the response as JSON; plain-text responses (e.g. _cat APIs) are returned as String. */
    Object json(String method, String path, String body) throws SQLException {
        String response = send(method, path, body);
        if (response.isEmpty()) {
            return null;
        }
        try {
            return Json.parse(response);
        } catch (IllegalArgumentException notJson) {
            return response;
        }
    }

    String send(String method, String path, String body) throws SQLException {
        HttpURLConnection http = open(path);
        try {
            http.setRequestMethod(method.toUpperCase());
            http.setConnectTimeout(connectTimeoutMillis);
            http.setReadTimeout(readTimeoutMillis);
            http.setRequestProperty("Accept", "application/json");
            if (authorization != null) {
                http.setRequestProperty("Authorization", authorization);
            }
            if (body != null && !body.isEmpty()) {
                writeBody(http, path, body);
            }

            int status = http.getResponseCode();
            InputStream stream = status >= 400 ? http.getErrorStream() : http.getInputStream();
            String response = stream == null ? "" : readAll(stream);
            if (status >= 400) {
                throw httpError(status, response);
            }
            return response;
        } catch (IOException e) {
            throw new SQLException("Cannot reach Elasticsearch at " + baseUrl + ": " + e, "08001", e);
        } finally {
            http.disconnect();
        }
    }

    // ---------------------------------------------------------------- helpers

    private HttpURLConnection open(String path) throws SQLException {
        String url = baseUrl + (path.startsWith("/") ? path : "/" + path);
        try {
            HttpURLConnection http = (HttpURLConnection) URI.create(url).toURL().openConnection();
            if (!(http instanceof HttpsURLConnection)) {
                return http;
            }
            HttpsURLConnection https = (HttpsURLConnection) http;
            if (sslSocketFactory != null) {
                https.setSSLSocketFactory(sslSocketFactory);
            }
            if (trustAll) {
                https.setHostnameVerifier((host, session) -> true);
            }
            return https;
        } catch (IllegalArgumentException | IOException e) {
            throw new SQLException("Invalid URL: " + url, "08001", e);
        }
    }

    private static void writeBody(HttpURLConnection http, String path, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        boolean ndjson = path.contains("_bulk") || path.contains("_msearch");
        http.setDoOutput(true);
        http.setRequestProperty("Content-Type", (ndjson ? "application/x-ndjson" : "application/json") + "; charset=UTF-8");
        http.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = http.getOutputStream()) {
            out.write(bytes);
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String authorization(EsConfig config) {
        if (config.apiKey != null && !config.apiKey.isEmpty()) {
            return "ApiKey " + config.apiKey;
        }
        if (config.user == null || config.user.isEmpty()) {
            return null;
        }
        String credentials = config.user + ":" + (config.password == null ? "" : config.password);
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static SSLSocketFactory sslSocketFactory(EsConfig config) throws SQLException {
        try {
            if (config.trustAll) {
                return sslContext(new TrustManager[] {new TrustAllManager()}).getSocketFactory();
            }
            if (config.caCertPath == null || config.caCertPath.isEmpty()) {
                return null;
            }
            return sslContext(trustManagersFor(config.caCertPath)).getSocketFactory();
        } catch (Exception e) {
            throw new SQLException("Cannot initialise TLS: " + e.getMessage(), "08001", e);
        }
    }

    private static TrustManager[] trustManagersFor(String caCertPath) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        try (InputStream in = Files.newInputStream(Paths.get(caCertPath))) {
            int index = 0;
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                keyStore.setCertificateEntry("ca" + index++, certificate);
            }
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore);
        return factory.getTrustManagers();
    }

    private static SSLContext sslContext(TrustManager[] trustManagers) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, null);
        return context;
    }

    /** Turns an Elasticsearch error response into an SQLException; the HTTP status becomes the vendor code. */
    private static SQLException httpError(int status, String response) {
        String state = status == 401 || status == 403 ? "28000"
                : status == 400 ? "42000"
                : status == 404 ? "42S02"
                : "HY000";
        return new SQLException("HTTP " + status + ": " + errorReason(response), state, status);
    }

    private static String errorReason(String response) {
        Object parsed;
        try {
            parsed = Json.parse(response);
        } catch (IllegalArgumentException notJson) {
            return response;
        }
        if (!(parsed instanceof Map)) {
            return response;
        }
        Object error = ((Map<?, ?>) parsed).get("error");
        if (error == null) {
            return response;
        }
        if (!(error instanceof Map)) {
            return String.valueOf(error);
        }

        Map<?, ?> topError = (Map<?, ?>) error;
        Map<?, ?> cause = topError;
        Object rootCauses = cause.get("root_cause");
        if (rootCauses instanceof List && !((List<?>) rootCauses).isEmpty() && ((List<?>) rootCauses).get(0) instanceof Map) {
            cause = (Map<?, ?>) ((List<?>) rootCauses).get(0);
        }
        String reason = "[" + cause.get("type") + "] " + cause.get("reason");
        Map<?, ?> deepest = deepestCause(topError);
        if (deepest == topError || deepest == cause) {
            return reason;
        }
        return reason + ", caused by [" + deepest.get("type") + "] " + deepest.get("reason");
    }

    /** Follows caused_by to the innermost error, e.g. the real exception behind a painless "runtime error". */
    private static Map<?, ?> deepestCause(Map<?, ?> error) {
        Map<?, ?> current = error;
        while (current.get("caused_by") instanceof Map) {
            current = (Map<?, ?>) current.get("caused_by");
        }
        return current;
    }

    private static final class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) { }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) { }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
