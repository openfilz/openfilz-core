package org.openfilz.dms.repository.graphql;

import lombok.Getter;
import org.openfilz.dms.entity.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class DocumentFields {

    @Getter
    protected final Map<String, String> documentFieldSqlMap;

    public DocumentFields() {
        documentFieldSqlMap = Map.copyOf(buildFieldToColumnMap());

    }

    protected Map<String, String> buildFieldToColumnMap() {
        Map<String, String> map = new HashMap<>();

        for (Field field : Document.class.getDeclaredFields()) {
            String fieldName = field.getName();
            String columnName = null;

            // Check for @Id annotation
            if (field.isAnnotationPresent(Id.class) && field.isAnnotationPresent(Column.class)) {
                columnName = field.getAnnotation(Column.class).value();
            }
            // Check for @Column annotation
            else if (field.isAnnotationPresent(Column.class)) {
                columnName = field.getAnnotation(Column.class).value();
            }

            if (columnName != null && !columnName.isEmpty()) {
                map.put(fieldName, columnName);
            }
        }

        return map;
    }

}
