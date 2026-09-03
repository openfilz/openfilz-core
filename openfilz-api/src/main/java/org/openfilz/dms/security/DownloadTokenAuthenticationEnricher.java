package org.openfilz.dms.security;

import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * Extension seam: turns a <em>validated</em> {@link DownloadTokenAuthenticationToken} into the
 * {@link Authentication} the rest of the download request runs as.
 * <p>
 * Core keeps the token as-is ({@link DefaultDownloadTokenAuthenticationEnricher}):
 * {@code UserInfoService} resolves the minter's email from {@code getName()}, which is all the
 * community edition needs. An extension layer whose services resolve the caller through its own
 * principal type (user id / org / teams from a custom token) overrides this with {@code @Primary}
 * and re-resolves the minter's identity from its user store — otherwise the redeeming request
 * reaches those services as an unknown authentication type and fails after a successful token
 * validation.
 * <p>
 * An error signal is treated as an authentication failure by the caller
 * ({@code DownloadTokenSecurityConfig}) and answers the uniform empty 404 — so an enricher may
 * refuse (minter unknown, account deactivated) but can never turn a bad link into a 500.
 */
public interface DownloadTokenAuthenticationEnricher {

    /**
     * @param authentication the already-validated token (authenticated, minter email set)
     * @return the Authentication to establish for the request — the input itself, or a richer
     *         principal for the same minter; an error to refuse redemption
     */
    Mono<Authentication> enrich(DownloadTokenAuthenticationToken authentication);
}
