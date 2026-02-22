package org.openfilz.dms.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleDocumentNotFound_returns404() {
        DocumentNotFoundException ex = new DocumentNotFoundException(UUID.randomUUID());

        StepVerifier.create(handler.handleDocumentNotFound(ex))
                .expectNextMatches(response -> {
                    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
                    assertEquals(404, response.getBody().status());
                    assertTrue(response.getBody().message().contains("Document not found"));
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void handleDuplicateName_returns409() {
        DuplicateNameException ex = new DuplicateNameException("duplicate");

        StepVerifier.create(handler.handleDuplicateName(ex))
                .expectNextMatches(response -> {
                    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
                    assertEquals(409, response.getBody().status());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void handleFileSizeExceeded_returns413() {
        FileSizeExceededException ex = new FileSizeExceededException("big.zip", 200L * 1024 * 1024, 100L * 1024 * 1024);

        StepVerifier.create(handler.handleFileSizeExceeded(ex))
                .expectNextMatches(response -> {
                    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
                    assertEquals(413, response.getBody().status());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void handleUserQuotaExceeded_returns507() {
        UserQuotaExceededException ex = new UserQuotaExceededException("user@test.com", 900L * 1024 * 1024, 200L * 1024 * 1024, 1024L * 1024 * 1024);

        StepVerifier.create(handler.handleUserQuotaExceeded(ex))
                .expectNextMatches(response -> {
                    assertEquals(HttpStatus.INSUFFICIENT_STORAGE, response.getStatusCode());
                    assertEquals(507, response.getBody().status());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void handleOperationForbidden_returns403() {
        OperationForbiddenException ex = new OperationForbiddenException("forbidden");

        StepVerifier.create(handler.handleOperationForbidden(ex))
                .expectNextMatches(response -> {
                    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
                    assertEquals(403, response.getBody().status());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void handleStorageException_returns500() {
        StorageException ex = new StorageException("disk full");

        StepVerifier.create(handler.handleStorageException(ex))
                .expectNextMatches(response -> {
                    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
                    assertEquals(500, response.getBody().status());
                    assertTrue(response.getBody().message().contains("Storage operation failed"));
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void handleValidationExceptions_returns400WithFieldErrors() {
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError1 = new FieldError("object", "name", "must not be blank");
        FieldError fieldError2 = new FieldError("object", "email", "must be a valid email");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError1, fieldError2));

        WebExchangeBindException ex = mock(WebExchangeBindException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(ex.getMessage()).thenReturn("Validation failed");

        StepVerifier.create(handler.handleValidationExceptions(ex))
                .expectNextMatches(response -> {
                    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                    assertEquals(400, response.getBody().status());
                    assertTrue(response.getBody().message().contains("name: must not be blank"));
                    assertTrue(response.getBody().message().contains("email: must be a valid email"));
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void handleAuditException_returns400() {
        AuditException ex = new AuditException("audit trail error", new RuntimeException("cause"));

        StepVerifier.create(handler.handleAuditException(ex))
                .expectNextMatches(response -> {
                    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                    assertEquals(400, response.getBody().status());
                    assertTrue(response.getBody().message().contains("Audit trail"));
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void handleIllegalArgumentException_returns400() {
        IllegalArgumentException ex = new IllegalArgumentException("bad input");

        StepVerifier.create(handler.handleIllegalArgumentException(ex))
                .expectNextMatches(response -> {
                    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                    assertEquals(400, response.getBody().status());
                    assertEquals("bad input", response.getBody().message());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void handleAccessDeniedException_returns403() {
        AccessDeniedException ex = new AccessDeniedException("denied");

        StepVerifier.create(handler.handleAccessDeniedException(ex))
                .expectNextMatches(response -> {
                    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
                    assertEquals(403, response.getBody().status());
                    assertEquals("denied", response.getBody().message());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void handleGenericException_returns500() {
        StepVerifier.create(handler.handleGenericException(new Exception("unknown")))
                .expectNextMatches(response -> {
                    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
                    assertEquals(500, response.getBody().status());
                    assertEquals("An unexpected error occurred. Please try again later.", response.getBody().message());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void handleVirusFoundException_returns500WithBadRequestStatus() {
        VirusFoundException ex = new VirusFoundException(List.of("EICAR-Test"));

        StepVerifier.create(handler.handleVirusFoundException(ex))
                .expectNextMatches(response -> {
                    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
                    assertEquals(400, response.getBody().status());
                    assertTrue(response.getBody().message().contains("Virus detected"));
                    return true;
                })
                .verifyComplete();
    }
}
