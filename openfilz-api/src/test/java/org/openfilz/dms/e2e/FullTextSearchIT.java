package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.service.IndexNameProvider;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.query_dsl.MatchQuery;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class FullTextSearchIT extends TestContainersBaseConfig {


    private static OpenSearchAsyncClient openSearchAsyncClient;

    @Container
    static OpenSearchContainer<?> openSearch = new OpenSearchContainer<>(DockerImageName.parse("opensearchproject/opensearch:latest"));

    public FullTextSearchIT(WebTestClient webTestClient) {
        super(webTestClient);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.full-text.opensearch.host", () -> openSearch.getHost());
        registry.add("openfilz.full-text.opensearch.port", () -> openSearch.getFirstMappedPort());
        registry.add("openfilz.full-text.opensearch.scheme", () -> openSearch.getHttpHostAddress().substring(0, openSearch.getHttpHostAddress().indexOf(":")));
        registry.add("openfilz.full-text.opensearch.username", () -> openSearch.getUsername());
        registry.add("openfilz.full-text.opensearch.password", () -> openSearch.getPassword());
        registry.add("openfilz.full-text.active", () -> Boolean.TRUE);
    }

    @BeforeAll
    static void init() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        log.debug("Creating OpenSearchAsyncClient for host: {}, port: {}", openSearch.getHost(), openSearch.getFirstMappedPort());
        final HttpHost httpHost = new HttpHost(openSearch.getHttpHostAddress().substring(0, openSearch.getHttpHostAddress().indexOf(":")), openSearch.getHost(), openSearch.getFirstMappedPort());
        final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        //Only for demo purposes. Don't specify your credentials in code.
        credentialsProvider.setCredentials(new AuthScope(httpHost), new UsernamePasswordCredentials(openSearch.getUsername(), openSearch.getPassword().toCharArray()));


        final SSLContext sslcontext = SSLContextBuilder
                .create()
                .loadTrustMaterial(null, (chains, authType) -> true)
                .build();

        final ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(httpHost);
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            final var tlsStrategy = ClientTlsStrategyBuilder.create()
                    .setSslContext(sslcontext)
                    .buildAsync();

            final PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder
                    .create()
                    .setTlsStrategy(tlsStrategy)
                    .build();

            return httpClientBuilder
                    .setDefaultCredentialsProvider(credentialsProvider)
                    .setConnectionManager(connectionManager);
        });
        openSearchAsyncClient = new OpenSearchAsyncClient(builder.build());
    }

    @Test
    void uploadFileAndVerifyIndex() throws IOException, ExecutionException, InterruptedException {

        MultipartBodyBuilder builder = newFileBuilder();
        String appId = UUID.randomUUID().toString();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", appId));

        UploadResponse response = getUploadResponse(builder);

        SearchRequest searchRequest = new SearchRequest.Builder()
                .index(IndexNameProvider.DEFAULT_INDEX_NAME)
                .query(q -> q.match(MatchQuery.builder()
                        .field("metadata.owner")
                        .query(fv -> fv.stringValue("OpenFilz")).build()))
                .build();
        CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> {
            log.info("Waiting 3 seconds...");
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            latch.countDown();
        }).start();
        latch.await();

        Assertions.assertEquals(1, Objects.requireNonNull(openSearchAsyncClient.search(searchRequest, Map.class).get().hits().total()).value());


    }

}
