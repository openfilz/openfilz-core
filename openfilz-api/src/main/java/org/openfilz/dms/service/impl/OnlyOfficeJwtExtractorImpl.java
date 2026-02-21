package org.openfilz.dms.service.impl;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.openfilz.dms.dto.response.OnlyOfficeUserInfo;
import org.openfilz.dms.service.OnlyOfficeJwtExtractor;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import static org.openfilz.dms.security.JwtTokenParser.EMAIL;

@Service
@ConditionalOnProperties(value = {
        @ConditionalOnProperty(name = "onlyoffice.enabled", havingValue = "true"),
        @ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
})
public class OnlyOfficeJwtExtractorImpl implements OnlyOfficeJwtExtractor<OnlyOfficeUserInfo>, UserInfoService {

    @Override
    public JwtBuilder newJwt(OnlyOfficeUserInfo userInfo) {
        return Jwts.builder()
                .claim(USER_ID_CLAIM, userInfo.getId())
                .claim(USER_NAME_CLAIM, userInfo.getName())
                .claim(USER_EMAIL_CLAIM, userInfo.getEmail());
    }

    @Override
    public Mono<OnlyOfficeUserInfo> getUserInfo() {
        return getAuthenticationMono()
                .switchIfEmpty(Mono.error(new RuntimeException("No authentication available")))
                .flatMap(auth -> Mono.just(OnlyOfficeUserInfo.builder()
                        .id(getUserAttribute(auth, EMAIL))
                        .name(getUserAttribute(auth, "name"))
                        .email(getUserAttribute(auth, EMAIL))
                        .build()));
    }
}
