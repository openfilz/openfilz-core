package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;

import java.util.UUID;

public interface IndexNameProvider {

    String getIndexName(Document document);

    String getIndexName(UUID documentId);

    String getDocumentsIndexName();
}
