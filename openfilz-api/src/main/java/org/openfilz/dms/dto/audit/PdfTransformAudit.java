package org.openfilz.dms.dto.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Provenance of a document produced or replaced by the PDF tools: which operation, from which
 * sources. Logged as {@code PDF_TRANSFORM} on the output document, next to the regular
 * {@code UPLOAD_DOCUMENT} / {@code REPLACE_DOCUMENT_CONTENT} entry the write pipeline adds.
 */
@JsonTypeName(AuditLogDetails.PDF_TRANSFORM)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = AuditLogDetails.DISCRIMINATOR + "=" + AuditLogDetails.PDF_TRANSFORM)
public class PdfTransformAudit extends AuditLogDetails {

    @Schema(description = "merge, split, organize or rotate")
    private String operation;

    @Schema(description = "Source documents the output was built from")
    private List<UUID> sourceDocumentIds;

    @Schema(description = "Pages in the output")
    private Integer pageCount;

    @Schema(description = "Name of the output document")
    private String filename;
}
