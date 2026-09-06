package org.openfilz.dms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code openfilz.workflows.*} — see docs/workflows.md §10. Every value is read at runtime
 * (never a bean condition) so one native image serves deployments with the feature on or off.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "openfilz.workflows")
public class WorkflowProperties {

    /** Master switch: endpoints answer 404, the sweeper idles and Settings.workflowsActive is false when off. */
    private boolean active = false;

    /** When true, definition writes also require the WORKFLOW_DESIGNER role. */
    private boolean requireDesignerRole = false;

    /** Overrides openfilz.common.web-public-base-url for the links in workflow e-mails. */
    private String webBaseUrl = "";

    /** Size guard on a definition. */
    private int maxStates = 30;

    private final Mail mail = new Mail();

    @Getter
    @Setter
    public static class Mail {
        private String from = "no-reply@openfilz.com";
        private String fromName = "OpenFilz Workflows";
        private String productName = "OpenFilz";
        private String logoUrl = "";
    }
}
