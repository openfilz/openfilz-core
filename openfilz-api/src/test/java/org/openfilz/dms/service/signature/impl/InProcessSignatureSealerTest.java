package org.openfilz.dms.service.signature.impl;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.service.signature.SignatureSealer;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InProcessSignatureSealerTest {

    private static SignatureProperties props() {
        SignatureProperties p = new SignatureProperties();
        p.getSeal().setName("Unit Test Seal");
        return p;
    }

    private static SignatureEnvelope envelope() {
        return SignatureEnvelope.builder().id(UUID.randomUUID()).title("t").build();
    }

    @Test
    void init_withoutKeystore_usesEphemeralSelfSignedSeal() throws Exception {
        InProcessSignatureSealer sealer = new InProcessSignatureSealer(props()).init();

        assertThat(sealer.id()).isEqualTo("self-signed-dev");
        assertThat(sealer.certificate()).isNotNull();
        assertThat(sealer.certificate().getSubjectX500Principal().getName()).contains("OpenFilz e-Sign Seal");
        assertThat(sealer.certificate().getIssuerX500Principal()).isEqualTo(sealer.certificate().getSubjectX500Principal());
    }

    @Test
    void seal_producesOneSignatureDictionary_withConfiguredName_andVerifiableCms() throws Exception {
        SignatureProperties props = props();
        InProcessSignatureSealer sealer = new InProcessSignatureSealer(props).init();
        SignatureEnvelope env = envelope();
        byte[] pdf = SignatureTestKeys.fixturePdf();

        SignatureSealer.SealResult result = sealer.seal(pdf, env).block();

        assertThat(result).isNotNull();
        assertThat(result.provider()).isEqualTo("self-signed-dev");
        assertThat(result.flavor()).isNull();
        assertThat(result.compliant()).isNull();
        assertThat(result.bytes().length).isGreaterThan(pdf.length);

        try (PDDocument doc = Loader.loadPDF(result.bytes())) {
            List<PDSignature> sigs = doc.getSignatureDictionaries();
            assertThat(sigs).hasSize(1);
            PDSignature sig = sigs.getFirst();
            assertThat(sig.getName()).isEqualTo("Unit Test Seal");
            assertThat(sig.getReason()).contains(env.getId().toString());
            assertThat(sig.getSubFilter()).isEqualTo(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED.getName());

            byte[] signedContent = sig.getSignedContent(result.bytes());
            byte[] cms = sig.getContents(result.bytes());
            CMSSignedData signedData = SignatureTestKeys.parseCms(signedContent, cms);
            assertThat(signedData.getSignerInfos().getSigners()).hasSize(1);
            SignerInformation signer = signedData.getSignerInfos().getSigners().iterator().next();
            assertThat(signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(sealer.certificate()))).isTrue();
        }
    }

    @Test
    void init_withPkcs12Keystore_loadsKeyAndReportsPkcs12(@TempDir Path tmp) throws Exception {
        SignatureTestKeys.Material material = SignatureTestKeys.generate("Customer Seal");
        Path ksPath = tmp.resolve("seal.p12");
        char[] pwd = "s3cret".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("my-seal", material.keyPair().getPrivate(), pwd, new Certificate[]{material.certificate()});
        try (var out = Files.newOutputStream(ksPath)) {
            ks.store(out, pwd);
        }

        SignatureProperties props = props();
        props.getSeal().setProvider("pkcs12");
        props.getSeal().setKeystorePath(ksPath.toString());
        props.getSeal().setKeystorePassword("s3cret");
        props.getSeal().setKeystoreAlias("my-seal");

        InProcessSignatureSealer sealer = new InProcessSignatureSealer(props).init();

        assertThat(sealer.id()).isEqualTo("pkcs12");
        assertThat(sealer.certificate()).isEqualTo(material.certificate());

        SignatureSealer.SealResult result = sealer.seal(SignatureTestKeys.fixturePdf(), envelope()).block();
        assertThat(result.provider()).isEqualTo("pkcs12");
        try (PDDocument doc = Loader.loadPDF(result.bytes())) {
            PDSignature sig = doc.getSignatureDictionaries().getFirst();
            CMSSignedData signedData = SignatureTestKeys.parseCms(
                    sig.getSignedContent(result.bytes()), sig.getContents(result.bytes()));
            SignerInformation signer = signedData.getSignerInfos().getSigners().iterator().next();
            assertThat(signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(material.certificate()))).isTrue();
            // the embedded certificate is the customer's one
            X509Certificate embedded = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                    .getCertificate(signedData.getCertificates().getMatches(null).iterator().next());
            assertThat(embedded).isEqualTo(material.certificate());
        }
    }

    @Test
    void init_wrongKeystorePassword_fallsBackToEphemeral(@TempDir Path tmp) throws Exception {
        SignatureTestKeys.Material material = SignatureTestKeys.generate("Customer Seal");
        Path ksPath = tmp.resolve("seal.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("my-seal", material.keyPair().getPrivate(), "right".toCharArray(),
                new Certificate[]{material.certificate()});
        try (var out = Files.newOutputStream(ksPath)) {
            ks.store(out, "right".toCharArray());
        }
        SignatureProperties props = props();
        props.getSeal().setKeystorePath(ksPath.toString());
        props.getSeal().setKeystorePassword("wrong");
        props.getSeal().setKeystoreAlias("my-seal");

        InProcessSignatureSealer sealer = new InProcessSignatureSealer(props).init();

        assertThat(sealer.id()).isEqualTo("self-signed-dev");
        assertThat(sealer.certificate()).isNotNull().isNotEqualTo(material.certificate());
    }

    @Test
    void init_missingKeystoreFile_fallsBackToEphemeral(@TempDir Path tmp) {
        SignatureProperties props = props();
        props.getSeal().setKeystorePath(tmp.resolve("does-not-exist.p12").toString());

        InProcessSignatureSealer sealer = new InProcessSignatureSealer(props).init();

        assertThat(sealer.id()).isEqualTo("self-signed-dev");
        assertThat(sealer.certificate()).isNotNull();
    }

    @Test
    void seal_garbageBytes_errorsWithIllegalState() {
        InProcessSignatureSealer sealer = new InProcessSignatureSealer(props()).init();

        StepVerifier.create(sealer.seal("not a pdf".getBytes(StandardCharsets.UTF_8), envelope()))
                .expectErrorSatisfies(err -> assertThat(err)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("PAdES seal"))
                .verify();
    }

    @Test
    void cmsInputStreamData_exposesContentAndWrites() throws Exception {
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        CmsInputStreamData data = new CmsInputStreamData(new ByteArrayInputStream(payload));
        assertThat(data.getContentType()).isEqualTo(org.bouncycastle.asn1.cms.CMSObjectIdentifiers.data);
        assertThat(data.getContent()).isInstanceOf(ByteArrayInputStream.class);
        var out = new java.io.ByteArrayOutputStream();
        data.write(out);
        assertThat(out.toByteArray()).isEqualTo(payload);
    }
}
