package org.openfilz.dms.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Core default: the validated token IS the request's Authentication. Its {@code getName()}
 * carries the minter's email, which is what {@code UserInfoService} (and therefore the audit
 * trail) falls back to. The enterprise layer overrides with {@code @Primary} — see
 * {@link DownloadTokenAuthenticationEnricher}.
 */
@Component
public class DefaultDownloadTokenAuthenticationEnricher implements DownloadTokenAuthenticationEnricher {

    @Override
    public Mono<Authentication> enrich(DownloadTokenAuthenticationToken authentication) {
        return Mono.just(authentication);
    }
}
