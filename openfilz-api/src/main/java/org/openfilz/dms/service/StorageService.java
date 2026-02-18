// com/example/dms/service/StorageService.java
package org.openfilz.dms.service;

import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
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

    // ==================== TUS Upload Support Methods ====================

    /**
     * TUS upload path prefix. Files stored under this prefix are temporary upload data.
     */
    String TUS_PREFIX = "_tus/";

    /**
     * Get the storage path for a TUS upload data file.
     */
    default String getTusDataPath(String uploadId) {
        return TUS_PREFIX + uploadId + ".bin";
    }

    /**
     * Get the storage path for a TUS upload metadata file.
     */
    default String getTusMetadataPath(String uploadId) {
        return TUS_PREFIX + uploadId + ".json";
    }

    /**
     * Create an empty file at the specified storage path.
     * Used for initializing TUS uploads.
     *
     * @param storagePath the path where the file should be created
     * @return empty Mono on success
     */
    Mono<Void> createEmptyFile(String storagePath);

    /**
     * Append data to a file at a specific offset.
     * Used for TUS chunk uploads. Chunks are always sequential (offset matches current file size).
     *
     * @param storagePath the path of the file to append to
     * @param data the data to append
     * @param offset the offset at which to write (must match current file size)
     * @return the new file size after appending
     */
    Mono<Long> appendData(String storagePath, Flux<DataBuffer> data, long offset);

    /**
     * Save data to a specific storage path.
     * Unlike saveFile which generates a unique filename, this writes to the exact path specified.
     * Overwrites existing file if present.
     *
     * @param storagePath the exact path where the data should be written
     * @param data the data to write as a Flux of DataBuffers
     * @return empty Mono on success
     */
    Mono<Void> saveData(String storagePath, Flux<DataBuffer> data);

    /**
     * Move/rename a file within storage.
     * Used for TUS finalization to move from temp to permanent location.
     *
     * @param sourcePath the current path
     * @param destPath the new path
     * @return empty Mono on success
     */
    Mono<Void> moveFile(String sourcePath, String destPath);

    /**
     * List all files under a prefix (for TUS cleanup).
     *
     * @param prefix the prefix to list
     * @return flux of storage paths under the prefix
     */
    Flux<String> listFiles(String prefix);

    default Mono<String> replaceFile(String oldStoragePath, FilePart newFilePart) {
        return saveFile(newFilePart);
    }
}