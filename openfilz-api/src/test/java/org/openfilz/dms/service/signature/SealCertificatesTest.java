package org.openfilz.dms.service.signature;

import org.bouncycastle.asn1.x500.X500Name;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.service.signature.impl.InProcessSignatureSealer;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import static org.assertj.core.api.Assertions.assertThat;

class SealCertificatesTest {

    @Test
    void signerName_readsTheSealCertificateCommonName() throws Exception {
        InProcessSignatureSealer sealer = new InProcessSignatureSealer(new SignatureProperties()).init();
        SignatureEnvelope env = SignatureEnvelope.builder().id(UUID.randomUUID()).title("t").build();

        byte[] sealed = sealer.seal(blankPdf(), env).block().bytes();

        assertThat(SealCertificates.signerName(sealed)).isEqualTo("OpenFilz e-Sign Seal (dev)");
    }

    @Test
    void signerName_isNullForUnsignedOrInvalidInput() throws Exception {
        assertThat(SealCertificates.signerName(blankPdf())).isNull();
        assertThat(SealCertificates.signerName(new byte[]{1, 2, 3})).isNull();
        assertThat(SealCertificates.signerName(null)).isNull();
    }

    @Test
    void displayName_fallsBackToOrganizationThenFullSubject() {
        assertThat(SealCertificates.displayName(new X500Name("CN=OpenFilz SAS, O=OpenFilz"))).isEqualTo("OpenFilz SAS");
        assertThat(SealCertificates.displayName(new X500Name("O=OpenFilz, C=FR"))).isEqualTo("OpenFilz");
        assertThat(SealCertificates.displayName(new X500Name("C=FR"))).isEqualTo("C=FR");
    }

    private static byte[] blankPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }
}
