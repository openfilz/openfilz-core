package org.openfilz.dms.security.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.DefaultRolesCondition;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.config.AutorizationMode;
import org.openfilz.dms.security.WormPolicy;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Conditional(DefaultRolesCondition.class)
public class SecurityServiceImpl extends AbstractSecurityService {


    public SecurityServiceImpl(AutorizationMode autorizationMode, OnlyOfficeProperties onlyOfficeProperties, ThumbnailProperties thumbnailProperties, WormPolicy wormPolicy) {
        super(autorizationMode, onlyOfficeProperties, thumbnailProperties, wormPolicy);
    }
}
