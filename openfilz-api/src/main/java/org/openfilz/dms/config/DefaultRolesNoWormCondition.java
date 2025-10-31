package org.openfilz.dms.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class DefaultRolesNoWormCondition extends DefaultRolesCondition{
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Boolean wormMode = context.getEnvironment().getProperty("openfilz.security.worm-mode", Boolean.class, Boolean.FALSE);

        return super.matches(context, metadata) && !wormMode;
    }
}
