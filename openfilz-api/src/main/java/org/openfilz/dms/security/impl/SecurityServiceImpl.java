package org.openfilz.dms.security.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.DefaultRolesNoWormCondition;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.config.ThumbnailProperties;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Conditional(DefaultRolesNoWormCondition.class)
public class SecurityServiceImpl extends AbstractSecurityService {


    public SecurityServiceImpl(OnlyOfficeProperties onlyOfficeProperties, ThumbnailProperties thumbnailProperties) {
        super(onlyOfficeProperties, thumbnailProperties);
    }
}
