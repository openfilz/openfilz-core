package org.openfilz.dms.service.signature.impl;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.service.signature.SignatureSealer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CloudSignatureSealer} against an in-memory fake of sign.openfilz.com wired through
 * {@code WebClient.Builder.exchangeFunction}. The fake signs the received SHA-256 digest with a
 * locally generated RSA key (PKCS#1 v1.5 over the DigestInfo), exactly like the real service.
 */
class CloudSignatureSealerTest {

    /** DER prefix of DigestInfo(SHA-256) — RFC 8017 §9.2 note 1. */
    private static final byte[] SHA256_DIGEST_INFO_PREFIX =
            HexFormat.of().parseHex("3031300d060960864801650304020105000420");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static SignatureTestKeys.Material material;
    private static byte[] pdf;

    @BeforeAll
    static void setup() throws Exception {
        material = SignatureTestKeys.generate("OpenFilz Cloud Test");
        pdf = SignatureTestKeys.fixturePdf();
    }

    private static SignatureProperties props(String apiKey) {
        SignatureProperties p = new SignatureProperties();
        p.getSeal().setProvider("openfilz-cloud");
        p.getSeal().setName("Cloud Seal");
        p.getSeal().getCloud().setUrl("https://sign.test.local");
        p.getSeal().getCloud().setApiKey(apiKey);
        return p;
    }

    private static SignatureEnvelope envelope() {
        return SignatureEnvelope.builder().id(UUID.randomUUID()).title("cloud").build();
    }

    /** In-memory sign.openfilz.com. */
    private static final class FakeSigningServer implements ExchangeFunction {
        final List<ClientRequest> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger certCalls = new AtomicInteger();
        boolean failSignHash;
        boolean chainAsList = true;

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            requests.add(request);
            String path = request.url().getPath();
            try {
                if (request.method() == HttpMethod.GET && path.equals("/api/v1/cert")) {
                    certCalls.incrementAndGet();
                    String der = Base64.getEncoder().encodeToString(material.certificate().getEncoded());
                    Map<String, Object> body = chainAsList
                            ? Map.of("certificate", der, "certificateChain", List.of(der))
                            : Map.of("certificate", der, "certificateChain", List.of());
                    return Mono.just(json(HttpStatus.OK, body));
                }
                if (request.method() == HttpMethod.POST && path.equals("/api/v1/sign-hash")) {
                    if (failSignHash) {
                        return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                                .body("boom").build());
                    }
                    return readBody(request).map(raw -> {
                        try {
                            Map<?, ?> req = JSON.readValue(raw, Map.class);
                            assertThat(req.get("hashAlgo")).isEqualTo("SHA256");
                            assertThat((String) req.get("context")).startsWith("envelope:");
                            byte[] hash = Base64.getDecoder().decode((String) req.get("hash"));
                            assertThat(hash).hasSize(32);
                            byte[] digestInfo = new byte[SHA256_DIGEST_INFO_PREFIX.length + hash.length];
                            System.arraycopy(SHA256_DIGEST_INFO_PREFIX, 0, digestInfo, 0, SHA256_DIGEST_INFO_PREFIX.length);
                            System.arraycopy(hash, 0, digestInfo, SHA256_DIGEST_INFO_PREFIX.length, hash.length);
                            Signature rsa = Signature.getInstance("NONEwithRSA");
                            rsa.initSign(material.keyPair().getPrivate());
                            rsa.update(digestInfo);
                            String sig = Base64.getEncoder().encodeToString(rsa.sign());
                            return json(HttpStatus.OK, Map.of("signature", sig, "algorithm", "SHA256withRSA"));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
                }
                return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
            } catch (Exception e) {
                return Mono.error(e);
            }
        }

        private static Mono<String> readBody(ClientRequest request) {
            MockClientHttpRequest mock = new MockClientHttpRequest(request.method(), request.url());
            return request.writeTo(mock, ExchangeStrategies.withDefaults()).then(Mono.defer(mock::getBodyAsString));
        }

        private static ClientResponse json(HttpStatus status, Map<String, Object> body) {
            return ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(JSON.writeValueAsString(body))
                    .build();
        }
    }

    private static CloudSignatureSealer sealer(SignatureProperties props, FakeSigningServer server) {
        return new CloudSignatureSealer(props, WebClient.builder().exchangeFunction(server));
    }

    @Test
    void id_isOpenfilzCloud() {
        assertThat(sealer(props("key"), new FakeSigningServer()).id()).isEqualTo("openfilz-cloud");
        assertThat(CloudSignatureSealer.ID).isEqualTo("openfilz-cloud");
    }

    @Test
    void seal_signsViaRemoteHash_andCmsVerifiesWithRemoteCert() throws Exception {
        FakeSigningServer server = new FakeSigningServer();
        CloudSignatureSealer sealer = sealer(props("tenant-key"), server);
        SignatureEnvelope env = envelope();

        SignatureSealer.SealResult result = sealer.seal(pdf, env).block();

        assertThat(result).isNotNull();
        assertThat(result.provider()).isEqualTo("openfilz-cloud");
        try (PDDocument doc = Loader.loadPDF(result.bytes())) {
            List<PDSignature> sigs = doc.getSignatureDictionaries();
            assertThat(sigs).hasSize(1);
            PDSignature sig = sigs.getFirst();
            assertThat(sig.getName()).isEqualTo("Cloud Seal");
            byte[] signedContent = sig.getSignedContent(result.bytes());
            byte[] cms = sig.getContents(result.bytes());
            CMSSignedData signedData = SignatureTestKeys.parseCms(signedContent, cms);
            assertThat(signedData.getSignerInfos().getSigners()).hasSize(1);
            SignerInformation signer = signedData.getSignerInfos().getSigners().iterator().next();
            assertThat(signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(material.certificate()))).isTrue();
        }
        // bearer auth on every call, only the hash crosses the wire
        assertThat(server.requests).allSatisfy(r ->
                assertThat(r.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tenant-key"));
        assertThat(server.requests).extracting(r -> r.url().getPath())
                .containsExactly("/api/v1/cert", "/api/v1/sign-hash");

        // second seal reuses the cached certificate chain
        sealer.seal(pdf, envelope()).block();
        assertThat(server.certCalls.get()).isEqualTo(1);
    }

    @Test
    void seal_withEmptyChainList_fallsBackToSingleCertificate() throws Exception {
        FakeSigningServer server = new FakeSigningServer();
        server.chainAsList = false;
        SignatureSealer.SealResult result = sealer(props("k"), server).seal(pdf, envelope()).block();
        try (PDDocument doc = Loader.loadPDF(result.bytes())) {
            assertThat(doc.getSignatureDictionaries()).hasSize(1);
        }
    }

    @Test
    void seal_missingApiKey_errorsBeforeAnyCall() {
        FakeSigningServer server = new FakeSigningServer();
        StepVerifier.create(sealer(props(""), server).seal(pdf, envelope()))
                .expectErrorSatisfies(err -> assertThat(err)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("api-key"))
                .verify();
        StepVerifier.create(sealer(props(null), server).seal(pdf, envelope()))
                .expectError(IllegalStateException.class)
                .verify();
        assertThat(server.requests).isEmpty();
    }

    @Test
    void seal_serverErrorOnSignHash_propagatesError() {
        FakeSigningServer server = new FakeSigningServer();
        server.failSignHash = true;
        StepVerifier.create(sealer(props("k"), server).seal(pdf, envelope()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(Exception.class);
                    Throwable root = err;
                    boolean found = false;
                    while (root != null) {
                        if (root instanceof WebClientResponseException) { found = true; break; }
                        root = root.getCause();
                    }
                    assertThat(found).as("WebClientResponseException in cause chain").isTrue();
                })
                .verify();
    }

    @Test
    void seal_garbagePdf_errors() {
        StepVerifier.create(sealer(props("k"), new FakeSigningServer())
                        .seal("nope".getBytes(StandardCharsets.UTF_8), envelope()))
                .expectError()
                .verify();
    }
}
