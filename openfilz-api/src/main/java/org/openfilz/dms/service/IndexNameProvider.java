package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;

import java.util.UUID;

public interface IndexNameProvider {

    String DEFAULT_INDEX_NAME = "openfilz";

    String getIndexName(Document document);

    String getIndexName(UUID documentId);

    String getDocumentsIndexName();
}
