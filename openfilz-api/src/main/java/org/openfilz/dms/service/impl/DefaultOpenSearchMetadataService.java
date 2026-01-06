package org.openfilz.dms.service.impl;

import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.service.OpenSearchMetadataService;
import org.openfilz.dms.utils.FileUtils;
import org.openfilz.dms.utils.JsonUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
@RequiredArgsConstructor
@ConditionalOnProperties({
        @ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true"),
        @ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
})
public class DefaultOpenSearchMetadataService implements OpenSearchMetadataService {

    private final JsonUtils jsonUtils;

    public Mono<Map<String, Object>> fillOpenSearchDocumentMetadataMap(Document document, Map<String, Object> source) {
        source.put(OpenSearchDocumentKey.id.toString(), document.getId());
        source.put(OpenSearchDocumentKey.name.toString(), document.getName());
        source.put(OpenSearchDocumentKey.name_suggest.toString(), FileUtils.removeFileExtension(document.getName()));
        source.put(OpenSearchDocumentKey.extension.toString(), FileUtils.getDocumentExtension(document.getType(), document.getName()));
        source.put(OpenSearchDocumentKey.size.toString(), document.getSize());
        source.put(OpenSearchDocumentKey.parentId.toString(), document.getParentId());
        source.put(OpenSearchDocumentKey.createdAt.toString(), document.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        source.put(OpenSearchDocumentKey.createdBy.toString(), document.getCreatedBy());
        source.put(OpenSearchDocumentKey.updatedAt.toString(), document.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        source.put(OpenSearchDocumentKey.updatedBy.toString(), document.getUpdatedBy());
        Json metadata = document.getMetadata();
        if(metadata != null) {
            source.put(OpenSearchDocumentKey.metadata.toString(), jsonUtils.toMap(metadata));
        }
        return Mono.just(source);
    }

}
