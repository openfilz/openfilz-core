package org.openfilz.dms.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StorageExceptionTest {

    @Test
    void constructorWithCause_storesCause() {
        RuntimeException cause = new RuntimeException("root cause");
        StorageException ex = new StorageException(cause);
        assertEquals(cause, ex.getCause());
    }

    @Test
    void constructorWithMessage_storesMessage() {
        StorageException ex = new StorageException("storage error");
        assertEquals("storage error", ex.getMessage());
    }

    @Test
    void constructorWithMessageAndCause_storesBoth() {
        RuntimeException cause = new RuntimeException("root");
        StorageException ex = new StorageException("wrapped", cause);
        assertEquals("wrapped", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }

    @Test
    void getError_returnsStorage() {
        StorageException ex = new StorageException("test");
        assertEquals("Storage", ex.getError());
    }
}
