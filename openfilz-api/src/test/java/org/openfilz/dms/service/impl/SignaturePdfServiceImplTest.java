package org.openfilz.dms.service.impl;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureEvent;
import org.openfilz.dms.entity.SignatureField;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureEventType;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.enums.SignatureRecipientRole;
import org.openfilz.dms.enums.SignatureRecipientStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link SignaturePdfServiceImpl} (PDFBox stamping + Certificate of Completion). */
class SignaturePdfServiceImplTest {

    private static byte[] originalPdf;
    private static int originalPages;
    private static String tinyPngB64;

    private final SignaturePdfServiceImpl service = new SignaturePdfServiceImpl();

    @BeforeAll
    static void loadFixture() throws IOException {
        try (var in = SignaturePdfServiceImplTest.class.getResourceAsStream("/pdf-example.pdf")) {
            assertThat(in).as("pdf-example.pdf fixture").isNotNull();
            originalPdf = in.readAllBytes();
        }
        try (PDDocument doc = Loader.loadPDF(originalPdf)) {
            originalPages = doc.getNumberOfPages();
        }
        BufferedImage img = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 8; x++) for (int y = 0; y < 4; y++) img.setRGB(x, y, 0xFF000000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        tinyPngB64 = Base64.getEncoder().encodeToString(out.toByteArray());
    }

    // ── sha256Hex / pageCount ─────────────────────────────────────────────

    @Test
    void sha256Hex_knownVector() {
        assertThat(service.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(service.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void pageCount_readsFixture() {
        assertThat(service.pageCount(originalPdf)).isEqualTo(originalPages).isPositive();
    }

    @Test
    void pageCount_garbage_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.pageCount("definitely not a pdf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a readable PDF");
    }

    // ── buildStampedDocument ──────────────────────────────────────────────

    @Test
    void buildStampedDocument_everyFieldType_addsCertificatePage() throws IOException {
        SignatureRecipient signed = recipient("Alice Signer", "alice@example.com", SignatureRecipientStatus.SIGNED);
        signed.setSignerIp("10.0.0.1");
        signed.setOtpVerifiedAt(OffsetDateTime.now());
        signed.setAuthMethod(SignatureAuthMethod.EMAIL_OTP);
        SignatureRecipient pending = recipient(null, "bob@example.com", SignatureRecipientStatus.PENDING);
        SignatureRecipient cc = recipient("Carol", "carol@example.com", SignatureRecipientStatus.PENDING);
        cc.setRole(SignatureRecipientRole.CC);
        SignatureEnvelope env = envelope("Lease agreement", true);

        List<SignatureField> fields = new ArrayList<>();
        // image types: drawn PNG
        for (SignatureFieldType t : SignatureFieldType.values()) {
            if (t.isImage()) {
                fields.add(field(signed.getId(), t, 0, 0.1, 0.5, 0.25, 0.08, null, "data:image/png;base64," + tinyPngB64));
            }
        }
        // text-like types
        fields.add(field(signed.getId(), SignatureFieldType.TEXT, 0, 0.1, 0.3, 0.3, 0.04, "Some free text value that is deliberately long to force shrinking", null));
        fields.add(field(signed.getId(), SignatureFieldType.NUMBER, 0, 0.1, 0.25, 0.1, 0.04, "42", null));
        fields.add(field(signed.getId(), SignatureFieldType.EMAIL, 0, 0.1, 0.2, 0.3, 0.04, "alice@example.com", null));
        fields.add(field(signed.getId(), SignatureFieldType.PHONE, 0, 0.1, 0.15, 0.2, 0.04, "+33 6 12 34 56 78", null));
        fields.add(field(signed.getId(), SignatureFieldType.SELECT, 0, 0.5, 0.3, 0.2, 0.04, "Option B", null));
        fields.add(field(signed.getId(), SignatureFieldType.RADIO, 0, 0.5, 0.25, 0.2, 0.04, "yes", null));
        fields.add(field(signed.getId(), SignatureFieldType.DATE_SIGNED, 0, 0.5, 0.2, 0.2, 0.04, "2026-08-21", null));
        fields.add(field(signed.getId(), SignatureFieldType.CHECKBOX, 0, 0.5, 0.15, 0.05, 0.03, "true", null));
        fields.add(field(signed.getId(), SignatureFieldType.CHECKBOX, 0, 0.6, 0.15, 0.05, 0.03, "false", null));
        // unicode → sanitized to '?', must not throw with WinAnsi fonts
        fields.add(field(signed.getId(), SignatureFieldType.TEXT, 0, 0.5, 0.1, 0.3, 0.04, "Zoë — 日本語 ✓", null));
        // unfilled field of a signed recipient → skipped
        fields.add(field(signed.getId(), SignatureFieldType.TEXT, 0, 0.1, 0.1, 0.2, 0.04, null, null));
        fields.add(field(signed.getId(), SignatureFieldType.SIGNATURE, 0, 0.1, 0.6, 0.2, 0.06, null, "   "));
        // out-of-range page → skipped
        fields.add(field(signed.getId(), SignatureFieldType.TEXT, 999, 0.1, 0.1, 0.2, 0.04, "never rendered", null));
        fields.add(field(signed.getId(), SignatureFieldType.TEXT, -1, 0.1, 0.1, 0.2, 0.04, "never rendered", null));
        // fields of non-SIGNED recipients → skipped even when filled
        fields.add(field(pending.getId(), SignatureFieldType.SIGNATURE, 0, 0.1, 0.7, 0.2, 0.06, null, tinyPngB64));
        fields.add(field(cc.getId(), SignatureFieldType.TEXT, 0, 0.1, 0.7, 0.2, 0.06, "cc text", null));
        // unknown recipient id → skipped
        fields.add(field(UUID.randomUUID(), SignatureFieldType.TEXT, 0, 0.1, 0.8, 0.2, 0.04, "orphan", null));

        OffsetDateTime t0 = OffsetDateTime.of(2026, 8, 21, 10, 0, 0, 0, ZoneOffset.UTC);
        List<SignatureEvent> events = List.of(
                event(SignatureEventType.ENVELOPE_CREATED, "alice@example.com", t0, null),
                event(SignatureEventType.ENVELOPE_SENT, "alice@example.com", t0.plusMinutes(1), "2 recipients"),
                event(SignatureEventType.RECIPIENT_VIEWED, "alice@example.com", t0.plusMinutes(2), null),
                event(SignatureEventType.RECIPIENT_SIGNED, null, t0.plusMinutes(3), "ip=10.0.0.1"));

        byte[] out = service.buildStampedDocument(originalPdf, env, List.of(signed, pending, cc), fields, events);

        try (PDDocument doc = Loader.loadPDF(out)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(originalPages + 1);
            String cert = textOfPage(doc, doc.getNumberOfPages());
            assertThat(cert)
                    .contains("Certificate of Completion")
                    .contains("Lease agreement")
                    .contains(env.getId().toString())
                    .contains("alice@example.com")
                    .contains("bob@example.com")
                    .contains("carol@example.com")
                    .contains("[CC]")
                    .contains("sequential")
                    .contains("IP 10.0.0.1")
                    .contains("OTP EMAIL_OTP")
                    .contains("ENVELOPE_CREATED")
                    .contains("ENVELOPE_SENT")
                    .contains("RECIPIENT_VIEWED")
                    .contains("RECIPIENT_SIGNED")
                    .contains("(2 recipients)")
                    .contains("2026-08-21 10:03:00 UTC")
                    .doesNotContain("Audit trail (continued)");
            // stamped text values land on the first page
            String first = textOfPage(doc, 1);
            assertThat(first).contains("Option B").contains("alice@example.com").contains("42")
                    .doesNotContain("never rendered").doesNotContain("cc text").doesNotContain("orphan");
            assertThat(first).contains("Signed by Alice Signer <alice@example.com>");
        }
    }

    @Test
    void buildStampedDocument_parallelMode_andNoEvents() throws IOException {
        SignatureRecipient r = recipient("Solo", "solo@example.com", SignatureRecipientStatus.SIGNED);
        SignatureEnvelope env = envelope("Parallel one", false);
        env.setOriginalSha256(null);
        byte[] out = service.buildStampedDocument(originalPdf, env, List.of(r), List.of(), List.of());
        try (PDDocument doc = Loader.loadPDF(out)) {
            String cert = textOfPage(doc, doc.getNumberOfPages());
            assertThat(cert).contains("parallel").contains("Original SHA-256: -").contains("Audit trail");
        }
    }

    @Test
    void buildStampedDocument_manyEvents_spillsToContinuationPage() throws IOException {
        SignatureRecipient r = recipient("A", "a@example.com", SignatureRecipientStatus.SIGNED);
        SignatureEnvelope env = envelope("Long trail", false);
        List<SignatureEvent> events = new ArrayList<>();
        OffsetDateTime t = OffsetDateTime.now();
        for (int i = 0; i < 200; i++) {
            events.add(event(SignatureEventType.RECIPIENT_REMINDED, "a@example.com", t.plusSeconds(i), "reminder " + i));
        }
        byte[] out = service.buildStampedDocument(originalPdf, env, List.of(r), List.of(), events);
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(originalPages + 2);
            String secondCertPage = textOfPage(doc, originalPages + 2);
            assertThat(secondCertPage).contains("Audit trail (continued)");
            String last = textOfPage(doc, doc.getNumberOfPages());
            assertThat(last).contains("reminder 199");
        }
    }

    @Test
    void buildStampedDocument_typedSignatureWithoutImage_isSkippedNotRendered() throws IOException {
        // SignatureField.isFilled() requires valueImage for image types, so a typed-only SIGNATURE is skipped.
        SignatureRecipient r = recipient("Typed", "typed@example.com", SignatureRecipientStatus.SIGNED);
        SignatureField typed = field(r.getId(), SignatureFieldType.SIGNATURE, 0, 0.2, 0.2, 0.3, 0.06, "Typed Name", null);
        assertThat(typed.isFilled()).isFalse();
        byte[] out = service.buildStampedDocument(originalPdf, envelope("Typed", false), List.of(r), List.of(typed), List.of());
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertThat(textOfPage(doc, 1)).doesNotContain("Signed by Typed");
        }
    }

    @Test
    void buildStampedDocument_captionNearEdges_doesNotThrow() {
        SignatureRecipient r = recipient("Edge Case With A Rather Long Name To Stretch The Caption",
                "edge-case-very-long-address@example-domain-with-a-long-name.com", SignatureRecipientStatus.SIGNED);
        SignatureEnvelope env = envelope("Edges", false);
        List<SignatureField> fields = List.of(
                // bottom edge: caption flips above the field
                field(r.getId(), SignatureFieldType.SIGNATURE, 0, 0.1, 0.0, 0.2, 0.05, null, tinyPngB64),
                // top edge: caption clamped below page top
                field(r.getId(), SignatureFieldType.SIGNATURE, 0, 0.1, 0.98, 0.2, 0.02, null, tinyPngB64),
                // right edge: caption shifted left so it stays on the page
                field(r.getId(), SignatureFieldType.SIGNATURE, 0, 0.95, 0.5, 0.05, 0.05, null, tinyPngB64),
                // whole-page box
                field(r.getId(), SignatureFieldType.SIGNATURE, 0, 0.0, 0.0, 1.0, 1.0, null, tinyPngB64),
                // degenerate (zero-size) text box
                field(r.getId(), SignatureFieldType.TEXT, 0, 0.5, 0.5, 0.0, 0.0, "x", null));
        assertThatCode(() -> service.buildStampedDocument(originalPdf, env, List.of(r), fields, List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void buildStampedDocument_garbageInput_throwsIllegalState() {
        assertThatThrownBy(() -> service.buildStampedDocument("nope".getBytes(StandardCharsets.UTF_8),
                envelope("x", false), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void buildStampedDocument_badImagePayload_throws() {
        SignatureRecipient r = recipient("A", "a@example.com", SignatureRecipientStatus.SIGNED);
        SignatureField bad = field(r.getId(), SignatureFieldType.SIGNATURE, 0, 0.1, 0.1, 0.2, 0.05, null,
                Base64.getEncoder().encodeToString("not-a-png".getBytes(StandardCharsets.UTF_8)));
        // PDFBox rejects an unknown image format with IllegalArgumentException (not IOException), so it is
        // not wrapped into the IllegalStateException used for I/O failures — either way the stamping fails loudly.
        assertThatThrownBy(() -> service.buildStampedDocument(originalPdf, envelope("x", false), List.of(r), List.of(bad), List.of()))
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static String textOfPage(PDDocument doc, int oneBasedPage) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(oneBasedPage);
        stripper.setEndPage(oneBasedPage);
        return stripper.getText(doc);
    }

    private static SignatureEnvelope envelope(String title, boolean sequential) {
        return SignatureEnvelope.builder()
                .id(UUID.randomUUID())
                .title(title)
                .initiatorEmail("initiator@example.com")
                .sequential(sequential)
                .originalSha256("0123456789abcdef")
                .build();
    }

    private static SignatureRecipient recipient(String name, String email, SignatureRecipientStatus status) {
        return SignatureRecipient.builder()
                .id(UUID.randomUUID())
                .recipientName(name)
                .recipientEmail(email)
                .role(SignatureRecipientRole.SIGNER)
                .status(status)
                .signedAt(status == SignatureRecipientStatus.SIGNED ? OffsetDateTime.now() : null)
                .build();
    }

    private static SignatureField field(UUID recipientId, SignatureFieldType type, int page,
                                        double x, double y, double w, double h, String value, String image) {
        return SignatureField.builder()
                .id(UUID.randomUUID())
                .recipientId(recipientId)
                .type(type)
                .page(page).x(x).y(y).w(w).h(h)
                .value(value)
                .valueImage(image)
                .build();
    }

    private static SignatureEvent event(SignatureEventType type, String actor, OffsetDateTime at, String details) {
        return SignatureEvent.builder()
                .id(UUID.randomUUID())
                .eventType(type)
                .actor(actor)
                .createdAt(at)
                .details(details)
                .build();
    }
}
