// com/example/dms/service/StorageService.java
package org.openfilz.dms.service;

import org.springframework.core.io.Resource;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface StorageService {

    String FOLDER_SEPARATOR = "/";
    String FILENAME_SEPARATOR = "#";

    default String getUniqueStorageFileName(String originalFilename) {
        int i = originalFilename.lastIndexOf(FILENAME_SEPARATOR);
        if(i < 0) {
            return UUID.randomUUID() + FILENAME_SEPARATOR + originalFilename;
        }
        return UUID.randomUUID() + originalFilename.substring(i);
    }

    default String getOriginalFileName(String storagePath) {
        return storagePath.substring(storagePath.lastIndexOf(FILENAME_SEPARATOR) + 1);
    }

    Mono<String> saveFile(FilePart filePart); // Returns storage path/key

    Mono<? extends Resource> loadFile(String storagePath);

    Mono<Void> deleteFile(String storagePath);

    Mono<String> copyFile(String sourceStoragePath); // Returns new storage path/key

    Mono<Long> getFileLength(String storagePath);
}