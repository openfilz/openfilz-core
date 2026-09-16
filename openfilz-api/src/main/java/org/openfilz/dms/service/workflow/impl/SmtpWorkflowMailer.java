package org.openfilz.dms.service.workflow.impl;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.WorkflowProperties;
import org.openfilz.dms.entity.WorkflowInstance;
import org.openfilz.dms.entity.WorkflowTask;
import org.openfilz.dms.service.workflow.WorkflowMailer;
import org.openfilz.dms.utils.EmailLayout;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Locale;

/**
 * JavaMail implementation of {@link WorkflowMailer}. Fire-and-forget on the bounded-elastic
 * scheduler; failures are logged and never reach the engine. HTML assembled in Java from the
 * localised bundle ({@link WorkflowMailTexts}), inside the shared branded shell {@link EmailLayout}.
 */
@Slf4j
public class SmtpWorkflowMailer implements WorkflowMailer {

    private final JavaMailSender sender;
    private final WorkflowProperties props;

    public SmtpWorkflowMailer(JavaMailSender sender, WorkflowProperties props) {
        this.sender = sender;
        this.props = props;
    }

    @Override
    public void sendTaskAssigned(WorkflowInstance i, WorkflowTask task, String toEmail, String link, String previousComment) {
        Locale loc = WorkflowMailTexts.localeOf(i.getLocale());
        String subject = t(loc, "task.subject", i.getDocumentName(), task.getStateLabel());
        String body = layout(loc, t(loc, "task.title"),
                EmailLayout.lead(t(loc, "task.body", esc(i.getDocumentName()), esc(i.getDefinitionName()), esc(task.getStateLabel()), esc(i.getStartedBy())))
                        + quote(previousComment)
                        + (task.getDueAt() == null ? "" : EmailLayout.callout("&#9200; " + t(loc, "task.due", task.getDueAt().toLocalDate()), false))
                        + EmailLayout.button(link, t(loc, "task.button"))
                        + EmailLayout.linkFallback(t(loc, "linkFallback"), link));
        send(toEmail, subject, body);
    }

    @Override
    public void sendTaskOverdue(WorkflowInstance i, WorkflowTask task, String toEmail, String link) {
        Locale loc = WorkflowMailTexts.localeOf(i.getLocale());
        String subject = t(loc, "overdue.subject", i.getDocumentName(), task.getStateLabel());
        String body = layout(loc, t(loc, "overdue.title"),
                EmailLayout.callout(t(loc, "overdue.body", esc(i.getDocumentName()), esc(task.getStateLabel()),
                        task.getDueAt() == null ? "-" : task.getDueAt().toLocalDate()), true)
                        + EmailLayout.button(link, t(loc, "task.button")));
        send(toEmail, subject, body);
    }

    @Override
    public void sendCompleted(WorkflowInstance i, String toEmail, String link) {
        Locale loc = WorkflowMailTexts.localeOf(i.getLocale());
        String subject = t(loc, "completed.subject", i.getDocumentName(), i.getCurrentStateLabel());
        String body = layout(loc, t(loc, "completed.title"),
                EmailLayout.lead(t(loc, "completed.body", esc(i.getDocumentName()), esc(i.getDefinitionName()), esc(i.getCurrentStateLabel())))
                        + EmailLayout.button(link, t(loc, "completed.button")));
        send(toEmail, subject, body);
    }

    @Override
    public void sendCancelled(WorkflowInstance i, String toEmail, String actorEmail, String comment, String link) {
        Locale loc = WorkflowMailTexts.localeOf(i.getLocale());
        String subject = t(loc, "cancelled.subject", i.getDocumentName());
        String body = layout(loc, t(loc, "cancelled.title"),
                EmailLayout.lead(t(loc, "cancelled.body", esc(i.getDocumentName()), esc(i.getDefinitionName()), esc(actorEmail)))
                        + quote(comment)
                        + EmailLayout.button(link, t(loc, "completed.button")));
        send(toEmail, subject, body);
    }

    @Override
    public void sendStateReached(WorkflowInstance i, String stateLabel, String toEmail, String link) {
        Locale loc = WorkflowMailTexts.localeOf(i.getLocale());
        String subject = t(loc, "reached.subject", i.getDocumentName(), stateLabel);
        String body = layout(loc, t(loc, "reached.title"),
                EmailLayout.lead(t(loc, "reached.body", esc(i.getDocumentName()), esc(i.getDefinitionName()), esc(stateLabel)))
                        + EmailLayout.button(link, t(loc, "completed.button")));
        send(toEmail, subject, body);
    }

    // ─────────────────────────────────────────────────────────────────────

    private void send(String to, String subject, String html) {
        Mono.fromRunnable(() -> {
            try {
                MimeMessage message = sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
                helper.setFrom(props.getMail().getFrom(), props.getMail().getFromName());
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(html, true);
                sender.send(message);
                log.info("[workflows] mail '{}' sent to {}", subject, to);
            } catch (Exception e) {
                log.error("[workflows] failed to send mail '{}' to {}: {}", subject, to, e.toString());
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private String t(Locale loc, String key, Object... args) {
        return WorkflowMailTexts.text(loc, key, args);
    }

    private static String esc(String s) {
        return EmailLayout.esc(s);
    }

    private static String quote(String text) {
        if (text == null || text.isBlank()) return "";
        return EmailLayout.quote(esc(text).replace("\n", "<br>"));
    }

    private String layout(Locale loc, String title, String content) {
        WorkflowProperties.Mail mail = props.getMail();
        return EmailLayout.page(loc.getLanguage(), WorkflowMailTexts.isRtl(loc), title, content,
                t(loc, "footer", esc(mail.getProductName())), mail.getProductName(), mail.getLogoUrl());
    }
}
