package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.dto.signature.ApplySignatureRequest;
import org.openfilz.dms.dto.signature.DeclineSignatureRequest;
import org.openfilz.dms.dto.signature.PublicSignatureView;
import org.openfilz.dms.dto.signature.VerifyOtpRequest;
import org.openfilz.dms.service.SignatureService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Signer-facing e-Sign endpoints. No OIDC session — the signing token in the query string is
 * the authenticator (validated by hash lookup in the service), optionally hardened with an OTP.
 * Lives under {@code /public/signatures/**}, which has its own permit-all security chain
 * ({@code SignaturePublicSecurityConfig}); the chain is inert when the feature is off, and the
 * controller additionally answers 404.
 */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_PUBLIC_SIGNATURES)
@RequiredArgsConstructor
@Tag(name = "e-Sign — Public", description = "Token-only signing flow for recipients.")
public class PublicSignatureController {

    private final SignatureService service;
    private final SignatureProperties props;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Resolve a signing token to its envelope view")
    public Mono<PublicSignatureView> view(@RequestParam String token) {
        requireActive();
        return service.getByToken(token);
    }

    @PostMapping(value = "/viewed", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Record that the recipient opened the document")
    public Mono<PublicSignatureView> markViewed(@RequestParam String token,
                                                @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String ua,
                                                ServerWebExchange exchange) {
        requireActive();
        return service.recordView(token, clientIp(exchange), ua);
    }

    @GetMapping(value = "/document", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Stream the source PDF for signing")
    public Mono<ResponseEntity<Resource>> document(@RequestParam String token) {
        requireActive();
        return service.loadDocumentByToken(token)
                .map(res -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .body(res));
    }

    @PostMapping("/otp/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Send a one-time access code to the recipient (EMAIL_OTP / SMS_OTP)")
    public Mono<Void> requestOtp(@RequestParam String token) {
        requireActive();
        return service.requestOtp(token);
    }

    @PostMapping(value = "/otp/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Verify the one-time access code")
    public Mono<PublicSignatureView> verifyOtp(@RequestParam String token, @Valid @RequestBody VerifyOtpRequest req,
                                               ServerWebExchange exchange) {
        requireActive();
        return service.verifyOtp(token, req.code(), clientIp(exchange));
    }

    @PostMapping(value = "/sign", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Submit the recipient's fields and sign")
    public Mono<PublicSignatureView> sign(@RequestParam String token,
                                          @Valid @RequestBody ApplySignatureRequest req,
                                          @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String ua,
                                          ServerWebExchange exchange) {
        requireActive();
        return service.applySignature(token, req, clientIp(exchange), ua);
    }

    @PostMapping(value = "/decline", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Decline to sign (voids the envelope)")
    public Mono<PublicSignatureView> decline(@RequestParam String token,
                                             @Valid @RequestBody(required = false) DeclineSignatureRequest req,
                                             ServerWebExchange exchange) {
        requireActive();
        return service.decline(token, req, clientIp(exchange));
    }

    private void requireActive() {
        if (!props.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "e-Sign feature is disabled");
        }
    }

    static String clientIp(ServerWebExchange exchange) {
        var fwd = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        var addr = exchange.getRequest().getRemoteAddress();
        return addr != null ? addr.getAddress().getHostAddress() : null;
    }
}
