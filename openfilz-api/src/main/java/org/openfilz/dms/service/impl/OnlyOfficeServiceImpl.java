package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.dto.response.OnlyOfficeUserInfo;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.OnlyOfficeJwtExtractor;
import org.openfilz.dms.service.OnlyOfficeJwtService;
import org.openfilz.dms.service.StorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Implementation of OnlyOfficeService for document editing with OnlyOffice DocumentServer.
 */
@Slf4j
@Service
@ConditionalOnProperties(value = {
        @ConditionalOnProperty(name = "onlyoffice.enabled", havingValue = "true"),
        @ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
})
public class OnlyOfficeServiceImpl extends AbstractOnlyOfficeService<OnlyOfficeUserInfo> {


    public OnlyOfficeServiceImpl(CommonProperties commonProperties, OnlyOfficeProperties onlyOfficeProperties, OnlyOfficeJwtService<OnlyOfficeUserInfo> jwtService, OnlyOfficeJwtExtractor<OnlyOfficeUserInfo> jwtExtactor, DocumentDAO documentDAO, DocumentService documentService, WebClient.Builder webClientBuilder) {
        super(commonProperties, onlyOfficeProperties, jwtService, jwtExtactor, documentDAO, documentService, webClientBuilder);
    }
}
