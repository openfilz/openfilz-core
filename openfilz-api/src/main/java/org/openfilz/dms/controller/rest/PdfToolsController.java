package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.PdfToolsProperties;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.pdf.MergeRequest;
import org.openfilz.dms.dto.request.pdf.OrganizeRequest;
import org.openfilz.dms.dto.request.pdf.RotateRequest;
import org.openfilz.dms.dto.request.pdf.SplitRequest;
import org.openfilz.dms.dto.response.pdf.PdfInfo;
import org.openfilz.dms.dto.response.pdf.PdfOperationResponse;
import org.openfilz.dms.service.PdfToolsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * PDF tools: merge, split, rotate and page organisation of PDFs stored in the DMS. Always mapped;
 * every call gates on {@code openfilz.pdf-tools.active} at runtime (404 when off) so the toggle
 * works in native images. Reads need READER or CONTRIBUTOR, writes need CONTRIBUTOR
 * ({@code AbstractSecurityService}); per-document access is enforced by the document DAO.
 * <p>
 * Request bodies are validated by the service (400 on a bad argument) rather than with
 * {@code @Valid}: bean validation runs before the handler, and a disabled deployment must answer
 * 404 whatever the body.
 */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_PDF)
@RequiredArgsConstructor
@Tag(name = "PDF tools", description = "Merge, split, rotate and reorganise the pages of stored PDFs. "
        + "Results are regular documents (new document or new version) with audit trail and provenance.")
@SecurityRequirement(name = "keycloak_auth")
public class PdfToolsController {

    private final PdfToolsService service;
    private final PdfToolsProperties props;

    @GetMapping(value = "/{documentId}/info", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Describe a stored PDF",
            description = "Page count and geometry, bookmarks, and whether the file is password-protected or digitally signed.")
    public Mono<PdfInfo> info(@Parameter(description = "PDF document id") @PathVariable UUID documentId) {
        requireActive();
        return service.info(documentId);
    }

    @PostMapping(value = "/merge", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Merge PDFs",
            description = "Concatenate several PDFs (or page selections of them) into one document, in the given order, "
                    + "optionally with one bookmark per source. Default output: a new document next to the first source.")
    public Mono<PdfOperationResponse> merge(@RequestBody MergeRequest request) {
        requireActive();
        return service.merge(request);
    }

    @PostMapping(value = "/split", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Split a PDF",
            description = "Cut one PDF into several new documents: every N pages, at given pages, by explicit page ranges, "
                    + "one document per page, or at bookmarks. Names follow a pattern ({name}, {index}, {first}, {last}, {title}).")
    public Mono<PdfOperationResponse> split(@RequestBody SplitRequest request) {
        requireActive();
        return service.split(request);
    }

    @PostMapping(value = "/organize", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Reorganise the pages of a PDF",
            description = "The result is exactly the listed pages, in order: reorder, delete, duplicate, rotate individual pages, "
                    + "extract a selection, or insert pages from other PDFs. Default output: a new version of the main document.")
    public Mono<PdfOperationResponse> organize(@RequestBody OrganizeRequest request) {
        requireActive();
        return service.organize(request);
    }

    @PostMapping(value = "/rotate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rotate pages",
            description = "Rotate all or selected pages of one or several PDFs by 90, 180 or 270 degrees, one output per document. "
                    + "Default output: a new version of each document.")
    public Mono<PdfOperationResponse> rotate(@RequestBody RotateRequest request) {
        requireActive();
        return service.rotate(request);
    }

    private void requireActive() {
        if (!props.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF tools are disabled");
        }
    }
}
