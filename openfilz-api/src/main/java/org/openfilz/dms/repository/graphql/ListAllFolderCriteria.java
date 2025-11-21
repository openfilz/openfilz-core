package org.openfilz.dms.repository.graphql;

import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static org.openfilz.dms.entity.SqlColumnMapping.*;
import static org.openfilz.dms.utils.SqlUtils.WHERE;

@Component("allFolders")
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class ListAllFolderCriteria extends ListFolderCriteria {

    public ListAllFolderCriteria(SqlUtils sqlUtils) {
        super(sqlUtils);
    }

    public boolean appendWhereParentIdFilter(String prefix, StringBuilder query, ListFolderRequest request) {
        if(request.id() != null) {
            query.append(WHERE);
            sqlUtils.appendEqualsCriteria(prefix, PARENT_ID, query);
            return  true;
        }
        return false;
    }



}
