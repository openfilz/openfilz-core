package org.openfilz.dms.service.branding;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.BrandingProperties;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.config.WorkflowProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandingProviderTest {

    @Test
    void defaultsAreOpenFilz() {
        Brand brand = new PropertiesBrandingProvider(new BrandingProperties(), new SignatureProperties(), new WorkflowProperties()).brand();
        assertThat(brand.productName()).isEqualTo("OpenFilz");
        assertThat(brand.primaryColor()).isEqualTo(Brand.DEFAULT_PRIMARY_COLOR);
        assertThat(brand.brandUrl()).isEqualTo(Brand.OPENFILZ_URL);
        assertThat(brand.isOpenFilz()).isTrue();
        assertThat(brand.poweredByShown()).isFalse();
    }

    @Test
    void legacyMailerValuesAreFallbacks() {
        SignatureProperties signature = new SignatureProperties();
        WorkflowProperties workflows = new WorkflowProperties();
        workflows.getMail().setProductName("Acme Docs");
        workflows.getMail().setLogoUrl("https://acme.test/logo.png");
        Brand brand = new PropertiesBrandingProvider(new BrandingProperties(), signature, workflows).brand();
        assertThat(brand.productName()).isEqualTo("Acme Docs");
        assertThat(brand.logoUrl()).isEqualTo("https://acme.test/logo.png");
        // a brand other than OpenFilz has no footer link unless one is set
        assertThat(brand.brandUrl()).isEmpty();
        assertThat(brand.poweredByShown()).isTrue();

        signature.getMail().setProductName("Sign Co");
        assertThat(new PropertiesBrandingProvider(new BrandingProperties(), signature, workflows).brand().productName())
                .isEqualTo("Sign Co");
    }

    @Test
    void brandingPropertiesWinOverLegacy() {
        BrandingProperties branding = new BrandingProperties();
        branding.setProductName("Brand X");
        branding.setPrimaryColor("#1A73E8");
        branding.setBrandUrl("https://x.test");
        branding.setShowPoweredBy(false);
        SignatureProperties signature = new SignatureProperties();
        signature.getMail().setProductName("Legacy");
        Brand brand = new PropertiesBrandingProvider(branding, signature, null).brand();
        assertThat(brand.productName()).isEqualTo("Brand X");
        assertThat(brand.primaryColor()).isEqualTo("#1a73e8");
        assertThat(brand.brandUrl()).isEqualTo("https://x.test");
        assertThat(brand.poweredByShown()).isFalse();
    }

    @Test
    void malformedColourFallsBackToDefault() {
        assertThat(Brand.of("X", "", "red", "", "", true).primaryColor()).isEqualTo(Brand.DEFAULT_PRIMARY_COLOR);
        assertThat(Brand.of("X", "", "#12345", "", "", true).primaryColor()).isEqualTo(Brand.DEFAULT_PRIMARY_COLOR);
    }

    @Test
    void colours() {
        assertThat(BrandColors.contrastWithWhite("#ffffff")).isEqualTo(1.0);
        assertThat(BrandColors.contrastWithWhite("#000000")).isEqualTo(21.0);
        assertThat(BrandColors.contrastWithWhite("#667eea")).isGreaterThan(BrandColors.MIN_CONTRAST_WITH_WHITE);
        assertThat(BrandColors.contrastWithWhite("#ffeb3b")).isLessThan(BrandColors.MIN_CONTRAST_WITH_WHITE);
        assertThat(BrandColors.darker("#808080", 0.5)).isEqualTo("#404040");
        assertThat(BrandColors.lighter("#000000", 0.5)).isEqualTo("#808080");
        assertThatThrownBy(() -> BrandColors.darker("nope", 0.1)).isInstanceOf(IllegalArgumentException.class);
    }
}
