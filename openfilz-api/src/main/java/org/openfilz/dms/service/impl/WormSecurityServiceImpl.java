package org.openfilz.dms.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.security.worm-mode", havingValue = "true")
public class WormSecurityServiceImpl extends AbstractSecurityService {

    @Value("${openfilz.security.no-auth}")
    private Boolean noAuth;

    @Value("${openfilz.features.custom-access:false}")
    private Boolean customAccess;

    @Value("${openfilz.calculate-checksum:false}")
    private Boolean calculateChecksum;

    @PostConstruct
    public void init() {
        if (noAuth) {
            throw new RuntimeException("Bad configuration : when openfilz.security.no-auth is true, openfilz.security.worm-mode must be false");
        }
        if(customAccess) {
            throw new RuntimeException("Bad configuration : when openfilz.security.custom-access is true, openfilz.security.worm-mode must be false");
        }
        if(!calculateChecksum) {
            throw new RuntimeException("Bad configuration : when openfilz.security.worm-mode is true, openfilz.calculate-checksum must be true");
        }
    }

    @Override
    protected boolean isDeleteAccess(ServerHttpRequest request) {
        return false;
    }

    @Override
    protected boolean isInsertOrUpdateAccess(HttpMethod method, String path) {
        return method.equals(HttpMethod.POST) && (
                        pathStartsWith(path, "/files/copy",
                                "/documents/upload",
                                "/documents/upload-multiple") ||
                                path.equals("/folders") ||
                                path.equals("/folders/copy"));
    }

}
