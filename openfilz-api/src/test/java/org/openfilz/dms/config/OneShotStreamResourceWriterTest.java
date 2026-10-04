package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OneShotStreamResourceWriterTest {

    private static final byte[] CONTENT = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);
    private static final ResolvableType RESOURCE_TYPE = ResolvableType.forClass(Resource.class);

    private final OneShotStreamResourceWriter writer = new OneShotStreamResourceWriter();

    private MockServerHttpResponse write(Resource resource) {
        MockServerHttpRequest request = MockServerHttpRequest.get("/download")
                .header(HttpHeaders.RANGE, "bytes=0-4").build();
        MockServerHttpResponse response = new MockServerHttpResponse();
        StepVerifier.create(writer.write(Mono.just(resource), RESOURCE_TYPE, RESOURCE_TYPE,
                MediaType.APPLICATION_OCTET_STREAM, request, response, Map.of())).verifyComplete();
        return response;
    }

    @Test
    void rangeOnStream_sendsWholeContentWithoutRangeSupport() {
        MockServerHttpResponse response = write(new InputStreamResource(new ByteArrayInputStream(CONTENT)));

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .isNotEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("none");
        StepVerifier.create(response.getBodyAsString())
                .expectNext(new String(CONTENT, StandardCharsets.UTF_8)).verifyComplete();
    }

    @Test
    void rangeOnSeekableResource_keepsPartialContent() {
        MockServerHttpResponse response = write(new ByteArrayResource(CONTENT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        StepVerifier.create(response.getBodyAsString()).expectNext("01234").verifyComplete();
    }
}
