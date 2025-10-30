package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.DefaultRolesCondition;
import org.openfilz.dms.config.DefaultRolesNoWormCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@Conditional(DefaultRolesNoWormCondition.class)
public class SecurityServiceImpl extends AbstractSecurityService {


}
