package org.openfilz.dms.service;

import io.jsonwebtoken.JwtBuilder;
import org.openfilz.dms.dto.response.IUserInfo;
import reactor.core.publisher.Mono;

public interface OnlyOfficeJwtExtractor<T extends IUserInfo> {

    String USER_ID_CLAIM = "userId";
    String USER_NAME_CLAIM = "userName";

    JwtBuilder newJwt(T userInfo);

    Mono<T> getUserInfo();
}
