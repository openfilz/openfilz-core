package org.openfilz.dms.service.signature;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.service.signature.impl.InProcessSignatureSealer;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SealCertificatesTest {

    private static final String DEV_SEAL = "OpenFilz e-Sign Seal (dev)";

    @Test
    void signerName_readsTheSealCertificateCommonName() throws Exception {
        assertThat(SealCertificates.signerName(devSealed())).isEqualTo(DEV_SEAL);
    }

    @Test
    void signerName_isNullForUnsignedOrInvalidInput() throws Exception {
        assertThat(SealCertificates.signerName(blankPdf())).isNull();
        assertThat(SealCertificates.signerName(new byte[]{1, 2, 3})).isNull();
        assertThat(SealCertificates.signerName(new byte[0])).isNull();
        assertThat(SealCertificates.signerName(null)).isNull();
    }

    @Test
    void signerName_skipsADocumentTimestampAppendedAfterTheSeal() throws Exception {
        // PAdES-B-LTA: the TSA's document timestamp is the last signature in the file but not the seal.
        byte[] lta = addSignature(devSealed(), COSName.getPDFName("ETSI.RFC3161"), content -> new byte[]{0x30, 0x00});

        assertThat(SealCertificates.signerName(lta)).isEqualTo(DEV_SEAL);
    }

    @Test
    void signerName_picksTheSignatureAppliedLast() throws Exception {
        byte[] twice = addSignature(devSealed(), PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED,
                cmsSigner("CN=Later Signer, O=ACME"));

        assertThat(SealCertificates.signerName(twice)).isEqualTo("Later Signer");
    }

    @Test
    void displayName_fallsBackToOrganizationThenFullSubject() {
        assertThat(SealCertificates.displayName(new X500Name("CN=OpenFilz SAS, O=OpenFilz"))).isEqualTo("OpenFilz SAS");
        assertThat(SealCertificates.displayName(new X500Name("O=OpenFilz, C=FR"))).isEqualTo("OpenFilz");
        assertThat(SealCertificates.displayName(new X500Name("C=FR"))).isEqualTo("C=FR");
    }

    @Test
    void clean_stripsControlCharactersAndFitsTheColumn() {
        assertThat(SealCertificates.clean("Open\u0000Filz\r\n SAS ")).isEqualTo("OpenFilz SAS");
        assertThat(SealCertificates.clean("\u0001\t")).isNull();
        assertThat(SealCertificates.clean(null)).isNull();

        String exact = "x".repeat(SealCertificates.MAX_LENGTH);
        assertThat(SealCertificates.clean(exact)).isEqualTo(exact);

        String cut = SealCertificates.clean("y".repeat(SealCertificates.MAX_LENGTH + 100));
        assertThat(cut).hasSize(SealCertificates.MAX_LENGTH).endsWith("…");

        // An astral code point straddling the cut is dropped whole rather than split.
        String emojiAtCut = "z".repeat(SealCertificates.MAX_LENGTH - 2) + "😀" + "tail";
        String cutEmoji = SealCertificates.clean(emojiAtCut);
        assertThat(cutEmoji).hasSizeLessThanOrEqualTo(SealCertificates.MAX_LENGTH).endsWith("z…");
        assertThat(cutEmoji.chars().noneMatch(c -> Character.isSurrogate((char) c))).isTrue();

        // A long DN (no CN / O, several 60-char OUs: BC caps each attribute, not the whole name) is cut too.
        String longDn = String.join(",", java.util.Collections.nCopies(5, "OU=" + "n".repeat(60)));
        assertThat(SealCertificates.displayName(new X500Name(longDn))).hasSize(SealCertificates.MAX_LENGTH);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static byte[] devSealed() throws Exception {
        InProcessSignatureSealer sealer = new InProcessSignatureSealer(new SignatureProperties()).init();
        SignatureEnvelope env = SignatureEnvelope.builder().id(UUID.randomUUID()).title("t").build();
        return sealer.seal(blankPdf(), env).block().bytes();
    }

    private static byte[] blankPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Appends one more signature (incremental update) with the given sub-filter and CMS producer. */
    private static byte[] addSignature(byte[] pdf, COSName subFilter, SignatureInterface si) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDSignature sig = new PDSignature();
            sig.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            sig.setSubFilter(subFilter);
            sig.setSignDate(Calendar.getInstance());
            doc.addSignature(sig, si);
            doc.saveIncremental(out);
            return out.toByteArray();
        }
    }

    /** A detached CMS signer backed by a fresh self-signed certificate with the given subject. */
    private static SignatureInterface cmsSigner(String subject) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        long now = System.currentTimeMillis();
        X500Name name = new X500Name(subject);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), new Date(now - 60_000L),
                        new Date(now + 3_600_000L), name, kp.getPublic()).build(signer));
        return content -> {
            try {
                CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
                ContentSigner cms = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
                gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                        new JcaDigestCalculatorProviderBuilder().build()).build(cms, cert));
                gen.addCertificates(new JcaCertStore(List.<Certificate>of(cert)));
                return gen.generate(new CMSProcessableByteArray(content.readAllBytes()), false).getEncoded();
            } catch (Exception e) {
                throw new java.io.IOException(e);
            }
        };
    }
}
