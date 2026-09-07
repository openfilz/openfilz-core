package org.openfilz.dms.service.workflow.impl;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.WorkflowProperties;
import org.openfilz.dms.entity.WorkflowInstance;
import org.openfilz.dms.entity.WorkflowTask;
import org.openfilz.dms.service.workflow.WorkflowMailer;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Locale;

/**
 * JavaMail implementation of {@link WorkflowMailer}. Fire-and-forget on the bounded-elastic
 * scheduler; failures are logged and never reach the engine. HTML assembled in Java from the
 * localised bundle ({@link WorkflowMailTexts}), same layout as the e-Sign mails.
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
                p(t(loc, "task.body", esc(i.getDocumentName()), esc(i.getDefinitionName()), esc(task.getStateLabel()), esc(i.getStartedBy())))
                        + quote(previousComment)
                        + (task.getDueAt() == null ? "" : p(t(loc, "task.due", task.getDueAt().toLocalDate())))
                        + button(link, t(loc, "task.button"))
                        + small(t(loc, "linkFallback") + "<br><a href=\"" + link + "\">" + link + "</a>"));
        send(toEmail, subject, body);
    }

    @Override
    public void sendTaskOverdue(WorkflowInstance i, WorkflowTask task, String toEmail, String link) {
        Locale loc = WorkflowMailTexts.localeOf(i.getLocale());
        String subject = t(loc, "overdue.subject", i.getDocumentName(), task.getStateLabel());
        String body = layout(loc, t(loc, "overdue.title"),
                p(t(loc, "overdue.body", esc(i.getDocumentName()), esc(task.getStateLabel()),
                        task.getDueAt() == null ? "-" : task.getDueAt().toLocalDate()))
                        + button(link, t(loc, "task.button")));
        send(toEmail, subject, body);
    }

    @Override
    public void sendCompleted(WorkflowInstance i, String toEmail, String link) {
        Locale loc = WorkflowMailTexts.localeOf(i.getLocale());
        String subject = t(loc, "completed.subject", i.getDocumentName(), i.getCurrentStateLabel());
        String body = layout(loc, t(loc, "completed.title"),
                p(t(loc, "completed.body", esc(i.getDocumentName()), esc(i.getDefinitionName()), esc(i.getCurrentStateLabel())))
                        + button(link, t(loc, "completed.button")));
        send(toEmail, subject, body);
    }

    @Override
    public void sendCancelled(WorkflowInstance i, String toEmail, String actorEmail, String comment, String link) {
        Locale loc = WorkflowMailTexts.localeOf(i.getLocale());
        String subject = t(loc, "cancelled.subject", i.getDocumentName());
        String body = layout(loc, t(loc, "cancelled.title"),
                p(t(loc, "cancelled.body", esc(i.getDocumentName()), esc(i.getDefinitionName()), esc(actorEmail)))
                        + quote(comment)
                        + button(link, t(loc, "completed.button")));
        send(toEmail, subject, body);
    }

    @Override
    public void sendStateReached(WorkflowInstance i, String stateLabel, String toEmail, String link) {
        Locale loc = WorkflowMailTexts.localeOf(i.getLocale());
        String subject = t(loc, "reached.subject", i.getDocumentName(), stateLabel);
        String body = layout(loc, t(loc, "reached.title"),
                p(t(loc, "reached.body", esc(i.getDocumentName()), esc(i.getDefinitionName()), esc(stateLabel)))
                        + button(link, t(loc, "completed.button")));
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
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }

    private static String p(String inner) {
        return "<p style=\"margin:0 0 14px\">" + inner + "</p>";
    }

    private static String small(String inner) {
        return "<p style=\"margin:10px 0 0;font-size:12px;color:#667085\">" + inner + "</p>";
    }

    private static String quote(String text) {
        if (text == null || text.isBlank()) return "";
        return "<blockquote style=\"margin:0 0 14px;padding:10px 14px;border-left:3px solid #d0d5dd;color:#344054\">"
                + esc(text).replace("\n", "<br>") + "</blockquote>";
    }

    private static String button(String href, String label) {
        return "<p style=\"margin:22px 0\"><a href=\"" + href + "\" style=\"background:#1f6feb;color:#fff;"
                + "padding:12px 22px;border-radius:8px;text-decoration:none;font-weight:600;display:inline-block\">"
                + label + "</a></p>";
    }

    private String layout(Locale loc, String title, String content) {
        String dir = WorkflowMailTexts.isRtl(loc) ? "rtl" : "ltr";
        String logo = props.getMail().getLogoUrl() == null || props.getMail().getLogoUrl().isBlank() ? ""
                : "<img src=\"" + props.getMail().getLogoUrl() + "\" alt=\"\" style=\"max-height:40px;margin-bottom:16px\">";
        return "<!doctype html><html dir=\"" + dir + "\"><body style=\"margin:0;background:#f4f6f8;font-family:Segoe UI,Helvetica,Arial,sans-serif;color:#101828\">"
                + "<div style=\"max-width:560px;margin:24px auto;background:#fff;border-radius:12px;padding:28px;border:1px solid #e4e7ec\">"
                + logo
                + "<h2 style=\"margin:0 0 16px;font-size:20px\">" + title + "</h2>"
                + content
                + "<hr style=\"border:none;border-top:1px solid #e4e7ec;margin:24px 0 12px\">"
                + small(t(loc, "footer", esc(props.getMail().getProductName())))
                + "</div></body></html>";
    }
}
