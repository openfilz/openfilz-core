package org.openfilz.dms.controller.rest;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.TusProperties;
import org.openfilz.dms.exception.TusUploadException;
import org.openfilz.dms.service.TusUploadService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PATCH refusals in TUS order: an offset mismatch is a 409 even when the chunk would also overflow
 * the declared Upload-Length; the 413 applies only once the offset is the server's.
 */
class TusControllerPatchOrderTest {

    private final TusUploadService service = mock(TusUploadService.class);
    private final TusController controller = new TusController(service, mock(TusProperties.class), mock(CommonProperties.class));
    private final Flux<DataBuffer> body = Flux.just(new DefaultDataBufferFactory().wrap(new byte[1024]));

    @Test
    void offsetMismatch_is409_evenWhenTheChunkWouldOverflow() {
        when(service.getUploadOffset("u")).thenReturn(Mono.just(0L));
        when(service.getUploadLength("u")).thenReturn(Mono.just(1024L));
        when(service.uploadChunk(eq("u"), eq(500L), any())).thenReturn(Mono.error(new TusUploadException("Offset mismatch. Expected: 0, Got: 500")));

        StepVerifier.create(controller.uploadChunk("u", 500L, 1024L, body))
                .assertNext(response -> assertEquals(HttpStatus.CONFLICT, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void matchingOffset_pastTheDeclaredLength_is413_withoutWriting() {
        when(service.getUploadOffset("u")).thenReturn(Mono.just(0L));
        when(service.getUploadLength("u")).thenReturn(Mono.just(10L));

        StepVerifier.create(controller.uploadChunk("u", 0L, 1024L, body))
                .assertNext(response -> assertEquals(HttpStatus.CONTENT_TOO_LARGE, response.getStatusCode()))
                .verifyComplete();
        verify(service, never()).uploadChunk(any(), anyLong(), any());
    }

    @Test
    void matchingOffset_withinTheDeclaredLength_isWritten() {
        when(service.getUploadOffset("u")).thenReturn(Mono.just(0L));
        when(service.getUploadLength("u")).thenReturn(Mono.just(1024L));
        when(service.uploadChunk(eq("u"), eq(0L), any())).thenReturn(Mono.just(1024L));

        StepVerifier.create(controller.uploadChunk("u", 0L, 1024L, body))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
                .verifyComplete();
    }
}
