package org.openfilz.dms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the PDF tools feature ({@code openfilz.pdf-tools.*}): merge, split, rotate and
 * page organisation of PDF documents already stored in the DMS.
 * <p>
 * {@link #active} is a <b>runtime</b> toggle (read per request, never a bean condition) so a single
 * native image serves both enabled and disabled deployments — same shape as
 * {@code openfilz.signature.active}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "openfilz.pdf-tools")
public class PdfToolsProperties {

    /** Master switch. Off: the controller answers 404 and the AI/MCP tools refuse politely. */
    private boolean active = true;

    /** Maximum total size in bytes of the source PDFs of one operation (default 200 MB). */
    private long maxInputBytes = 200L * 1024 * 1024;

    /** Maximum total number of source pages of one operation. */
    private int maxPages = 2000;

    /** Maximum number of documents one split may produce. */
    private int maxOutputs = 200;

    /**
     * How many PDF compositions may run at the same time on this instance. PDFBox work is CPU and
     * temp-file bound; the rest queue briefly on the bounded elastic scheduler.
     */
    private int maxConcurrentOperations = 2;

    /** Seconds a request waits for a free slot before answering 503. */
    private int slotWaitSeconds = 60;
}
