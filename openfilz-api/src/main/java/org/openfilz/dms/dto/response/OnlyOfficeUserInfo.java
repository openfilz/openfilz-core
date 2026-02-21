package org.openfilz.dms.dto.response;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
public class OnlyOfficeUserInfo implements IUserInfo {
    private String id;
    private String name;
    private String email;
}
