package org.openfilz.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.workflow.WorkflowMailer;
import org.openfilz.dms.service.workflow.impl.LoggingWorkflowMailer;
import org.openfilz.dms.service.workflow.impl.SmtpWorkflowMailer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Runtime wiring for workflows. The mailer is chosen from properties read inside the factory
 * (never a bean condition) so one native image serves every deployment.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(WorkflowProperties.class)
public class WorkflowConfig {

    @Bean
    public WorkflowMailer workflowMailer(@Value("${spring.mail.host:}") String mailHost,
                                         ObjectProvider<JavaMailSender> mailSender,
                                         WorkflowProperties props) {
        JavaMailSender sender = mailHost == null || mailHost.isBlank() ? null : mailSender.getIfAvailable();
        if (sender == null) {
            if (props.isActive()) {
                log.warn("[workflows] spring.mail.host is not set — task invitations will only be LOGGED, not emailed");
            }
            return new LoggingWorkflowMailer();
        }
        return new SmtpWorkflowMailer(sender, props);
    }
}
