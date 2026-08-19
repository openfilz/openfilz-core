package org.openfilz.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.DocumentVersionService;
import org.openfilz.dms.service.impl.DocumentVersionServiceImpl;
import org.openfilz.dms.service.impl.NoOpDocumentVersionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Runtime selection of the document-version service (same native-image-safe pattern
 * as {@link StorageConfig}: both implementations are {@code @Lazy} beans compiled into
 * the image; the property is read at runtime, and the unused implementation is never
 * initialized).
 * <p>
 * Versioning is effective only when {@code storage.type=minio} AND
 * {@code storage.minio.versioning-enabled=true}.
 */
@Slf4j
@Configuration
public class DocumentVersionConfig {

    @Bean
    @Primary
    public DocumentVersionService documentVersionService(
            @Value("${storage.type:local}") String storageType,
            @Value("${storage.minio.versioning-enabled:false}") boolean versioningEnabled,
            ObjectProvider<DocumentVersionServiceImpl> enabledProvider,
            ObjectProvider<NoOpDocumentVersionService> disabledProvider) {
        if ("minio".equals(storageType) && versioningEnabled) {
            log.info("Document versioning: enabled");
            return enabledProvider.getIfAvailable();
        }
        log.info("Document versioning: disabled");
        return disabledProvider.getIfAvailable();
    }
}
