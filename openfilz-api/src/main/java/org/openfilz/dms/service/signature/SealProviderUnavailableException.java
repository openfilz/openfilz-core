package org.openfilz.dms.service.signature;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The seal provider cannot seal right now, and no other sealer may stand in for it. A
 * {@link SignatureSealer} signals it when sealing with any other identity would be wrong, for
 * example an AATL key held in an HSM that the in-process sealer cannot use. The envelope
 * engine does not fall back to the core sealer for this error: the completion transaction rolls
 * back (the last signature included), so the signer is told to try again shortly (503) and no
 * document is sealed with an untrusted certificate.
 *
 * <p>The {@code reason} is the body of that 503: it is shown as is to the signer, who is not
 * authenticated. Keep it to a sentence for that person, with no host name, URL or provider
 * error in it; pass the technical failure as the {@code cause}, which only reaches the log.
 */
public class SealProviderUnavailableException extends ResponseStatusException {

    public SealProviderUnavailableException(String reason) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }

    public SealProviderUnavailableException(String reason, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason, cause);
    }
}
