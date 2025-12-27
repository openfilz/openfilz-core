package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.dto.response.OnlyOfficeUserInfo;
import org.openfilz.dms.service.OnlyOfficeJwtExtractor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Implementation of OnlyOfficeJwtService using HMAC-SHA256.
 * Generates and validates JWT tokens for OnlyOffice DocumentServer integration.
 */
@Slf4j
@Service
@ConditionalOnProperties(value = {
        @ConditionalOnProperty(name = "onlyoffice.enabled", havingValue = "true"),
        @ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
})
public class OnlyOfficeJwtServiceImpl extends AbstractOnlyOfficeJwtService<OnlyOfficeUserInfo> {


    public OnlyOfficeJwtServiceImpl(OnlyOfficeProperties properties, OnlyOfficeJwtExtractor<OnlyOfficeUserInfo> extractor) {
        super(properties, extractor);
    }
}
