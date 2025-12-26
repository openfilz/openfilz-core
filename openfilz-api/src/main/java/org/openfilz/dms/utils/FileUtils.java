package org.openfilz.dms.utils;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
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

    public static String getFileExtension(DocumentType type, String name) {
        if(DocumentType.FOLDER.equals(type)) {
            return null;
        }
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

}
