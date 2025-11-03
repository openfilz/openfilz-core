package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;
import org.springframework.http.codec.multipart.FilePart;

public interface FullTextService {

    void indexDocument(FilePart filePart, Document document);

    void indexDocumentMetadata(Document document);
}
