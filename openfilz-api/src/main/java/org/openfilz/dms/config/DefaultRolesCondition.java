package org.openfilz.dms.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class DefaultRolesCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Boolean customRole = context.getEnvironment().getProperty("spring.security.custom-roles", Boolean.class,Boolean.FALSE);
        Boolean noAuth = context.getEnvironment().getRequiredProperty("spring.security.no-auth", Boolean.class);

        return !noAuth && !customRole;
    }
}
