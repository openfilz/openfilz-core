package org.openfilz.dms.service.signature.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.service.signature.SignatureSealer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * In-process PAdES-B-B seal (detached CMS / PKCS#7, SHA-256 with RSA) applied with PDFBox +
 * Bouncy Castle. Key material comes from a PKCS#12 keystore ({@code pkcs12} provider) or, when
 * none is configured, from an ephemeral self-signed RSA-2048 pair generated at startup
 * ({@code self-signed-dev} — fine for evaluation, recipients must import the cert once).
 *
 * <p>SHA256withRSA is resolved from the default JDK provider, not "BC": forcing the BC provider
 * fails in GraalVM native images (BC algorithm services are not reflectively registered).
 */
@Slf4j
public class InProcessSignatureSealer implements SignatureSealer {

    private final SignatureProperties props;
    private PrivateKey sealKey;
    private X509Certificate sealCert;
    private String providerId = "self-signed-dev";

    public InProcessSignatureSealer(SignatureProperties props) {
        this.props = props;
    }

    /** Loads (or generates) the seal key material. Called once by {@code SignatureConfig}. */
    public InProcessSignatureSealer init() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        SignatureProperties.Seal seal = props.getSeal();
        try {
            if (seal.getKeystorePath() != null && !seal.getKeystorePath().isBlank()) {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                char[] pwd = seal.getKeystorePassword() == null ? new char[0] : seal.getKeystorePassword().toCharArray();
                try (InputStream in = Files.newInputStream(Path.of(seal.getKeystorePath()))) {
                    ks.load(in, pwd);
                }
                this.sealKey = (PrivateKey) ks.getKey(seal.getKeystoreAlias(), pwd);
                this.sealCert = (X509Certificate) ks.getCertificate(seal.getKeystoreAlias());
                this.providerId = "pkcs12";
                log.info("[e-sign] PAdES seal loaded from keystore alias '{}' ({})", seal.getKeystoreAlias(),
                        sealCert.getSubjectX500Principal());
            } else {
                generateEphemeralSeal();
                log.warn("[e-sign] No openfilz.signature.seal.keystore-path configured — using an EPHEMERAL "
                        + "self-signed seal certificate (self-signed-dev). Configure a PKCS#12 keystore or the "
                        + "openfilz-cloud provider before relying on this in production.");
            }
        } catch (Exception e) {
            log.error("[e-sign] Failed to load seal keystore — falling back to ephemeral seal", e);
            try {
                generateEphemeralSeal();
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot initialise e-sign seal material", ex);
            }
        }
        return this;
    }

    private void generateEphemeralSeal() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        long now = System.currentTimeMillis();
        var issuer = new org.bouncycastle.asn1.x500.X500Name("CN=OpenFilz e-Sign Seal (dev), O=OpenFilz");
        var builder = new JcaX509v3CertificateBuilder(issuer, BigInteger.valueOf(now),
                new Date(now - 60_000L), new Date(now + 10L * 365 * 24 * 3600 * 1000), issuer, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        this.sealCert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        this.sealKey = kp.getPrivate();
        this.providerId = "self-signed-dev";
    }

    @Override
    public String id() {
        return providerId;
    }

    /** Public certificate of the seal (for {@code GET /signatures/seal-certificate}). */
    public X509Certificate certificate() {
        return sealCert;
    }

    @Override
    public Mono<SealResult> seal(byte[] stampedPdf, SignatureEnvelope envelope) {
        return Mono.fromCallable(() -> SealResult.plain(applySeal(stampedPdf, envelope), providerId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    byte[] applySeal(byte[] pdf, SignatureEnvelope envelope) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(props.getSeal().getName());
            signature.setReason("Document completed via OpenFilz e-Sign — envelope " + envelope.getId());
            signature.setSignDate(Calendar.getInstance());

            SignatureInterface si = content -> {
                try {
                    CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
                    ContentSigner sha256 = new JcaContentSignerBuilder("SHA256withRSA").build(sealKey);
                    gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                            new JcaDigestCalculatorProviderBuilder().build()).build(sha256, sealCert));
                    gen.addCertificates(new JcaCertStore(List.<Certificate>of(sealCert)));
                    CMSSignedData signedData = gen.generate(new CmsInputStreamData(content), false);
                    return signedData.getEncoded();
                } catch (Exception e) {
                    throw new IOException("CMS sealing failed", e);
                }
            };

            doc.addSignature(signature, si);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.saveIncremental(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to apply PAdES seal", e);
        }
    }
}
