package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureEvent;
import org.openfilz.dms.entity.SignatureField;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.enums.SignatureRecipientStatus;
import org.openfilz.dms.service.SignaturePdfService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PDFBox implementation. Each field type has a dedicated renderer:
 * <ul>
 *   <li>SIGNATURE / INITIALS / IMAGE / STAMP — the base64 PNG scaled into the box (or the typed
 *       value in an oblique font when the signer typed instead of drawing);</li>
 *   <li>TEXT / NUMBER / EMAIL / PHONE / SELECT / RADIO / DATE_SIGNED — the value in Helvetica,
 *       shrunk to fit the box;</li>
 *   <li>CHECKBOX — a box with a cross when "true".</li>
 * </ul>
 * Signature fields get a "Signed by … — timestamp" caption below (or above when near the
 * page bottom). A Certificate of Completion page is appended with signers + audit trail.
 */
@Slf4j
@Service
public class SignaturePdfServiceImpl implements SignaturePdfService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

    @Override
    public String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public int pageCount(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a readable PDF", e);
        }
    }

    @Override
    public byte[] buildStampedDocument(byte[] originalPdf, SignatureEnvelope envelope,
                                       List<SignatureRecipient> recipients, List<SignatureField> fields,
                                       List<SignatureEvent> events) {
        Map<java.util.UUID, SignatureRecipient> byId = recipients.stream()
                .collect(Collectors.toMap(SignatureRecipient::getId, Function.identity()));
        try (PDDocument doc = Loader.loadPDF(originalPdf)) {
            for (SignatureField f : fields) {
                SignatureRecipient r = byId.get(f.getRecipientId());
                if (r == null || r.getStatus() != SignatureRecipientStatus.SIGNED) continue;
                if (!f.isFilled()) continue;
                if (f.getPage() < 0 || f.getPage() >= doc.getNumberOfPages()) continue;
                PDPage page = doc.getPage(f.getPage());
                PDRectangle box = page.getMediaBox();
                float x = (float) (f.getX() * box.getWidth()) + box.getLowerLeftX();
                float y = (float) (f.getY() * box.getHeight()) + box.getLowerLeftY();
                float w = (float) (f.getW() * box.getWidth());
                float h = (float) (f.getH() * box.getHeight());
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    renderField(doc, cs, f, r, box, x, y, w, h);
                }
            }
            appendCertificatePage(doc, envelope, recipients, events);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stamp / certify PDF", e);
        }
    }

    private void renderField(PDDocument doc, PDPageContentStream cs, SignatureField f, SignatureRecipient r,
                             PDRectangle box, float x, float y, float w, float h) throws IOException {
        SignatureFieldType type = f.getType();
        if (type.isImage()) {
            if (f.getValueImage() != null && !f.getValueImage().isBlank()) {
                byte[] png = decodeImage(f.getValueImage());
                PDImageXObject img = PDImageXObject.createFromByteArray(doc, png, "sig");
                // Preserve aspect ratio inside the box.
                float ratio = Math.min(w / img.getWidth(), h / img.getHeight());
                float dw = img.getWidth() * ratio, dh = img.getHeight() * ratio;
                cs.drawImage(img, x + (w - dw) / 2f, y + (h - dh) / 2f, dw, dh);
            } else if (f.getValue() != null) {
                text(cs, f.getValue(), Standard14Fonts.FontName.HELVETICA_OBLIQUE, x, y, w, h, 18f);
            }
            if (type == SignatureFieldType.SIGNATURE) {
                caption(cs, box, x, y, h, "Signed by " + recipientLabel(r) + " — " + fmt(r.getSignedAt()));
            }
            return;
        }
        switch (type) {
            case CHECKBOX -> {
                float size = Math.min(w, h);
                float bx = x + (w - size) / 2f, by = y + (h - size) / 2f;
                cs.setLineWidth(1f);
                cs.addRect(bx, by, size, size);
                cs.stroke();
                if (Boolean.parseBoolean(f.getValue())) {
                    cs.moveTo(bx + size * 0.2f, by + size * 0.5f);
                    cs.lineTo(bx + size * 0.42f, by + size * 0.22f);
                    cs.lineTo(bx + size * 0.82f, by + size * 0.8f);
                    cs.setLineWidth(Math.max(1.2f, size * 0.1f));
                    cs.stroke();
                }
            }
            default -> text(cs, f.getValue(), Standard14Fonts.FontName.HELVETICA, x, y, w, h, 12f);
        }
    }

    /** Draw text vertically centred in the box, shrinking the font so it fits horizontally. */
    private static void text(PDPageContentStream cs, String value, Standard14Fonts.FontName fontName,
                             float x, float y, float w, float h, float maxSize) throws IOException {
        if (value == null || value.isBlank()) return;
        PDType1Font font = new PDType1Font(fontName);
        String s = sanitize(value);
        float size = Math.min(maxSize, h * 0.6f);
        float width = font.getStringWidth(s) / 1000f * size;
        if (width > w - 4f && width > 0f) {
            size = Math.max(4f, size * (w - 4f) / width);
        }
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x + 2f, y + (h - size * 0.7f) / 2f);
        cs.showText(s);
        cs.endText();
    }

    /** "Signed by …" line below the field, flipped above when the field sits near the page bottom. */
    private static void caption(PDPageContentStream cs, PDRectangle box, float x, float y, float h, String caption)
            throws IOException {
        float pageBottom = box.getLowerLeftY(), pageTop = box.getUpperRightY();
        float captionY = (y - 10f >= pageBottom) ? (y - 8f) : (y + h + 4f);
        if (captionY + 6f > pageTop) captionY = pageTop - 8f;
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        String s = sanitize(caption);
        float pageLeft = box.getLowerLeftX(), pageRight = box.getUpperRightX(), margin = 4f;
        float available = (pageRight - pageLeft) - 2f * margin;
        float size = 6f;
        float textWidth = font.getStringWidth(s) / 1000f * size;
        if (textWidth > available && textWidth > 0f) {
            size = Math.max(4f, size * available / textWidth);
            textWidth = font.getStringWidth(s) / 1000f * size;
        }
        float captionX = Math.max(pageLeft + margin, Math.min(x, pageRight - margin - textWidth));
        cs.setFont(font, size);
        cs.beginText();
        cs.newLineAtOffset(captionX, captionY);
        cs.showText(s);
        cs.endText();
    }

    private void appendCertificatePage(PDDocument doc, SignatureEnvelope envelope,
                                       List<SignatureRecipient> recipients, List<SignatureEvent> events)
            throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        float margin = 50f;
        float y = page.getMediaBox().getHeight() - margin;
        PDPageContentStream cs = new PDPageContentStream(doc, page);
        try {
            y = line(cs, 18, margin, y, "Certificate of Completion", true);
            y -= 6;
            y = line(cs, 10, margin, y, "Envelope: " + envelope.getTitle(), false);
            y = line(cs, 9, margin, y, "Envelope ID: " + envelope.getId(), false);
            y = line(cs, 9, margin, y, "Initiated by: " + envelope.getInitiatorEmail(), false);
            y = line(cs, 9, margin, y, "Signing mode: " + (envelope.isSequential() ? "sequential" : "parallel"), false);
            y = line(cs, 9, margin, y, "Original SHA-256: " + nv(envelope.getOriginalSha256()), false);
            y -= 10;
            y = line(cs, 12, margin, y, "Signers", true);
            for (SignatureRecipient r : recipients) {
                y = line(cs, 9, margin, y, "- " + recipientLabel(r)
                        + (r.isSigner() ? "" : "  [CC]")
                        + "  [" + r.getStatus() + "]"
                        + (r.getSignedAt() != null ? "  signed " + fmt(r.getSignedAt()) : "")
                        + (r.getSignerIp() != null ? "  IP " + r.getSignerIp() : "")
                        + (r.getOtpVerifiedAt() != null ? "  OTP " + r.getAuthMethod() : ""), false);
            }
            y -= 10;
            y = line(cs, 12, margin, y, "Audit trail", true);
            for (SignatureEvent e : events) {
                if (y < margin + 20) {
                    cs.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                    y = page.getMediaBox().getHeight() - margin;
                    y = line(cs, 12, margin, y, "Audit trail (continued)", true);
                }
                y = line(cs, 8, margin, y, fmt(e.getCreatedAt()) + "  " + e.getEventType()
                        + "  " + nv(e.getActor())
                        + (e.getDetails() != null ? "  (" + e.getDetails() + ")" : ""), false);
            }
        } finally {
            cs.close();
        }
    }

    private float line(PDPageContentStream cs, float size, float x, float y, String text, boolean bold)
            throws IOException {
        cs.beginText();
        cs.setFont(new PDType1Font(bold ? Standard14Fonts.FontName.HELVETICA_BOLD
                : Standard14Fonts.FontName.HELVETICA), size);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitize(text));
        cs.endText();
        return y - (size + 5);
    }

    private static byte[] decodeImage(String b64) {
        int comma = b64.indexOf(',');
        String payload = b64.startsWith("data:") && comma > 0 ? b64.substring(comma + 1) : b64;
        return Base64.getMimeDecoder().decode(payload);
    }

    private static String recipientLabel(SignatureRecipient r) {
        return (r.getRecipientName() != null && !r.getRecipientName().isBlank())
                ? r.getRecipientName() + " <" + r.getRecipientEmail() + ">"
                : r.getRecipientEmail();
    }

    private static String fmt(OffsetDateTime t) {
        return t == null ? "-" : t.atZoneSameInstant(ZoneOffset.UTC).format(TS);
    }

    private static String nv(String s) {
        return s == null ? "-" : s;
    }

    /** WinAnsi (Standard14 fonts) cannot render arbitrary Unicode — keep it ASCII-safe. */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
