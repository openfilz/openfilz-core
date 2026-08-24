package org.openfilz.dms.service;

import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureEvent;
import org.openfilz.dms.entity.SignatureField;
import org.openfilz.dms.entity.SignatureRecipient;

import java.util.List;

/**
 * PDF generation for e-Sign: stamps every filled field (signature images, initials, typed
 * text, dates, checkboxes…) at its placed position and appends a Certificate of Completion
 * page rendered from the audit trail. The cryptographic seal is applied afterwards by the
 * {@link org.openfilz.dms.service.signature.SignatureSealer}.
 *
 * <p>Pure / synchronous on purpose — callers run it on a bounded-elastic scheduler.
 */
public interface SignaturePdfService {

    /** Lowercase hex SHA-256 of arbitrary bytes (tamper anchor). */
    String sha256Hex(byte[] data);

    /** Number of pages of a PDF (validation of field placements). Throws on a non-PDF. */
    int pageCount(byte[] pdf);

    /** Stamp all filled fields + append the Certificate of Completion. Not sealed. */
    byte[] buildStampedDocument(byte[] originalPdf,
                                SignatureEnvelope envelope,
                                List<SignatureRecipient> recipients,
                                List<SignatureField> fields,
                                List<SignatureEvent> events);
}
