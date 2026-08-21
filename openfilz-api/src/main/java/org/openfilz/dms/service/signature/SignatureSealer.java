package org.openfilz.dms.service.signature;

import org.openfilz.dms.entity.SignatureEnvelope;
import reactor.core.publisher.Mono;

/**
 * Applies the cryptographic seal over the stamped PDF (visible marks + Certificate of
 * Completion already rendered). This is the seam between the open-source envelope engine and
 * the trust tier:
 *
 * <ul>
 *   <li>core: {@code InProcessSignatureSealer} (PAdES-B-B, self-signed or PKCS#12 key) and
 *       {@code CloudSignatureSealer} (hash-only call to sign.openfilz.com with a tenant API key),
 *       selected at runtime by {@code openfilz.signature.seal.provider};</li>
 *   <li>enterprise: {@code ArchivingSignatureSealer} (archiving-api → PDF/A-2b + AATL/TSA seal +
 *       veraPDF), registered {@code @Primary}.</li>
 * </ul>
 *
 * Implementations must never throw synchronously; failures surface as {@code Mono.error}.
 */
public interface SignatureSealer {

    /** Stable provider id persisted on the envelope ({@code seal_provider}). */
    String id();

    Mono<SealResult> seal(byte[] stampedPdf, SignatureEnvelope envelope);

    /**
     * @param bytes      the final signed document
     * @param provider   id of the sealer that produced it
     * @param flavor     archival flavor when applicable (e.g. {@code PDF/A-2b}), else null
     * @param compliant  veraPDF verdict when applicable, else null
     * @param reportJson veraPDF report when applicable, else null
     */
    record SealResult(byte[] bytes, String provider, String flavor, Boolean compliant, String reportJson) {
        public static SealResult plain(byte[] bytes, String provider) {
            return new SealResult(bytes, provider, null, null, null);
        }
    }
}
