package org.openfilz.dms.exception;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DocumentNotFoundException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleDocumentNotFound(DocumentNotFoundException ex) {
        log.warn("Document not found: {}", ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(HttpStatus.NOT_FOUND.value(), ex.getMessage())));
    }

    @ExceptionHandler(DuplicateNameException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleDuplicateName(DuplicateNameException ex) {
        log.warn("Duplicate name: {}", ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(HttpStatus.CONFLICT.value(), ex.getMessage())));
    }

    @ExceptionHandler(FileSizeExceededException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleFileSizeExceeded(FileSizeExceededException ex) {
        log.warn("File size exceeded: {}", ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(new ErrorResponse(HttpStatus.CONTENT_TOO_LARGE.value(), ex.getMessage(), ex.getError())));
    }

    @ExceptionHandler(UserQuotaExceededException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleUserQuotaExceeded(UserQuotaExceededException ex) {
        log.warn("User quota exceeded: {}", ex.getMessage());
        // HTTP 507 Insufficient Storage is appropriate for quota exceeded scenarios
        return Mono.just(ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE).body(new ErrorResponse(HttpStatus.INSUFFICIENT_STORAGE.value(), ex.getMessage(), ex.getError())));
    }

    @ExceptionHandler(InstanceQuotaExceededException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleInstanceQuotaExceeded(InstanceQuotaExceededException ex) {
        log.warn("Instance quota exceeded: {}", ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE).body(new ErrorResponse(HttpStatus.INSUFFICIENT_STORAGE.value(), ex.getMessage(), ex.getError())));
    }

    @ExceptionHandler(OperationForbiddenException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleOperationForbidden(OperationForbiddenException ex) {
        log.warn("Operation forbidden: {}", ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(HttpStatus.FORBIDDEN.value(), ex.getMessage())));
    }

    @ExceptionHandler(StorageException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleStorageException(StorageException ex) {
        log.error("Storage exception: {}", ex.getMessage(), ex.getCause());
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Storage operation failed: " + ex.getMessage())));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleValidationExceptions(WebExchangeBindException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        String errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Validation failed: " + errors)));
    }

    @ExceptionHandler(VersioningDisabledException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleVersioningDisabled(VersioningDisabledException ex) {
        log.warn("Versioning disabled: {}", ex.getMessage());
        // 409 (not 404) so clients can distinguish "feature off" from "version not found"
        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(HttpStatus.CONFLICT.value(), ex.getMessage())));
    }

    @ExceptionHandler(CannotRestoreLatestVersionException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleCannotRestoreLatestVersion(CannotRestoreLatestVersionException ex) {
        log.warn("Cannot restore latest version: {}", ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage())));
    }

    @ExceptionHandler(AuditException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleAuditException(AuditException ex) {
        log.error("AuditException", ex);
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Exception while processing Audit trail for this request")));
    }

    @ExceptionHandler(PdfToolsException.class)
    public Mono<ResponseEntity<ErrorResponse>> handlePdfTools(PdfToolsException ex) {
        log.warn("PDF tools refused ({}): {}", ex.getStatus(), ex.getMessage());
        return Mono.just(ResponseEntity.status(ex.getStatus()).body(new ErrorResponse(ex.getStatus().value(), ex.getMessage())));
    }

    @ExceptionHandler(UnzipException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleUnzip(UnzipException ex) {
        log.warn("Unzip refused ({}): {}", ex.getStatus(), ex.getMessage());
        return Mono.just(ResponseEntity.status(ex.getStatus()).body(new ErrorResponse(ex.getStatus().value(), ex.getMessage())));
    }

    @ExceptionHandler(WorkflowValidationException.class)
    public Mono<ResponseEntity<WorkflowValidationException.Body>> handleWorkflowValidation(WorkflowValidationException ex) {
        log.debug("Workflow definition rejected: {}", ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new WorkflowValidationException.Body(HttpStatus.BAD_REQUEST.value(), ex.getMessage(), ex.getProblems())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Illegal argument : {}", ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage())));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleAccessDeniedException(AccessDeniedException ex) {
        log.warn("AccessDeniedException", ex);
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(HttpStatus.FORBIDDEN.value(), ex.getMessage())));
    }

    /**
     * Spring/WebFlux throws ResponseStatusException for things like "no handler matched" (404),
     * bad method (405), content-type mismatch (415), etc. These are client-side issues — we must
     * (a) preserve the original status code (NOT rewrite to 500) and (b) log at a sane level so
     * routine 404 polls don't flood the log as ERRORs with full reactor stack traces.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleResponseStatus(ResponseStatusException ex, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        String path = exchange.getRequest().getMethod() + " " + exchange.getRequest().getPath().value();
        if (status != null && status.is5xxServerError()) {
            log.warn("{} -> {} {}", path, ex.getStatusCode(), ex.getReason(), ex);
        } else {
            log.debug("{} -> {} {}", path, ex.getStatusCode(), ex.getReason());
        }
        String msg = ex.getReason() != null ? ex.getReason() : (status != null ? status.getReasonPhrase() : "Error");
        return Mono.just(ResponseEntity.status(ex.getStatusCode()).body(new ErrorResponse(ex.getStatusCode().value(), msg)));
    }

    /**
     * MCP SDK 2.0.0's stateless handler turns a failing request handler into a JSON-RPC error
     * response, but not an unknown method: {@code DefaultMcpStatelessServerHandler} answers it with
     * {@code Mono.error(McpError)}, which escapes {@code WebFluxStatelessServerTransport} and lands
     * here. Clients probe optional methods (Claude Code sends {@code server/discover} before
     * {@code initialize}) and fall back on the error, so this is routine — answer with the JSON-RPC
     * error instead of a 500 + stack trace. The request id is gone by now (the body was consumed),
     * hence {@code id: null}, as JSON-RPC allows when it cannot be determined.
     */
    @ExceptionHandler(McpError.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleMcpError(McpError ex) {
        McpSchema.JSONRPCResponse.JSONRPCError error = ex.getJsonRpcError();
        int code = error.code() != null ? error.code() : McpSchema.ErrorCodes.INTERNAL_ERROR;
        if (code == McpSchema.ErrorCodes.METHOD_NOT_FOUND) {
            log.debug("MCP: {}", error.message());
        } else {
            log.warn("MCP error {}: {}", code, error.message());
        }
        Map<String, Object> jsonRpcError = new LinkedHashMap<>();
        jsonRpcError.put("code", code);
        jsonRpcError.put("message", error.message());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", McpSchema.JSONRPC_VERSION);
        body.put("id", null);
        body.put("error", jsonRpcError);
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body));
    }

    @ExceptionHandler(Throwable.class)
    public Mono<ResponseEntity<ErrorResponse>> handleGenericException(Throwable ex) {
        log.error("An unexpected error occurred", ex);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "An unexpected error occurred. Please try again later.")));
    }

    @ExceptionHandler(VirusFoundException.class)
    public  Mono<ResponseEntity<ErrorResponse>> handleVirusFoundException(VirusFoundException ex) {
        log.warn(ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage())));
    }

    /**
     * Error body. {@code error} is the machine-readable code of an {@link AbstractOpenFilzException}
     * ({@link OpenFilzException} constants, e.g. {@code UserQuotaExceeded}) when one applies — a client
     * branches on it instead of parsing the message; absent otherwise.
     */
    public record ErrorResponse(int status, String message,
                                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String error) {
        public ErrorResponse(int status, String message) {
            this(status, message, null);
        }
    }
}