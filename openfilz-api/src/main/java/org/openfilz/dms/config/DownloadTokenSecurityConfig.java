package org.openfilz.dms.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.security.DownloadTokenAuthenticationToken;
import org.openfilz.dms.security.DownloadTokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dedicated security chain for redeeming signed download links:
 * {@code GET /api/v1/documents/{id}/download?token=…} (see {@link DownloadTokenProperties} and
 * {@code DownloadTokenService} for the feature and its security model).
 * <p>
 * The matcher claims an exchange only when ALL of: the feature is enabled (read per request —
 * runtime toggle, GraalVM-native safe, no sentinel path needed), the path is exactly the single
 * download endpoint, and a {@code token} query parameter is present. Everything else — including
 * plain bearer-authenticated downloads of the same path — falls through to the default OAuth2
 * chain untouched, so this chain narrows nothing and opens exactly one parameterized route.
 * <p>
 * A valid token authenticates the request as its <em>minter</em>
 * ({@link DownloadTokenAuthenticationToken}), after which the normal controller flow runs: the
 * document service re-resolves the document under that identity (extension layers re-check
 * access at click time) and the audit trail logs the download as that user. Any token failure —
 * absent claims, bad signature, expired, document mismatch — answers a uniform empty 404,
 * indistinguishable from a document that does not exist.
 * <p>
 * Chain ordering convention: -4 download tokens, -3 public signatures, -2 upload tokens (EE),
 * -1 OnlyOffice, unordered = default OAuth2.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DownloadTokenSecurityConfig {

    /** {@code /api/v1/documents/{uuid}/download} — the one path a token is accepted on. */
    private static final String DOWNLOAD_PATH_PATTERN =
            RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS + "/*/download";

    private static final Pattern DOCUMENT_ID_IN_PATH = Pattern.compile(
            Pattern.quote(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS)
                    + "/([0-9a-fA-F-]{36})/download$");

    private final DownloadTokenService downloadTokenService;

    @Bean
    @Order(-4)
    public SecurityWebFilterChain downloadTokenSecurityFilterChain(ServerHttpSecurity http) {
        AuthenticationWebFilter authFilter = new AuthenticationWebFilter(authenticationManager());
        authFilter.setServerAuthenticationConverter(DownloadTokenSecurityConfig::extractToken);
        // Uniform 404 on any authentication failure — never a hint about why the token failed
        authFilter.setAuthenticationFailureHandler(
                (webFilterExchange, exception) -> notFound(webFilterExchange.getExchange()));

        return http
                .securityMatcher(this::matchesTokenizedDownload)
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> {})
                .addFilterAt(authFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                // Reached only if the converter produced nothing (unparseable path) — same 404
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((exchange, exception) -> notFound(exchange)))
                .authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
                .build();
    }

    /**
     * Claims the exchange for this chain only when the feature is on and the request is the
     * tokenized form of the download endpoint. Checked per request so the toggle is runtime.
     */
    private Mono<ServerWebExchangeMatcher.MatchResult> matchesTokenizedDownload(ServerWebExchange exchange) {
        if (!downloadTokenService.isEnabled()
                || exchange.getRequest().getQueryParams().getFirst(DownloadTokenService.TOKEN_PARAM) == null) {
            return ServerWebExchangeMatcher.MatchResult.notMatch();
        }
        return new PathPatternParserServerWebExchangeMatcher(DOWNLOAD_PATH_PATTERN).matches(exchange);
    }

    private ReactiveAuthenticationManager authenticationManager() {
        return authentication -> {
            DownloadTokenAuthenticationToken token = (DownloadTokenAuthenticationToken) authentication;
            String minterEmail = downloadTokenService.validate(token.getRawToken(), token.getDocumentId());
            if (minterEmail == null) {
                return Mono.error(new BadCredentialsException("invalid download token"));
            }
            log.debug("Download token redeemed for document {} by {}", token.getDocumentId(), minterEmail);
            return Mono.just(new DownloadTokenAuthenticationToken(
                    minterEmail, token.getDocumentId(), token.getRawToken()));
        };
    }

    private static Mono<Authentication> extractToken(ServerWebExchange exchange) {
        String rawToken = exchange.getRequest().getQueryParams().getFirst(DownloadTokenService.TOKEN_PARAM);
        UUID documentId = documentIdFrom(exchange.getRequest().getPath().value());
        return (rawToken == null || documentId == null)
                ? Mono.empty()
                : Mono.just(new DownloadTokenAuthenticationToken(rawToken, documentId));
    }

    private static UUID documentIdFrom(String path) {
        Matcher matcher = DOCUMENT_ID_IN_PATH.matcher(path);
        if (!matcher.find()) {
            return null;
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Mono<Void> notFound(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }
}
