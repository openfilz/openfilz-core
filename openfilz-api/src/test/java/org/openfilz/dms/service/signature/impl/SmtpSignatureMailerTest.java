package org.openfilz.dms.service.signature.impl;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link SmtpSignatureMailer} against an embedded GreenMail SMTP server. */
class SmtpSignatureMailerTest {

    @RegisterExtension
    final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    private SmtpSignatureMailer mailer;
    private SignatureProperties props;

    @BeforeEach
    void setUp() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(ServerSetupTest.SMTP.getBindAddress());
        sender.setPort(ServerSetupTest.SMTP.getPort());
        props = new SignatureProperties();
        props.getMail().setFrom("esign@test.local");
        props.getMail().setFromName("Test e-Sign");
        props.getMail().setProductName("TestProduct");
        props.getMail().setLogoUrl("https://cdn.test.local/logo.png");
        mailer = new SmtpSignatureMailer(sender, props);
    }

    private static SignatureEnvelope envelope(String locale) {
        return SignatureEnvelope.builder()
                .id(UUID.randomUUID())
                .title("Contrat <B&B>")
                .message("Line one\nLine two")
                .initiatorEmail("initiator@test.local")
                .locale(locale)
                .expiresAt(OffsetDateTime.of(2030, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC))
                .build();
    }

    private static SignatureRecipient recipient(String email, String locale) {
        return SignatureRecipient.builder()
                .id(UUID.randomUUID())
                .recipientEmail(email)
                .recipientName("Recipient")
                .locale(locale)
                .build();
    }

    private MimeMessage awaitOne() {
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        return received[0];
    }

    /** Decoded text/html content, whether the message is single-part or multipart. */
    private static String body(MimeMessage m) throws Exception {
        return html(m);
    }

    private static String html(jakarta.mail.Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String h = html(mp.getBodyPart(i));
                if (h != null) return h;
            }
            return null;
        }
        return part.isMimeType("text/html") ? content.toString() : null;
    }

    @Test
    void sendRequest_english_containsLocalizedSubjectBodyAndLink() throws Exception {
        SignatureEnvelope env = envelope("en");
        mailer.sendRequest(env, recipient("alice@test.local", null), "lease.pdf", "https://app.test.local/sign?token=abc");

        MimeMessage m = awaitOne();
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("alice@test.local");
        assertThat(MimeUtility.decodeText(m.getFrom()[0].toString())).contains("Test e-Sign").contains("esign@test.local");
        assertThat(m.getSubject()).isEqualTo("initiator@test.local asks you to sign \"Contrat <B&B>\"");
        String html = body(m);
        assertThat(html)
                .contains("Document to sign")
                .contains("invites you to sign <b>Contrat &lt;B&amp;B&gt;</b> (lease.pdf)")
                .contains("Review and sign")
                .contains("https://app.test.local/sign?token=abc")
                .contains("expires on 2030-01-02")
                .contains("Line one<br>Line two")
                .contains("https://cdn.test.local/logo.png")
                .contains("Sent by TestProduct e-Sign")
                .contains("dir=\"ltr\"");
    }

    @Test
    void sendRequest_french_recipientLocaleWinsOverEnvelope_andApostrophesRender() throws Exception {
        SignatureEnvelope env = envelope("en");
        mailer.sendRequest(env, recipient("bob@test.local", "fr-FR"), "bail.pdf", "https://x/sign?token=1");

        MimeMessage m = awaitOne();
        assertThat(m.getSubject()).isEqualTo("initiator@test.local vous invite à signer « Contrat <B&B> »");
        String html = body(m);
        assertThat(html)
                .contains("Document à signer")
                .contains("Consulter et signer")
                .contains("Si vous n'attendiez pas cet e-mail, vous pouvez l'ignorer");
    }

    @Test
    void sendReminder_usesReminderTexts() throws Exception {
        mailer.sendReminder(envelope(null), recipient("c@test.local", null), "doc.pdf", "https://x/sign?token=2");
        MimeMessage m = awaitOne();
        assertThat(m.getSubject()).isEqualTo("Reminder: \"Contrat <B&B>\" is waiting for your signature");
        assertThat(body(m)).contains("Still waiting for your signature").contains("A friendly reminder");
    }

    @Test
    void sendOtp_containsCodeAndValidity() throws Exception {
        mailer.sendOtp(envelope("en"), recipient("d@test.local", null), "123456", 10);
        MimeMessage m = awaitOne();
        assertThat(m.getSubject()).isEqualTo("Your signing code: 123456");
        assertThat(body(m)).contains("123456").contains("valid for 10 minutes");
    }

    @Test
    void sendCompleted_attachesSignedPdf_andUsesExplicitLocale() throws Exception {
        byte[] pdfBytes = "%PDF-1.4 fake".getBytes();
        mailer.sendCompleted(envelope("en"), "e@test.local", "E", "fr", pdfBytes, "signed.pdf");

        MimeMessage m = awaitOne();
        assertThat(m.getSubject()).isEqualTo("« Contrat <B&B> » a été signé par toutes les parties");
        Object content = m.getContent();
        assertThat(content).isInstanceOf(Multipart.class);
        Multipart mp = (Multipart) content;
        boolean attachmentFound = false;
        for (int i = 0; i < mp.getCount(); i++) {
            BodyPart part = mp.getBodyPart(i);
            if ("signed.pdf".equals(part.getFileName())) {
                attachmentFound = true;
                assertThat(part.getContentType()).contains("application/pdf");
                assertThat(part.getInputStream().readAllBytes()).isEqualTo(pdfBytes);
            }
        }
        assertThat(attachmentFound).as("signed.pdf attachment").isTrue();
        assertThat(body(m)).contains("Document finalisé");
    }

    @Test
    void sendCompleted_nullLocaleFallsBackToEnvelopeLocale() throws Exception {
        mailer.sendCompleted(envelope("fr"), "f@test.local", null, null, new byte[]{1}, "s.pdf");
        MimeMessage m = awaitOne();
        assertThat(m.getSubject()).startsWith("« Contrat");
    }

    @Test
    void sendDeclined_goesToInitiator_withReasonOrFallback() throws Exception {
        SignatureEnvelope env = envelope("en");
        SignatureRecipient decliner = recipient("g@test.local", null);
        decliner.setDeclineReason("Wrong <amount>");
        mailer.sendDeclined(env, decliner);

        MimeMessage m = awaitOne();
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("initiator@test.local");
        assertThat(m.getSubject()).isEqualTo("\"Contrat <B&B>\" was declined");
        assertThat(body(m)).contains("g@test.local declined to sign").contains("Wrong &lt;amount&gt;");

        greenMail.purgeEmailFromAllMailboxes();
        decliner.setDeclineReason("  ");
        mailer.sendDeclined(env, decliner);
        MimeMessage m2 = awaitOne();
        assertThat(body(m2)).contains("No reason was given.");
    }

    @Test
    void arabic_rendersRtlLayout() throws Exception {
        mailer.sendRequest(envelope("ar"), recipient("h@test.local", null), "doc.pdf", "https://x/sign?token=3");
        MimeMessage m = awaitOne();
        assertThat(body(m)).contains("dir=\"rtl\"");
        assertThat(m.getSubject()).contains("Contrat");
    }

    @Test
    void unsupportedLocale_fallsBackToEnglish() throws Exception {
        mailer.sendRequest(envelope("xx"), recipient("i@test.local", "zz-ZZ"), "doc.pdf", "https://x/sign?token=4");
        MimeMessage m = awaitOne();
        assertThat(m.getSubject()).contains("asks you to sign");
    }

    @Test
    void sendFailure_isSwallowed() {
        JavaMailSenderImpl dead = new JavaMailSenderImpl();
        dead.setHost("127.0.0.1");
        dead.setPort(1); // nothing listens here
        SmtpSignatureMailer failing = new SmtpSignatureMailer(dead, props);
        // fire-and-forget: no exception must surface to the caller
        failing.sendRequest(envelope("en"), recipient("j@test.local", null), "doc.pdf", "https://x");
        failing.sendCompleted(envelope("en"), "k@test.local", null, null, new byte[]{1}, "s.pdf");
    }
}
