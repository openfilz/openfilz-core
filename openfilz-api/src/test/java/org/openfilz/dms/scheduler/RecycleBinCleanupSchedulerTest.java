package org.openfilz.dms.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.RecycleBinProperties;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.repository.impl.DocumentSoftDeleteDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.StorageService;
import org.springframework.transaction.reactive.TransactionalOperator;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecycleBinCleanupSchedulerTest {

    @Mock
    private DocumentDAO documentDAO;
    @Mock
    private DocumentSoftDeleteDAO documentSoftDeleteDAO;
    @Mock
    private StorageService storageService;
    @Mock
    private AuditService auditService;
    @Mock
    private RecycleBinProperties recycleBinProperties;
    @Mock
    private TransactionalOperator tx;
    @Mock
    private MetadataPostProcessor metadataPostProcessor;

    @Test
    void cleanupExpiredItems_whenDisabled_skipsWork() {
        when(recycleBinProperties.isEnabled()).thenReturn(false);

        RecycleBinCleanupScheduler scheduler = new RecycleBinCleanupScheduler(
                documentDAO, documentSoftDeleteDAO, storageService, auditService,
                recycleBinProperties, tx, metadataPostProcessor);

        scheduler.cleanupExpiredItems();

        verifyNoInteractions(documentSoftDeleteDAO, documentDAO, storageService);
    }
}
