package org.openfilz.dms.config;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import jakarta.annotation.PreDestroy;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * Configuration to add an HTTPS server for mTLS alongside the main HTTP server.
 * <p>
 * This configuration is activated by setting openfilz.thumbnail.mtls-access.enabled=true
 * (NOT server.ssl.enabled, which would affect the main server).
 * <p>
 * The result is:
 * - Main HTTP server on port 8081 (for browser/frontend)
 * - Secondary HTTPS server on port 8443 (for ImgProxy mTLS)
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "openfilz.thumbnail.mtls-access.enabled", havingValue = "true")
public class DualPortServerConfig {

    private final HttpHandler httpHandler;

    @Value("${openfilz.mtls.keystore.path:${server.ssl.key-store:}}")
    private String keyStorePath;

    @Value("${openfilz.mtls.keystore.password:${server.ssl.key-store-password:}}")
    private String keyStorePassword;

    @Value("${openfilz.mtls.keystore.type:PKCS12}")
    private String keyStoreType;

    @Value("${openfilz.mtls.truststore.path:${server.ssl.trust-store:}}")
    private String trustStorePath;

    @Value("${openfilz.mtls.truststore.password:${server.ssl.trust-store-password:}}")
    private String trustStorePassword;

    @Value("${openfilz.mtls.truststore.type:PKCS12}")
    private String trustStoreType;

    @Value("${openfilz.mtls.client-auth:want}")
    private String clientAuth;

    @Value("${openfilz.mtls.port:8443}")
    private int httpsPort;

    private DisposableServer httpsServer;

    public DualPortServerConfig(HttpHandler httpHandler) {
        this.httpHandler = httpHandler;
    }

    /**
     * Starts the HTTPS server after the application is fully ready.
     * This ensures the main HTTP server is already running.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startHttpsServer() {
        if (keyStorePath == null || keyStorePath.isEmpty()) {
            log.warn("mTLS is enabled but no keystore configured. Set openfilz.mtls.keystore.path or server.ssl.key-store");
            return;
        }

        try {
            log.info("Starting HTTPS/mTLS server on port {} with client-auth={}", httpsPort, clientAuth);

            ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);

            HttpServer server = HttpServer.create()
                    .port(httpsPort)
                    .secure(spec -> {
                        try {
                            spec.sslContext(buildSslContext());
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to build SSL context", e);
                        }
                    })
                    .handle(adapter);

            httpsServer = server.bindNow();
            log.info("HTTPS/mTLS server started successfully on port {}", httpsServer.port());

        } catch (Exception e) {
            log.error("Failed to start HTTPS/mTLS server on port {}: {}", httpsPort, e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (httpsServer != null) {
            log.info("Stopping HTTPS/mTLS server...");
            httpsServer.dispose();
            log.info("HTTPS/mTLS server stopped");
        }
    }

    /**
     * Builds the SSL context for the HTTPS server with optional client authentication.
     */
    private io.netty.handler.ssl.SslContext buildSslContext() throws Exception {
        // Load keystore
        KeyStore keyStore = KeyStore.getInstance(keyStoreType);
        String keyStoreLocation = resolveFilePath(keyStorePath);
        try (InputStream is = new FileInputStream(keyStoreLocation)) {
            keyStore.load(is, keyStorePassword.toCharArray());
        }

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, keyStorePassword.toCharArray());

        SslContextBuilder builder = SslContextBuilder.forServer(keyManagerFactory);

        // Load truststore if configured (for client certificate validation)
        if (trustStorePath != null && !trustStorePath.isEmpty()) {
            KeyStore trustStore = KeyStore.getInstance(trustStoreType);
            String trustStoreLocation = resolveFilePath(trustStorePath);
            try (InputStream is = new FileInputStream(trustStoreLocation)) {
                trustStore.load(is, trustStorePassword.toCharArray());
            }

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            builder.trustManager(trustManagerFactory);
        }

        // Configure client authentication
        ClientAuth nettyClientAuth = switch (clientAuth.toLowerCase()) {
            case "need" -> ClientAuth.REQUIRE;
            case "want" -> ClientAuth.OPTIONAL;
            default -> ClientAuth.NONE;
        };
        builder.clientAuth(nettyClientAuth);

        log.debug("SSL context built with keystore={}, truststore={}, clientAuth={}",
                keyStorePath, trustStorePath, nettyClientAuth);

        return builder.build();
    }

    /**
     * Resolves file: prefix from Spring resource paths.
     */
    private String resolveFilePath(String path) {
        if (path.startsWith("file:")) {
            return path.substring(5);
        }
        return path;
    }
}
