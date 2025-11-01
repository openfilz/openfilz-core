package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;
import reactor.core.publisher.Mono;

public interface IndexService {
    Mono<Void> indexDocument(Document document, Mono<String> text);
}
