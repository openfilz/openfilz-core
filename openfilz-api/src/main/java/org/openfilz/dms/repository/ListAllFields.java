package org.openfilz.dms.repository;

import graphql.schema.DataFetchingEnvironment;
import org.openfilz.dms.entity.SqlColumnMapping;

import java.util.ArrayList;
import java.util.List;

public interface ListAllFields {

    default List<String> getCustomSqlFields(DataFetchingEnvironment environment) {
        List<String> sqlFields = getAllSqlFields(environment);
        if(!sqlFields.contains(SqlColumnMapping.TYPE)) {
            List<String> newList = new ArrayList<>(sqlFields);
            newList.add(SqlColumnMapping.TYPE);
            return newList;
        }
        return sqlFields;
    }

    List<String> getAllSqlFields(DataFetchingEnvironment environment);

}
