package org.openfilz.dms.e2e.signature;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.signature.ApplySignatureRequest;
import org.openfilz.dms.dto.signature.CreateSignatureEnvelopeRequest;
import org.openfilz.dms.dto.signature.PublicSignatureView;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureEventDTO;
import org.openfilz.dms.dto.signature.SignatureFieldInput;
import org.openfilz.dms.dto.signature.SignatureFieldPlacement;
import org.openfilz.dms.dto.signature.SignatureFieldValue;
import org.openfilz.dms.dto.signature.SignatureRecipientDTO;
import org.openfilz.dms.dto.signature.SignatureRecipientInput;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.openfilz.dms.enums.SignatureEventType;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.enums.SignatureRecipientRole;
import org.openfilz.dms.enums.SignatureRecipientStatus;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/** End-to-end envelope lifecycle through the REST API: create → view → sign → complete → download. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class SignatureEnvelopeIT extends AbstractSignatureIT {

    private String contributor;

    SignatureEnvelopeIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @BeforeEach
    void setUp() {
        contributor = getAccessToken("admin-user");
        mails().clear();
    }

    @Test
    void parallel_envelope_with_all_field_types_completes_and_produces_a_sealed_pdf() throws Exception {
        UUID docId = uploadPdf(contributor);
        List<SignatureFieldInput> aliceFields = List.of(
                signatureField(0, 0.1, 0.1),
                field(SignatureFieldType.INITIALS, 0.6, 0.1, true, "Initials", null),
                field(SignatureFieldType.DATE_SIGNED, 0.1, 0.2, true, "Date", null),
                field(SignatureFieldType.TEXT, 0.1, 0.3, true, "Company", null),
                field(SignatureFieldType.NUMBER, 0.1, 0.4, true, "Amount", null),
                field(SignatureFieldType.EMAIL, 0.1, 0.5, false, "Email", null),
                field(SignatureFieldType.PHONE, 0.1, 0.6, false, "Phone", null),
                field(SignatureFieldType.CHECKBOX, 0.1, 0.7, true, "I agree", null),
                field(SignatureFieldType.RADIO, 0.1, 0.8, true, "Option", Map.of("choices", List.of("A", "B"), "group", "g")),
                field(SignatureFieldType.SELECT, 0.5, 0.8, true, "Plan", Map.of("choices", List.of("A", "B", "C"))),
                field(SignatureFieldType.IMAGE, 0.5, 0.3, false, "Logo", null),
                field(SignatureFieldType.STAMP, 0.5, 0.5, false, "Stamp", null));
        CreateSignatureEnvelopeRequest req = request(docId, "Contract 42", List.of(
                signer("Alice", "alice@example.com", aliceFields),
                signer("Bob", "bob@example.com", List.of(signatureField(0, 0.1, 0.9))),
                new SignatureRecipientInput(null, "Carol", "carol@example.com", 0, SignatureRecipientRole.CC,
                        null, null, null, List.of(), null)));

        SignatureEnvelopeDTO env = createEnvelope(contributor, req);
        assertThat(env.status()).isEqualTo(SignatureEnvelopeStatus.SENT);
        assertThat(env.initiatorEmail()).isEqualTo(CONTRIBUTOR_EMAIL);
        assertThat(env.recipients()).hasSize(3);
        assertThat(env.recipients().get(0).fields()).hasSize(12);
        // Only signers get an invitation; CC waits for the completed document.
        assertThat(mails().ofKind("request")).extracting(CapturingSignatureMailer.Sent::to)
                .containsExactlyInAnyOrder("alice@example.com", "bob@example.com");

        // Listing for the initiator.
        List<SignatureEnvelopeDTO> sent = getWebTestClient().get().uri(SIG)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk()
                .expectBodyList(SignatureEnvelopeDTO.class).returnResult().getResponseBody();
        assertThat(sent).extracting(SignatureEnvelopeDTO::id).contains(env.id());
        // The list is assembled from batched queries, so it must carry each envelope's OWN
        // recipients and their own fields — not another envelope's, and not none at all.
        SignatureEnvelopeDTO listed = sent.stream().filter(e -> e.id().equals(env.id())).findFirst().orElseThrow();
        assertThat(listed.recipients()).extracting(SignatureRecipientDTO::email)
                .containsExactlyElementsOf(env.recipients().stream().map(SignatureRecipientDTO::email).toList());
        assertThat(listed.recipients().get(0).fields()).hasSize(env.recipients().get(0).fields().size());

        // A second envelope forces the batch path (one query for both, not two per envelope) and
        // would expose any cross-envelope mix-up in the grouping.
        // dave, not carol — carol is already a CC on the first envelope, which would make the
        // cross-envelope check below pass or fail for the wrong reason.
        SignatureEnvelopeDTO other = createEnvelope(contributor, request(uploadPdf(contributor), "Second envelope",
                List.of(signer("Dave", "dave@example.com", List.of(signatureField(0, 0.2, 0.2))))));
        List<SignatureEnvelopeDTO> both = getWebTestClient().get().uri(SIG)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk()
                .expectBodyList(SignatureEnvelopeDTO.class).returnResult().getResponseBody();
        SignatureEnvelopeDTO listedOther = both.stream().filter(e -> e.id().equals(other.id())).findFirst().orElseThrow();
        assertThat(listedOther.recipients()).extracting(SignatureRecipientDTO::email)
                .containsExactly("dave@example.com");
        assertThat(listedOther.recipients().getFirst().fields()).hasSize(1);
        assertThat(both.stream().filter(e -> e.id().equals(env.id())).findFirst().orElseThrow().recipients())
                .as("recipients of the first envelope, listed alongside the second")
                .extracting(SignatureRecipientDTO::email)
                .containsExactly("alice@example.com", "bob@example.com", "carol@example.com");

        // Alice opens the link: public view carries her fields (not Bob's) and no OTP.
        String aliceToken = mails().tokenFor(env.id(), "alice@example.com");
        PublicSignatureView aliceView = markViewed(aliceToken);
        assertThat(aliceView.recipientStatus()).isEqualTo(SignatureRecipientStatus.VIEWED);
        assertThat(aliceView.fields()).hasSize(12);
        assertThat(aliceView.otherFields()).isEmpty();
        assertThat(aliceView.otpRequired()).isFalse();
        assertThat(aliceView.myTurn()).isTrue();
        assertThat(aliceView.documentName()).isEqualTo("pdf-example.pdf");

        // The source PDF streams with the token only.
        byte[] source = getWebTestClient().get().uri(u -> u.path(PUB + "/document").queryParam("token", aliceToken).build())
                .exchange().expectStatus().isOk().expectHeader().contentType(MediaType.APPLICATION_PDF)
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(new String(source, 0, 4)).isEqualTo("%PDF");

        // Missing required field → 422.
        signRaw(aliceToken, new ApplySignatureRequest(null, null,
                List.of(new SignatureFieldValue(aliceView.fields().get(0).id(), null, TINY_PNG))))
                .expectStatus().isEqualTo(422);

        // Alice signs everything; DATE_SIGNED is auto-filled server-side.
        PublicSignatureView afterAlice = signAllFields(aliceToken);
        assertThat(afterAlice.recipientStatus()).isEqualTo(SignatureRecipientStatus.SIGNED);
        assertThat(afterAlice.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.SENT);
        assertThat(afterAlice.fields()).allMatch(f -> f.value() != null || f.valueImage() != null);
        assertThat(afterAlice.fields().stream().filter(f -> f.type() == SignatureFieldType.DATE_SIGNED).findFirst().orElseThrow().value())
                .matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(afterAlice.signatureImage()).isEqualTo(TINY_PNG);

        // Signing twice → 409.
        signRaw(aliceToken, new ApplySignatureRequest(null, null, List.of())).expectStatus().isEqualTo(409);

        // Bob sees Alice's filled fields as read-only context, signs with the legacy typed-name shape.
        String bobToken = mails().tokenFor(env.id(), "bob@example.com");
        PublicSignatureView bobView = view(bobToken);
        assertThat(bobView.otherFields()).isNotEmpty();
        PublicSignatureView afterBob = sign(bobToken, new ApplySignatureRequest(null, "Bob Builder", null));
        assertThat(afterBob.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);
        assertThat(afterBob.signatureTyped()).isEqualTo("Bob Builder");

        // Envelope completed, signed doc available, seal recorded.
        SignatureEnvelopeDTO done = getEnvelope(contributor, env.id());
        assertThat(done.status()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);
        assertThat(done.signedDocId()).isNotNull();
        assertThat(done.sealProvider()).isEqualTo("self-signed-dev");
        assertThat(done.completedAt()).isNotNull();

        byte[] sealed = downloadSigned(contributor, env.id());
        try (PDDocument pdf = Loader.loadPDF(sealed)) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(Loader.loadPDF(source).getNumberOfPages());
            assertThat(pdf.getSignatureDictionaries()).hasSize(1);
            assertThat(pdf.getSignatureDictionaries().getFirst().getName()).isEqualTo("OpenFilz e-Sign Seal");
        }

        // The signed copy is a regular document, readable through the documents API and locked read-only.
        getWebTestClient().get().uri("/api/v1/documents/" + done.signedDocId() + "/download")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk();

        // Everyone gets the sealed PDF (initiator + both signers + CC), deduplicated by address.
        assertThat(mails().ofKind("completed")).extracting(CapturingSignatureMailer.Sent::to)
                .containsExactlyInAnyOrder(CONTRIBUTOR_EMAIL, "alice@example.com", "bob@example.com", "carol@example.com");
        assertThat(mails().ofKind("completed").getFirst().pdf()).isEqualTo(sealed);

        // Audit trail exposes the whole story.
        List<SignatureEventDTO> events = getWebTestClient().get().uri(SIG + "/" + env.id() + "/events")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk()
                .expectBodyList(SignatureEventDTO.class).returnResult().getResponseBody();
        assertThat(events).extracting(SignatureEventDTO::type).containsSubsequence(
                SignatureEventType.ENVELOPE_CREATED, SignatureEventType.ENVELOPE_SENT, SignatureEventType.RECIPIENT_VIEWED,
                SignatureEventType.RECIPIENT_SIGNED, SignatureEventType.RECIPIENT_SIGNED, SignatureEventType.ENVELOPE_COMPLETED);
        assertThat(events.stream().filter(e -> e.type() == SignatureEventType.RECIPIENT_SIGNED).map(SignatureEventDTO::actor))
                .containsExactlyInAnyOrder("alice@example.com", "bob@example.com");

        // Cancelling a completed envelope is rejected.
        getWebTestClient().post().uri(SIG + "/" + env.id() + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void sequential_envelope_invites_signers_one_order_at_a_time() {
        UUID docId = uploadPdf(contributor);
        CreateSignatureEnvelopeRequest req = new CreateSignatureEnvelopeRequest(docId, "Sequential", null, List.of(
                signer("First", "first@example.com", 0, List.of(signatureField(0, 0.1, 0.1))),
                signer("Second", "second@example.com", 1, List.of(signatureField(0, 0.1, 0.3))),
                signer("Third", "third@example.com", 2, List.of(signatureField(0, 0.1, 0.5)))),
                10, true, null, "fr", true, null);
        SignatureEnvelopeDTO env = createEnvelope(contributor, req);
        assertThat(env.sequential()).isTrue();
        assertThat(env.currentOrder()).isZero();
        assertThat(mails().ofKind("request")).extracting(CapturingSignatureMailer.Sent::to).containsExactly("first@example.com");

        // Second has no link yet; when they eventually get one it is not their turn.
        String first = mails().tokenFor(env.id(), "first@example.com");
        signAllFields(first);

        assertThat(mails().ofKind("request")).extracting(CapturingSignatureMailer.Sent::to)
                .containsExactly("first@example.com", "second@example.com");
        SignatureEnvelopeDTO afterFirst = getEnvelope(contributor, env.id());
        assertThat(afterFirst.currentOrder()).isEqualTo(1);
        assertThat(afterFirst.status()).isEqualTo(SignatureEnvelopeStatus.SENT);

        String second = mails().tokenFor(env.id(), "second@example.com");
        assertThat(view(second).myTurn()).isTrue();
        signAllFields(second);
        String third = mails().tokenFor(env.id(), "third@example.com");
        signAllFields(third);

        assertThat(getEnvelope(contributor, env.id()).status()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);
    }

    @Test
    void draft_then_send_then_resend_rotates_tokens() {
        UUID docId = uploadPdf(contributor);
        CreateSignatureEnvelopeRequest req = new CreateSignatureEnvelopeRequest(docId, "Draft", null,
                List.of(signer("Dana", "dana@example.com", List.of(signatureField(0, 0.2, 0.2)))),
                null, null, null, null, false, null);
        SignatureEnvelopeDTO draft = createEnvelope(contributor, req);
        assertThat(draft.status()).isEqualTo(SignatureEnvelopeStatus.DRAFT);
        assertThat(draft.sentAt()).isNull();
        assertThat(mails().ofKind("request")).isEmpty();

        SignatureEnvelopeDTO sent = getWebTestClient().post().uri(SIG + "/" + draft.id() + "/send")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk()
                .expectBody(SignatureEnvelopeDTO.class).returnResult().getResponseBody();
        assertThat(sent.status()).isEqualTo(SignatureEnvelopeStatus.SENT);
        assertThat(sent.sentAt()).isNotNull();
        String firstToken = mails().tokenFor(draft.id(), "dana@example.com");
        assertThat(view(firstToken).envelopeTitle()).isEqualTo("Draft");

        // Sending twice is a conflict.
        getWebTestClient().post().uri(SIG + "/" + draft.id() + "/send")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isEqualTo(409);

        // Resend: old token revoked, new one works, reminder counted.
        UUID recipientId = sent.recipients().getFirst().id();
        SignatureEnvelopeDTO afterResend = getWebTestClient().post()
                .uri(SIG + "/" + draft.id() + "/recipients/" + recipientId + "/resend")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk()
                .expectBody(SignatureEnvelopeDTO.class).returnResult().getResponseBody();
        assertThat(afterResend.recipients().getFirst().reminderCount()).isEqualTo(1);
        assertThat(mails().ofKind("reminder")).hasSize(1);
        String secondToken = mails().tokenFor(draft.id(), "dana@example.com");
        assertThat(secondToken).isNotEqualTo(firstToken);
        getWebTestClient().get().uri(u -> u.path(PUB).queryParam("token", firstToken).build())
                .exchange().expectStatus().isNotFound();
        assertThat(view(secondToken).recipientEmail()).isEqualTo("dana@example.com");

        // Cancel voids the envelope; the signer is told it is gone.
        SignatureEnvelopeDTO cancelled = getWebTestClient().post().uri(SIG + "/" + draft.id() + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk()
                .expectBody(SignatureEnvelopeDTO.class).returnResult().getResponseBody();
        assertThat(cancelled.status()).isEqualTo(SignatureEnvelopeStatus.CANCELLED);
        signRaw(secondToken, new ApplySignatureRequest(null, "Dana", null)).expectStatus().isEqualTo(409);
        // and the signed-document download says there is nothing.
        getWebTestClient().get().uri(SIG + "/" + draft.id() + "/signed-document")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void decline_voids_the_envelope_and_notifies_the_initiator() {
        UUID docId = uploadPdf(contributor);
        SignatureEnvelopeDTO env = createEnvelope(contributor, request(docId, "Decline me", List.of(
                signer("Eve", "eve@example.com", List.of(signatureField(0, 0.1, 0.1))),
                signer("Frank", "frank@example.com", List.of(signatureField(0, 0.1, 0.5))))));
        String eve = mails().tokenFor(env.id(), "eve@example.com");
        PublicSignatureView declined = getWebTestClient().post()
                .uri(u -> u.path(PUB + "/decline").queryParam("token", eve).build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reason", "Wrong amount"))
                .exchange().expectStatus().isOk()
                .expectBody(PublicSignatureView.class).returnResult().getResponseBody();
        assertThat(declined.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.DECLINED);
        assertThat(declined.recipientStatus()).isEqualTo(SignatureRecipientStatus.DECLINED);
        assertThat(mails().ofKind("declined")).hasSize(1);

        SignatureEnvelopeDTO dto = getEnvelope(contributor, env.id());
        assertThat(dto.recipients().getFirst().declineReason()).isEqualTo("Wrong amount");
        // Frank can no longer sign.
        String frank = mails().tokenFor(env.id(), "frank@example.com");
        signRaw(frank, new ApplySignatureRequest(null, "Frank", null)).expectStatus().isEqualTo(409);
    }

    @Test
    void legacy_single_placement_shape_is_still_accepted() {
        UUID docId = uploadPdf(contributor);
        SignatureRecipientInput legacy = new SignatureRecipientInput(null, "Old Client", "old@example.com", null, null, null,
                null, null, null, new SignatureFieldPlacement(0, 0.1, 0.1, 0.3, 0.08));
        SignatureEnvelopeDTO env = createEnvelope(contributor, request(docId, "Legacy", List.of(legacy)));
        assertThat(env.recipients().getFirst().fields()).hasSize(1);
        assertThat(env.recipients().getFirst().fields().getFirst().type()).isEqualTo(SignatureFieldType.SIGNATURE);
        String token = mails().tokenFor(env.id(), "old@example.com");
        PublicSignatureView v = view(token);
        assertThat(v.fieldPage()).isZero();
        assertThat(v.fieldW()).isEqualTo(0.3);
        // Both legacy inputs at once → 400.
        signRaw(token, new ApplySignatureRequest(TINY_PNG, "Both", null)).expectStatus().isBadRequest();
        PublicSignatureView signed = sign(token, new ApplySignatureRequest(TINY_PNG, null, null));
        assertThat(signed.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);
        assertThat(signed.signatureImage()).isEqualTo(TINY_PNG);
    }

    @Test
    void validation_rejects_bad_envelopes() {
        UUID docId = uploadPdf(contributor);
        // Signer without a signature field
        createEnvelopeRaw(contributor, request(docId, "x", List.of(
                signer("A", "a@example.com", List.of(field(SignatureFieldType.TEXT, 0.1, 0.1, true, "t", null))))))
                .expectStatus().isEqualTo(422);
        // Page out of range
        createEnvelopeRaw(contributor, request(docId, "x", List.of(
                signer("A", "a@example.com", List.of(signatureField(99, 0.1, 0.1))))))
                .expectStatus().isEqualTo(422);
        // Placement outside the page
        createEnvelopeRaw(contributor, request(docId, "x", List.of(
                signer("A", "a@example.com", List.of(new SignatureFieldInput(SignatureFieldType.SIGNATURE, 0, 0.9, 0.9, 0.3, 0.3, true, null, null))))))
                .expectStatus().isEqualTo(422);
        // CC with fields
        createEnvelopeRaw(contributor, request(docId, "x", List.of(
                signer("A", "a@example.com", List.of(signatureField(0, 0.1, 0.1))),
                new SignatureRecipientInput(null, "C", "c@example.com", 0, SignatureRecipientRole.CC, null, null, null,
                        List.of(signatureField(0, 0.1, 0.5)), null))))
                .expectStatus().isEqualTo(422);
        // Duplicate recipient
        createEnvelopeRaw(contributor, request(docId, "x", List.of(
                signer("A", "a@example.com", List.of(signatureField(0, 0.1, 0.1))),
                signer("A2", "A@example.com", List.of(signatureField(0, 0.1, 0.5))))))
                .expectStatus().isEqualTo(422);
        // SELECT without choices
        createEnvelopeRaw(contributor, request(docId, "x", List.of(
                signer("A", "a@example.com", List.of(signatureField(0, 0.1, 0.1),
                        field(SignatureFieldType.SELECT, 0.1, 0.5, true, "Plan", null))))))
                .expectStatus().isEqualTo(422);
        // Bean validation: blank title
        createEnvelopeRaw(contributor, Map.of("sourceDocId", docId, "title", "", "recipients", List.of()))
                .expectStatus().isBadRequest();
        // Non-PDF document
        UUID txt = uploadText(contributor);
        createEnvelopeRaw(contributor, request(txt, "x", List.of(
                signer("A", "a@example.com", List.of(signatureField(0, 0.1, 0.1))))))
                .expectStatus().isEqualTo(422);
        // Unknown document
        createEnvelopeRaw(contributor, request(UUID.randomUUID(), "x", List.of(
                signer("A", "a@example.com", List.of(signatureField(0, 0.1, 0.1))))))
                .expectStatus().isNotFound();
        // Bad / missing token on the public side
        getWebTestClient().get().uri(u -> u.path(PUB).queryParam("token", "nope").build()).exchange().expectStatus().isNotFound();
        getWebTestClient().get().uri(u -> u.path(PUB).queryParam("token", "").build()).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void field_value_validation_per_type() {
        UUID docId = uploadPdf(contributor);
        SignatureEnvelopeDTO env = createEnvelope(contributor, request(docId, "Types", List.of(
                signer("Val", "val@example.com", List.of(
                        signatureField(0, 0.1, 0.1),
                        field(SignatureFieldType.NUMBER, 0.1, 0.3, true, "n", null),
                        field(SignatureFieldType.EMAIL, 0.1, 0.4, true, "e", null),
                        field(SignatureFieldType.CHECKBOX, 0.1, 0.5, true, "c", null),
                        field(SignatureFieldType.SELECT, 0.1, 0.6, true, "s", Map.of("choices", List.of("A", "B"))))))));
        String token = mails().tokenFor(env.id(), "val@example.com");
        PublicSignatureView v = view(token);
        UUID sig = v.fields().get(0).id(), num = v.fields().get(1).id(), mail = v.fields().get(2).id(),
                chk = v.fields().get(3).id(), sel = v.fields().get(4).id();

        signRaw(token, values(sig, TINY_PNG, num, "abc", mail, "x@y.z", chk, "true", sel, "A")).expectStatus().isEqualTo(422);
        signRaw(token, values(sig, TINY_PNG, num, "1", mail, "not-an-email", chk, "true", sel, "A")).expectStatus().isEqualTo(422);
        signRaw(token, values(sig, TINY_PNG, num, "1", mail, "x@y.z", chk, "false", sel, "A")).expectStatus().isEqualTo(422);
        signRaw(token, values(sig, TINY_PNG, num, "1", mail, "x@y.z", chk, "true", sel, "Z")).expectStatus().isEqualTo(422);
        // unknown field id
        signRaw(token, new ApplySignatureRequest(null, null, List.of(new SignatureFieldValue(UUID.randomUUID(), "x", null))))
                .expectStatus().isEqualTo(422);
        // all good
        sign(token, values(sig, TINY_PNG, num, "12.5", mail, "x@y.z", chk, "true", sel, "B"));
        assertThat(getEnvelope(contributor, env.id()).status()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);
    }

    private static ApplySignatureRequest values(UUID sig, String png, UUID num, String n, UUID mail, String m,
                                                UUID chk, String c, UUID sel, String s) {
        return new ApplySignatureRequest(null, null, List.of(
                new SignatureFieldValue(sig, null, png),
                new SignatureFieldValue(num, n, null),
                new SignatureFieldValue(mail, m, null),
                new SignatureFieldValue(chk, c, null),
                new SignatureFieldValue(sel, s, null)));
    }

    @Test
    void to_sign_list_matches_recipients_by_email() {
        UUID docId = uploadPdf(contributor);
        // contributor-user is a recipient of an envelope sent by admin-user
        SignatureEnvelopeDTO env = createEnvelope(contributor, request(docId, "For admin", List.of(
                signer("Contributor", "contributor-user@test.com", List.of(signatureField(0, 0.1, 0.1))))));
        String other = getAccessToken("contributor-user");
        List<SignatureEnvelopeDTO> toSign = getWebTestClient().get().uri(SIG + "/to-sign")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                .exchange().expectStatus().isOk()
                .expectBodyList(SignatureEnvelopeDTO.class).returnResult().getResponseBody();
        assertThat(toSign).extracting(SignatureEnvelopeDTO::id).contains(env.id());
        SignatureEnvelopeDTO listedToSign = toSign.stream().filter(e -> e.id().equals(env.id())).findFirst().orElseThrow();
        assertThat(listedToSign.recipients()).extracting(SignatureRecipientDTO::email)
                .containsExactly("contributor-user@test.com");
        assertThat(listedToSign.recipients().getFirst().fields()).hasSize(1);
        // but the recipient cannot manage the initiator's envelope
        getWebTestClient().get().uri(SIG + "/" + env.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                .exchange().expectStatus().isForbidden();
        // status filter
        List<SignatureEnvelopeDTO> completed = getWebTestClient().get().uri(SIG + "?status=COMPLETED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk()
                .expectBodyList(SignatureEnvelopeDTO.class).returnResult().getResponseBody();
        assertThat(completed).extracting(SignatureEnvelopeDTO::id).doesNotContain(env.id());
    }

    @Test
    void cloud_subscription_is_404_when_the_cloud_provider_is_not_configured() {
        // This context runs the default self-signed-dev seal provider.
        getWebTestClient().get().uri(SIG + "/cloud-subscription")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isNotFound();

        getWebTestClient().get().uri(org.openfilz.dms.config.RestApiVersion.API_PREFIX + "/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.signatureCloudActive").isEqualTo(false);
    }
}
