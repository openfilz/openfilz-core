package org.openfilz.dms.mapper;

import io.r2dbc.postgresql.codec.Json;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.utils.JsonUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.openfilz.dms.entity.SqlColumnMapping.METADATA;

@Mapper(componentModel = "spring")
public abstract class DocumentMapper {

    @Autowired
    private JsonUtils jsonUtils;

    @Mapping(source = METADATA, target = METADATA, qualifiedByName = "mapMetadataToMap")
    public abstract FullDocumentInfo toFullDocumentInfo(Document document);

    @Named("mapMetadataToMap")
    public Map<String, Object> mapMetadataToMap(Json value) {
        if(value != null) {
            return jsonUtils.toMap(value);
        }
        return null;
    }
}
