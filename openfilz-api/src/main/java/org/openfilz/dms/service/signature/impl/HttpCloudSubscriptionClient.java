package org.openfilz.dms.service.signature.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.dto.signature.CloudSignatureSubscription;
import org.openfilz.dms.service.signature.CloudSubscriptionClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Live client for {@code sign.openfilz.com}'s {@code GET /api/v1/subscription}. The WebClient
 * is built lazily on first use (the properties are read at runtime — native-image safe), and
 * responses are cached for a short TTL so the Settings page cannot be used to hammer the
 * signing service.
 */
@Slf4j
@Service
public class HttpCloudSubscriptionClient implements CloudSubscriptionClient {

    private static final long CACHE_TTL_MILLIS = 60_000;

    private final SignatureProperties props;
    private final WebClient.Builder builder;

    private volatile WebClient client;
    private volatile CloudSignatureSubscription cached;
    private volatile long cachedAt;

    public HttpCloudSubscriptionClient(SignatureProperties props, WebClient.Builder builder) {
        this.props = props;
        this.builder = builder;
    }

    @Override
    public Mono<CloudSignatureSubscription> fetch() {
        CloudSignatureSubscription hit = cached;
        if (hit != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MILLIS) {
            return Mono.just(hit);
        }
        return client().get().uri("/api/v1/subscription")
                .retrieve()
                .bodyToMono(CloudSignatureSubscription.class)
                .timeout(props.getSeal().getCloud().getTimeout())
                .doOnNext(sub -> {
                    cached = sub;
                    cachedAt = System.currentTimeMillis();
                })
                .onErrorMap(e -> {
                    log.warn("[e-sign] Cloud Signing subscription fetch failed: {}", e.toString());
                    return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Cloud Signing service is unreachable");
                });
    }

    private WebClient client() {
        WebClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    SignatureProperties.Seal.Cloud cloud = props.getSeal().getCloud();
                    client = builder.clone()
                            .baseUrl(cloud.getUrl())
                            .defaultHeaders(h -> h.setBearerAuth(cloud.getApiKey() == null ? "" : cloud.getApiKey()))
                            .build();
                }
                c = client;
            }
        }
        return c;
    }
}
