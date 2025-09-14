// com/example/dms/config/SecurityConfig.java
package org.openfilz.dms.config;

import org.openfilz.dms.enums.RoleTokenLookup;
import org.openfilz.dms.service.impl.AbstractSecurityService;
import org.openfilz.dms.service.impl.SecurityServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
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
public class SecurityConfig {

    public static final String ALL_MATCHES = "/**";

    @Value("${spring.security.no-auth}")
    private Boolean noAuth;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${spring.security.auth-class:#{null}}")
    private String authClassName;

    @Value("${spring.security.role-token-lookup:#{null}}")
    private RoleTokenLookup roleTokenLookup;

    @Value("${spring.security.root-group:#{null}}")
    private String rootGroup;

    @Value("${spring.graphql.http.path:/graphql}")
    protected String graphQlBaseUrl;

    @Bean
    @ConditionalOnProperty(name = "spring.security.no-auth", havingValue = "false")
    public AbstractSecurityService securityService() {
        if(authClassName != null) {
            try {
                return (AbstractSecurityService) Class.forName(authClassName)
                        .getConstructor(RoleTokenLookup.class, String.class, String.class)
                        .newInstance(roleTokenLookup, rootGroup, graphQlBaseUrl);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return new SecurityServiceImpl(roleTokenLookup, rootGroup, graphQlBaseUrl);
    }


    @Bean
    @ConditionalOnProperty(name = "spring.security.no-auth", havingValue = "false")
    public ReactiveJwtDecoder jwtDecoder() {
        return ReactiveJwtDecoders.fromIssuerLocation(issuerUri);
    }


    private static final String[] AUTH_WHITELIST = {
            // Swagger UI v3
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/webjars/swagger-ui/**",
            // Actuator health
            "/actuator/health/**"
    };

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // Disable CSRF for stateless APIs
                .authorizeExchange(exchanges -> {
                            if (noAuth) {
                                exchanges.anyExchange().permitAll();
                            } else {
                                exchanges.pathMatchers(AUTH_WHITELIST).permitAll() // Whitelist Swagger and health
                                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                                        .pathMatchers(RestApiVersion.API_PREFIX + ALL_MATCHES, graphQlBaseUrl + ALL_MATCHES)
                                            .access((mono, context) -> mono
                                                .map(auth -> newAuthorizationDecision(auth, context)))
                                        .anyExchange()
                                        .authenticated();
                            }
                        }
                );
        if(!noAuth) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(jwt -> jwt.jwtDecoder(jwtDecoder()))); // Configure JWT decoder
        }
        return http.build();
    }

    private AuthorizationDecision newAuthorizationDecision(Authentication auth, AuthorizationContext context) {
        return new AuthorizationDecision(securityService().authorize(auth, context));
    }




}