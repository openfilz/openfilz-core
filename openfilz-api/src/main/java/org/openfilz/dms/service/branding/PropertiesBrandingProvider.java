package org.openfilz.dms.service.branding;

import org.openfilz.dms.config.BrandingProperties;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.config.WorkflowProperties;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * The brand from the deployment properties, read on every call ({@code openfilz.branding.*}).
 * <p>
 * Older deployments set the product name and logo per mailer
 * ({@code OPENFILZ_SIGNATURE_PRODUCT_NAME}, {@code OPENFILZ_WORKFLOWS_PRODUCT_NAME}, the matching
 * {@code *_LOGO_URL}). Those values still apply when {@code openfilz.branding.*} leaves the field blank:
 * the first one that differs from the default wins, so nothing configured before is lost.
 */
public class PropertiesBrandingProvider implements BrandingProvider {

    private final BrandingProperties branding;
    private final SignatureProperties signature;
    private final WorkflowProperties workflows;

    public PropertiesBrandingProvider(BrandingProperties branding, SignatureProperties signature,
                                      WorkflowProperties workflows) {
        this.branding = branding == null ? new BrandingProperties() : branding;
        this.signature = signature;
        this.workflows = workflows;
    }

    @Override
    public Brand brand() {
        String name = firstSet(branding.getProductName(),
                signature == null ? null : signature.getMail().getProductName(),
                workflows == null ? null : workflows.getMail().getProductName());
        String logo = firstSet(branding.getLogoUrl(),
                signature == null ? null : signature.getMail().getLogoUrl(),
                workflows == null ? null : workflows.getMail().getLogoUrl());
        return Brand.of(name, logo, branding.getPrimaryColor(), branding.getBrandUrl(), branding.getSupportUrl(),
                branding.isShowPoweredBy());
    }

    /** First value that is neither blank nor the OpenFilz default; null when none. */
    private static String firstSet(String... values) {
        return Stream.of(values).filter(Objects::nonNull).map(String::strip)
                .filter(v -> !v.isEmpty() && !Brand.DEFAULT_PRODUCT_NAME.equals(v))
                .findFirst().orElse(null);
    }
}
