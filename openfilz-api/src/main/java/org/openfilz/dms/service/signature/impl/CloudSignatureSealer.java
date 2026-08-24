package org.openfilz.dms.service.signature.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.service.signature.SignatureSealer;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * Tier-2 seal: the CMS container is built locally (PDFBox + Bouncy Castle) but the RSA
 * signature over the signed attributes is produced by {@code sign.openfilz.com}
 * ({@code POST /api/v1/sign-hash}) with OpenFilz's AATL key. Only a SHA-256 digest ever
 * leaves the deployment; the document content does not.
 *
 * <p>Requires a tenant API key ({@code openfilz.signature.seal.cloud.api-key}). The leaf
 * certificate and chain are fetched once from {@code GET /api/v1/cert} and cached for the JVM
 * lifetime. Produces PAdES-B-B (no embedded timestamp, no PDF/A) — the PDF/A-2b + B-T path
 * remains the enterprise {@code archiving-api}.
 */
@Slf4j
public class CloudSignatureSealer implements SignatureSealer {

    public static final String ID = "openfilz-cloud";

    private final SignatureProperties props;
    private final WebClient client;
    private volatile List<X509Certificate> chain;

    public CloudSignatureSealer(SignatureProperties props, WebClient.Builder builder) {
        this.props = props;
        SignatureProperties.Seal.Cloud cloud = props.getSeal().getCloud();
        this.client = builder.clone()
                .baseUrl(cloud.getUrl())
                .defaultHeaders(h -> h.setBearerAuth(cloud.getApiKey() == null ? "" : cloud.getApiKey()))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(256 * 1024))
                .build();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Mono<SealResult> seal(byte[] stampedPdf, SignatureEnvelope envelope) {
        if (props.getSeal().getCloud().getApiKey() == null || props.getSeal().getCloud().getApiKey().isBlank()) {
            return Mono.error(new IllegalStateException(
                    "openfilz.signature.seal.cloud.api-key is required for the openfilz-cloud seal provider"));
        }
        return Mono.fromCallable(() -> SealResult.plain(applySeal(stampedPdf, envelope), ID))
                .subscribeOn(Schedulers.boundedElastic());
    }

    byte[] applySeal(byte[] pdf, SignatureEnvelope envelope) throws Exception {
        List<X509Certificate> certs = chain();
        X509Certificate leaf = certs.getFirst();
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
                    gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                            new JcaDigestCalculatorProviderBuilder().build())
                            .build(new RemoteContentSigner("envelope:" + envelope.getId()), leaf));
                    gen.addCertificates(new JcaCertStore(new ArrayList<Certificate>(certs)));
                    CMSSignedData signedData = gen.generate(new CmsInputStreamData(content), false);
                    return signedData.getEncoded();
                } catch (Exception e) {
                    throw new IOException("Cloud CMS sealing failed", e);
                }
            };
            doc.addSignature(signature, si);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.saveIncremental(out);
            return out.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private List<X509Certificate> chain() throws Exception {
        List<X509Certificate> cached = chain;
        if (cached != null) return cached;
        Map<String, Object> body = client.get().uri("/api/v1/cert").retrieve()
                .bodyToMono(Map.class)
                .block(props.getSeal().getCloud().getTimeout());
        if (body == null) throw new IllegalStateException("sign.openfilz.com returned no certificate");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> result = new ArrayList<>();
        Object chainObj = body.get("certificateChain");
        if (chainObj instanceof List<?> list && !list.isEmpty()) {
            for (Object c : list) result.add(parse(cf, (String) c));
        } else {
            result.add(parse(cf, (String) body.get("certificate")));
        }
        chain = result;
        log.info("[e-sign] openfilz-cloud seal certificate loaded: {}", result.getFirst().getSubjectX500Principal());
        return result;
    }

    private static X509Certificate parse(CertificateFactory cf, String b64Der) throws Exception {
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(b64Der)));
    }

    /** Remote signer: hashes the to-be-signed bytes (CMS signed attributes) and asks sign.openfilz.com for the RSA signature. */
    private final class RemoteContentSigner implements ContentSigner {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final String context;

        RemoteContentSigner(String context) {
            this.context = context;
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            return new DefaultSignatureAlgorithmIdentifierFinder().find("SHA256withRSA");
        }

        @Override
        public OutputStream getOutputStream() {
            return buffer;
        }

        @Override
        @SuppressWarnings("unchecked")
        public byte[] getSignature() {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray());
                Map<String, Object> resp = client.post().uri("/api/v1/sign-hash")
                        .bodyValue(Map.of("hash", Base64.getEncoder().encodeToString(digest),
                                "hashAlgo", "SHA256", "context", context))
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block(props.getSeal().getCloud().getTimeout());
                if (resp == null || resp.get("signature") == null) {
                    throw new IllegalStateException("sign.openfilz.com returned no signature");
                }
                return Base64.getDecoder().decode((String) resp.get("signature"));
            } catch (Exception e) {
                throw new IllegalStateException("Remote signing failed: " + e.getMessage(), e);
            }
        }
    }
}
