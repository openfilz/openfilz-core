package org.openfilz.dms.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class MetadataPostProcessingCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Boolean thumbnailsActive = context.getEnvironment().getProperty("openfilz.thumbnail.active", Boolean.class, Boolean.FALSE);
        Boolean fullTextActive = context.getEnvironment().getProperty("openfilz.full-text.active", Boolean.class, Boolean.FALSE);

        return thumbnailsActive || fullTextActive;
    }
}
