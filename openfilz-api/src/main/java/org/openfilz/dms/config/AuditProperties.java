package org.openfilz.dms.config;

import lombok.Data;
import org.openfilz.dms.enums.AuditAction;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Data
@Configuration
@ConfigurationProperties(prefix = "openfilz.audit")
public class AuditProperties {

    /**
     * Set of actions excluded from auditing. If empty, all actions are auditable.
     */
    private Set<AuditAction> excludedActions = Set.of();
}
