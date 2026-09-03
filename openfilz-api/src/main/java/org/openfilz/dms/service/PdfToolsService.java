package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.pdf.MergeRequest;
import org.openfilz.dms.dto.request.pdf.OrganizeRequest;
import org.openfilz.dms.dto.request.pdf.RotateRequest;
import org.openfilz.dms.dto.request.pdf.SplitRequest;
import org.openfilz.dms.dto.response.pdf.PdfInfo;
import org.openfilz.dms.dto.response.pdf.PdfOperationResponse;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * PDF tools: merge, split, rotate and page organisation of PDFs stored in the DMS.
 * <p>
 * Sources are read with the caller's read access; outputs go through the regular upload / replace
 * pipelines (audit, versioning, checksum, quota, thumbnails, indexing) and are additionally
 * audited as {@code PDF_TRANSFORM} with their provenance. Implementations are the single place
 * the REST controller, the AI assistant and the MCP server delegate to.
 */
public interface PdfToolsService {

    /** Structure of a stored PDF (page count, geometry, bookmarks, encrypted / signed flags). */
    Mono<PdfInfo> info(UUID documentId);

    /** Merge several PDFs (or page selections of them) into one document. */
    Mono<PdfOperationResponse> merge(MergeRequest request);

    /** Split one PDF into several new documents. */
    Mono<PdfOperationResponse> split(SplitRequest request);

    /** Compose a PDF from an explicit ordered list of pages (reorder / delete / rotate / extract / insert). */
    Mono<PdfOperationResponse> organize(OrganizeRequest request);

    /** Rotate pages of one or several PDFs, one output per document. */
    Mono<PdfOperationResponse> rotate(RotateRequest request);
}
