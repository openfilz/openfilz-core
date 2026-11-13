package org.openfilz.dms.service.signature;

import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.service.branding.Brand;
import org.openfilz.dms.service.branding.BrandingProvider;

import java.util.UUID;

/**
 * The words a seal writes into a signed PDF's signature dictionary: the reason (the product brand) and
 * the signer name.
 * <p>
 * The name is the deployment's ({@code openfilz.signature.seal.name}) when it was changed, else
 * "{product} e-Sign Seal". A seal made with someone else's certificate keeps its owner's name whatever
 * the brand: {@link #certificateOwnerName()}.
 */
public final class SealWording {

    /** The default of {@code openfilz.signature.seal.name}. */
    public static final String DEFAULT_SEAL_NAME = "OpenFilz e-Sign Seal";

    private SealWording() {
    }

    /** "Document completed via {product} e-Sign, envelope {id}". */
    public static String reason(BrandingProvider branding, UUID envelopeId) {
        return "Document completed via " + productName(branding) + " e-Sign"
                + (envelopeId == null ? "" : ", envelope " + envelopeId);
    }

    /** The signer name of a seal made with the deployment's own certificate. */
    public static String sealName(SignatureProperties props, BrandingProvider branding) {
        String configured = props.getSeal().getName();
        if (configured != null && !configured.isBlank() && !DEFAULT_SEAL_NAME.equals(configured.strip())) {
            return configured.strip();
        }
        return productName(branding) + " e-Sign Seal";
    }

    /** The signer name of a seal made with the hosted signing service's certificate: always its owner's. */
    public static String certificateOwnerName() {
        return DEFAULT_SEAL_NAME;
    }

    private static String productName(BrandingProvider branding) {
        Brand brand = branding == null ? null : branding.brand();
        return brand == null ? Brand.DEFAULT_PRODUCT_NAME : brand.productName();
    }
}
