package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface OpenSearchMetadataService {
    Mono<Map<String, Object>> fillOpenSearchDocumentMetadataMap(Document document, Map<String, Object> source);
}
