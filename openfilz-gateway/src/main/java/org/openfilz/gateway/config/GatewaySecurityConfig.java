// com/example/gateway/config/GatewaySecurityConfig.java
package org.openfilz.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;


@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {



    // Define public paths that don't require authentication at the gateway level
    private static final String[] PUBLIC_PATHS = {
        "/actuator/**", // Allow actuator endpoint
            // Add other public paths if needed, e.g., specific API docs for the gateway itself
        // Note: The downstream service (DMS API) will still enforce its own security for its Swagger UI.
    };


    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable) // Standard for stateless APIs
            .authorizeExchange(exchange -> exchange
                .pathMatchers(PUBLIC_PATHS).permitAll() // Public paths
                .pathMatchers(HttpMethod.OPTIONS).permitAll()
                .anyExchange().authenticated() // All other requests must be authenticated
            )
            // Configure OAuth2 Resource Server for JWT validation
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults())) // Uses issuer-uri from properties
            // Configure OAuth2 Login if the gateway needs to initiate login flows (e.g. for web apps using the gateway)
            .oauth2Login(withDefaults()) // For user-facing login page hosted by gateway
            ;
        return http.build();
    }

}