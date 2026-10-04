package org.openfilz.dms.config;

import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.http.codec.ResourceHttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * {@link ResourceHttpMessageWriter} that ignores the {@code Range} header for one-shot streams.
 * <p>
 * Object storage (MinIO / S3, and the EE decrypting decorators) hands content back as an
 * {@link org.springframework.core.io.InputStreamResource}, which can be read once and cannot be cut
 * into byte regions. The default writer still advertises {@code Accept-Ranges: bytes} and answers any
 * {@code Range} request for such a resource with {@code 416 Range Not Satisfiable} — which broke the
 * OnlyOffice DocumentServer download ({@code Error downloadFile ... 416}) and any range-aware client.
 * <p>
 * For a resource reporting {@link Resource#isOpen()}, this writer sends the full content with
 * {@code 200} and {@code Accept-Ranges: none} (RFC 9110 §14.2 lets a server ignore {@code Range}).
 * Seekable resources (local filesystem, in-memory) keep the default range support.
 */
public class OneShotStreamResourceWriter extends ResourceHttpMessageWriter {

    @Override
    public Mono<Void> write(Publisher<? extends Resource> inputStream, ResolvableType actualType,
                            ResolvableType elementType, MediaType mediaType, ServerHttpRequest request,
                            ServerHttpResponse response, Map<String, Object> hints) {
        return Mono.from(inputStream).flatMap(resource -> {
            if (!resource.isOpen()) {
                return super.write(Mono.just(resource), actualType, elementType, mediaType, request, response, hints);
            }
            // The client-side variant writes the whole resource, without any range processing
            return super.write(Mono.just(resource), elementType, mediaType, response, hints);
        });
    }

    @Override
    public Mono<Void> addDefaultHeaders(ReactiveHttpOutputMessage message, Resource resource,
                                        MediaType contentType, Map<String, Object> hints) {
        // The default sets "Accept-Ranges: bytes" on every server response, inviting Range requests
        return super.addDefaultHeaders(message, resource, contentType, hints)
                .then(Mono.fromRunnable(() -> {
                    if (resource.isOpen() && message instanceof ServerHttpResponse) {
                        message.getHeaders().set(HttpHeaders.ACCEPT_RANGES, "none");
                    }
                }));
    }
}
