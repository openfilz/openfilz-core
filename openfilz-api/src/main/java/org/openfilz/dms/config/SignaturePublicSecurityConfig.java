package org.openfilz.dms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;

/**
 * Dedicated permit-all security chain for the signer-facing e-Sign endpoints under
 * {@code /api/v1/public/signatures/**}. External signers have no Keycloak session — they
 * authenticate by presenting the single-use signing token in the URL, which the service
 * validates by hashing it and looking up the recipient row (optionally hardened by an OTP).
 *
 * <p>Chain ordering convention: -3 public signatures, -2 upload tokens (EE), -1 OnlyOffice,
 * unordered = default OAuth2. When {@code openfilz.signature.active} is false the chain
 * matches an unreachable sentinel path so it stays inert without being removed from the
 * context (native-image safe runtime toggle).
 */
@Configuration
public class SignaturePublicSecurityConfig {

    public static final String PUBLIC_SIGNATURE_PATH = RestApiVersion.API_PREFIX + "/public/signatures/**";
    private static final String DISABLED_SENTINEL_PATH = "/_disabled-public-signatures-chain";

    @Bean
    @Order(-3)
    public SecurityWebFilterChain publicSignatureSecurityFilterChain(
            @Value("${openfilz.signature.active:false}") boolean enabled,
            ServerHttpSecurity http) {
        String matcher = enabled ? PUBLIC_SIGNATURE_PATH : DISABLED_SENTINEL_PATH;
        return http
                .securityMatcher(new PathPatternParserServerWebExchangeMatcher(matcher))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> {})
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }
}
