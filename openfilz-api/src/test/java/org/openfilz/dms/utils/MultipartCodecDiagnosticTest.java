package org.openfilz.dms.utils;

import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader;
import org.springframework.http.codec.multipart.MultipartHttpMessageWriter;
import org.springframework.http.codec.multipart.Part;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Temporary diagnostic for the Spring Framework 7 multipart hang observed in ITs.
 * Writes a multipart body with the client-side writer, then parses it back with
 * the server-side reader — all in memory, no HTTP involved.
 */
class MultipartCodecDiagnosticTest {

    @Test
    void multipartWriterCompletesAndReaderParsesBack() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test_file_1.sql"));

        MultipartHttpMessageWriter writer = new MultipartHttpMessageWriter();
        MockClientHttpRequest request = new MockClientHttpRequest(org.springframework.http.HttpMethod.POST, "/upload");

        Mono<MultiValueMap<String, org.springframework.http.HttpEntity<?>>> bodyMono = Mono.just(builder.build());

        // 1. WRITE — if this blocks, the client-side writer is the culprit
        writer.write(bodyMono, ResolvableType.forClass(MultiValueMap.class),
                        MediaType.MULTIPART_FORM_DATA, request, Map.of())
                .block(Duration.ofSeconds(10));

        List<DataBuffer> written = request.getBody().collectList().block(Duration.ofSeconds(10));
        assertNotNull(written);
        assertFalse(written.isEmpty(), "writer produced no body");
        long totalBytes = written.stream().mapToLong(DataBuffer::readableByteCount).sum();
        System.out.println("WRITER OK — bytes written: " + totalBytes + ", content-type: " + request.getHeaders().getContentType());

        // 2. READ — feed the written bytes to the server-side reader
        MockServerHttpRequest serverRequest = MockServerHttpRequest.post("/upload")
                .contentType(request.getHeaders().getContentType())
                .body(Flux.fromIterable(written));

        DefaultPartHttpMessageReader reader = new DefaultPartHttpMessageReader();
        List<Part> parts = reader.read(ResolvableType.forClass(Part.class), serverRequest, Map.of())
                .flatMap(part -> DataBufferUtils(part))
                .collectList()
                .block(Duration.ofSeconds(10));

        assertNotNull(parts);
        System.out.println("READER OK — parts parsed: " + parts.size());
    }

    @Test
    void multipartOverRealHttpWithReactorNettyClient() throws Exception {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(201).setBody("ok"));
            server.start();

            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new ClassPathResource("test_file_1.sql"));

            String response = org.springframework.web.reactive.function.client.WebClient.create()
                    .post()
                    .uri(server.url("/upload").toString())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(org.springframework.web.reactive.function.BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            System.out.println("HTTP CLIENT OK — response: " + response);
            okhttp3.mockwebserver.RecordedRequest recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(recorded);
            System.out.println("SERVER RECEIVED bytes: " + recorded.getBodySize());
        }
    }

    private Mono<Part> DataBufferUtils(Part part) {
        // drain part content so the parser can move on, then return the part
        return org.springframework.core.io.buffer.DataBufferUtils.join(part.content())
                .map(buf -> {
                    int n = buf.readableByteCount();
                    org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                    System.out.println("part '" + part.name() + "' content bytes: " + n);
                    return part;
                });
    }
}
