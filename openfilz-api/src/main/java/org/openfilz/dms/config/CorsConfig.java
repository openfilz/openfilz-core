package org.openfilz.dms.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.openfilz.dms.config.RestApiVersion.API_PREFIX;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "openfilz.security.cors-allowed-origins")
public class CorsConfig {

    @Value("${openfilz.security.cors-allowed-origins}")
    private String[] allowedOrigins;

    /**
     * Extra request headers a browser may send to the REST API on top of the built-in list
     * (Authorization, Content-Type, TUS headers). Any custom header triggers a CORS preflight,
     * which fails unless the header is listed here — e.g. an extension that reads
     * {@code X-OpenFilz-Reason} on its endpoints adds it in its application.yml.
     */
    @Value("${openfilz.security.cors-allowed-headers:}")
    private String[] allowedHeaders;

    @Value("${spring.graphql.http.path:/graphql}")
    protected String graphQlBaseUrl;

    @PostConstruct
    public void init() {
        log.info("Created Cors bean with {}, {}, extra headers {}", API_PREFIX, Arrays.toString(allowedOrigins), Arrays.toString(allowedHeaders));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        if (allowedOrigins != null && allowedOrigins.length > 0) {
            // REST API CORS configuration (includes TUS endpoints)
            CorsConfiguration apiConfig = new CorsConfiguration();
            apiConfig.setAllowedOrigins(Arrays.asList(allowedOrigins));
            apiConfig.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
            // TUS protocol requires these headers to be allowed in requests
            List<String> requestHeaders = new ArrayList<>(List.of(
                    "Authorization",
                    "Content-Type",
                    "Content-Length",
                    "Upload-Length",
                    "Upload-Offset",
                    "Upload-Metadata",
                    "Tus-Resumable",
                    "X-Requested-With",
                    "X-HTTP-Method-Override"
            ));
            // Deployment- or extension-specific headers (openfilz.security.cors-allowed-headers)
            Arrays.stream(allowedHeaders).map(String::trim).filter(h -> !h.isEmpty()).forEach(requestHeaders::add);
            apiConfig.setAllowedHeaders(requestHeaders);
            // TUS protocol requires these headers to be exposed to JavaScript
            apiConfig.setExposedHeaders(Arrays.asList(
                    "Location",
                    "Tus-Resumable",
                    "Tus-Version",
                    "Tus-Extension",
                    "Tus-Max-Size",
                    "Upload-Offset",
                    "Upload-Length",
                    "Upload-Metadata"
            ));
            apiConfig.setAllowCredentials(true);
            apiConfig.setMaxAge(3600L); // Cache preflight for 1 hour

            source.registerCorsConfiguration(API_PREFIX + "/**", apiConfig);

            // GraphQL API CORS configuration
            CorsConfiguration graphqlConfig = new CorsConfiguration();
            graphqlConfig.setAllowedOrigins(Arrays.asList(allowedOrigins));
            graphqlConfig.setAllowedMethods(Arrays.asList("POST", "OPTIONS"));
            graphqlConfig.setAllowedHeaders(List.of("*"));
            graphqlConfig.setAllowCredentials(true);

            source.registerCorsConfiguration(graphQlBaseUrl + "/**", graphqlConfig);
        }

        return source;
    }
}
