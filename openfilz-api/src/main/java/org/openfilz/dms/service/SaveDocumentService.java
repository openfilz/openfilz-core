package org.openfilz.dms.service;

import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.entity.Document;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public interface SaveDocumentService {

    String APPLICATION_OCTET_STREAM = "application/octet-stream";

    Mono<UploadResponse> doSaveDocument(FilePart filePart, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, String originalFilename, Mono<String> storagePathMono);

    Mono<Document> saveAndReplaceDocument(FilePart newFilePart, Long contentLength, Document document, String oldStoragePath);

    Function<String, Mono<Document>> saveNewDocumentFunction(Document.DocumentBuilder documentBuilder);

    Mono<Document> doSaveDocument(Function<String, Mono<Document>> documentFunction);
}
