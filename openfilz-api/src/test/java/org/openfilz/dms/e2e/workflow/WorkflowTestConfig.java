package org.openfilz.dms.e2e.workflow;

import org.openfilz.dms.service.workflow.WorkflowMailer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class WorkflowTestConfig {

    @Bean
    @Primary
    public WorkflowMailer capturingWorkflowMailer() {
        return new CapturingWorkflowMailer();
    }
}
