package org.openfilz.dms.security.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AutorizationMode;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.config.ThumbnailProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class WormSecurityServiceImplTest {

    private WormSecurityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WormSecurityServiceImpl(
                mock(AutorizationMode.class),
                mock(OnlyOfficeProperties.class),
                mock(ThumbnailProperties.class));
        ReflectionTestUtils.setField(service, "noAuth", false);
        ReflectionTestUtils.setField(service, "customAccess", false);
        ReflectionTestUtils.setField(service, "calculateChecksum", true);
    }

    @Test
    void isDeleteAccess_alwaysFalse() {
        assertFalse(service.isDeleteAccess(mock(ServerHttpRequest.class)));
    }

    @Test
    void isInsertOrUpdateAccess_allowsUploadsAndFolderCopy() {
        assertTrue(service.isInsertOrUpdateAccess(HttpMethod.POST, "/documents/upload"));
        assertTrue(service.isInsertOrUpdateAccess(HttpMethod.POST, "/documents/upload-multiple"));
        assertTrue(service.isInsertOrUpdateAccess(HttpMethod.POST, "/files/copy"));
        assertTrue(service.isInsertOrUpdateAccess(HttpMethod.POST, "/folders"));
        assertTrue(service.isInsertOrUpdateAccess(HttpMethod.POST, "/folders/copy"));
    }

    @Test
    void isInsertOrUpdateAccess_rejectsMutationsAndNonPost() {
        assertFalse(service.isInsertOrUpdateAccess(HttpMethod.POST, "/documents/rename"));
        assertFalse(service.isInsertOrUpdateAccess(HttpMethod.PUT, "/documents/upload"));
        assertFalse(service.isInsertOrUpdateAccess(HttpMethod.DELETE, "/folders"));
    }

    @Test
    void init_validConfiguration_passes() {
        assertDoesNotThrow(() -> service.init());
    }

    @Test
    void init_whenNoAuth_throws() {
        ReflectionTestUtils.setField(service, "noAuth", true);
        assertThrows(RuntimeException.class, () -> service.init());
    }

    @Test
    void init_whenCustomAccess_throws() {
        ReflectionTestUtils.setField(service, "customAccess", true);
        assertThrows(RuntimeException.class, () -> service.init());
    }

    @Test
    void init_whenChecksumDisabled_throws() {
        ReflectionTestUtils.setField(service, "calculateChecksum", false);
        assertThrows(RuntimeException.class, () -> service.init());
    }
}
