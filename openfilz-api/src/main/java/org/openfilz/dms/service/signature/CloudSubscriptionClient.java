package org.openfilz.dms.service.signature;

import org.openfilz.dms.dto.signature.CloudSignatureSubscription;
import reactor.core.publisher.Mono;

/**
 * Fetches the deployment's Cloud Signing subscription from {@code sign.openfilz.com}.
 * An interface so tests can substitute the remote call; the production implementation is
 * {@code HttpCloudSubscriptionClient}.
 */
public interface CloudSubscriptionClient {

    Mono<CloudSignatureSubscription> fetch();
}
