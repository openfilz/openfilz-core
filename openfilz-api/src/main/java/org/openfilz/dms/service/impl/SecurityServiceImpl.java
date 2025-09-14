package org.openfilz.dms.service.impl;

import org.openfilz.dms.enums.RoleTokenLookup;

public class SecurityServiceImpl extends AbstractSecurityService {

    public SecurityServiceImpl(RoleTokenLookup roleTokenLookup, String rootGroupName, String graphQlBaseUrl) {
        super(roleTokenLookup, rootGroupName, graphQlBaseUrl);
    }

}
