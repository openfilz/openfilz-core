// com/example/dms/config/SecurityConfig.java
package org.openfilz.dms.config;

import lombok.RequiredArgsConstructor;

import org.openfilz.dms.security.SecurityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.AuthorizationContext;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity // For method-level security like @PreAuthorize
@RequiredArgsConstructor
@Conditional(DefaultRolesCondition.class)
public class DefaultAuthSecurityConfig {

    public static final String ALL_MATCHES = "/**";

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    protected String issuerUri;

    @Value("${spring.graphql.http.path:/graphql}")
    protected String graphQlBaseUrl;

    protected final SecurityService securityService;

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        return ReactiveJwtDecoders.fromIssuerLocation(issuerUri);
    }

    private static final String[] AUTH_WHITELIST = {
            // Swagger UI v3
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/webjars/swagger-ui/**",
            // Actuator endpoints
            "/actuator/**",
            // GraphiQL
            "/graphiql",
            "/graphiql/**"
    };

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // Disable CSRF for stateless APIs
                .authorizeExchange(exchanges -> {
                    exchanges.pathMatchers(AUTH_WHITELIST).permitAll() // Whitelist Swagger and health
                            .pathMatchers(HttpMethod.OPTIONS).permitAll()
                            .pathMatchers(RestApiVersion.API_PREFIX + ALL_MATCHES, graphQlBaseUrl + ALL_MATCHES)
                            .access((mono, context) -> mono
                                    .map(auth -> newAuthorizationDecision(auth, context)))
                            .anyExchange()
                            .authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(getJwtSpecCustomizer()))
                .build();
    }

    protected Customizer<ServerHttpSecurity.OAuth2ResourceServerSpec.JwtSpec> getJwtSpecCustomizer() {
        return jwt -> jwt.jwtDecoder(jwtDecoder());
    }

    private AuthorizationDecision newAuthorizationDecision(Authentication auth, AuthorizationContext context) {
        return new AuthorizationDecision(securityService.authorize(auth, context));
    }

}