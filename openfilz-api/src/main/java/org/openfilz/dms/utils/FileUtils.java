package org.openfilz.dms.utils;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;

import java.util.HashMap;
import java.util.Map;

import static org.openfilz.dms.service.ChecksumService.HASH_SHA256_KEY;

@UtilityClass
public class FileUtils {

    public static String removeFileExtension(String name) {
        int endIndex = name.lastIndexOf(".");
        if(endIndex > 0) {
            return name.substring(0, endIndex);
        }
        return name;
    }

    public static String getDocumentExtension(DocumentType type, String name) {
        if(DocumentType.FOLDER.equals(type)) {
            return null;
        }
        return getFileExtension(name);
    }

    private static String getFileExtension(String name) {
        int endIndex = name.lastIndexOf(".");
        if(endIndex > 0 && endIndex < name.length() - 1) {
            return name.substring(endIndex + 1);
        }
        return "";
    }

    public static Map<String, Object> getMetadataWithChecksum(Map<String, Object> metadata, String checksum) {
        Map<String, Object> newMap;
        if(metadata == null) {
            newMap = Map.of(HASH_SHA256_KEY, checksum);
        } else {
            newMap = new HashMap<>(metadata);
            newMap.put(HASH_SHA256_KEY, checksum);
        }
        return newMap;
    }

    public static String getContentType(FilePart  filePart) {
        MediaType contentType = filePart.headers().getContentType();
        return contentType != null && !MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(contentType.toString()) ?
                contentType.toString() :
                ContentTypeMapper.getContentType(getFileExtension(filePart.filename()));
    }

}
