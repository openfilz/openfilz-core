package org.openfilz.dms.service.signature.impl;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.service.branding.Brand;
import org.openfilz.dms.service.branding.BrandingProvider;
import org.openfilz.dms.service.signature.SealWording;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SealWordingTest {

    private final BrandingProvider acme = () -> Brand.OPENFILZ.withProductName("Acme Docs");

    @Test
    void reasonNamesTheProduct() {
        UUID id = UUID.randomUUID();
        assertThat(SealWording.reason(acme, id)).isEqualTo("Document completed via Acme Docs e-Sign, envelope " + id);
        assertThat(SealWording.reason(null, null)).isEqualTo("Document completed via OpenFilz e-Sign");
    }

    @Test
    void sealNameIsTheDeploymentsWhenChangedElseTheProducts() {
        SignatureProperties props = new SignatureProperties();
        assertThat(SealWording.sealName(props, acme)).isEqualTo("Acme Docs e-Sign Seal");
        assertThat(SealWording.sealName(props, null)).isEqualTo("OpenFilz e-Sign Seal");
        props.getSeal().setName("Acme Legal Seal");
        assertThat(SealWording.sealName(props, acme)).isEqualTo("Acme Legal Seal");
        assertThat(SealWording.certificateOwnerName()).isEqualTo("OpenFilz e-Sign Seal");
    }

    @Test
    void inProcessSealWritesTheBrand() throws Exception {
        InProcessSignatureSealer sealer = new InProcessSignatureSealer(new SignatureProperties(), acme).init();
        SignatureEnvelope env = SignatureEnvelope.builder().id(UUID.randomUUID()).title("t").build();
        byte[] sealed = sealer.seal(SignatureTestKeys.fixturePdf(), env).block().bytes();
        try (PDDocument doc = Loader.loadPDF(sealed)) {
            PDSignature sig = doc.getSignatureDictionaries().getFirst();
            assertThat(sig.getName()).isEqualTo("Acme Docs e-Sign Seal");
            assertThat(sig.getReason()).isEqualTo("Document completed via Acme Docs e-Sign, envelope " + env.getId());
        }
    }
}
