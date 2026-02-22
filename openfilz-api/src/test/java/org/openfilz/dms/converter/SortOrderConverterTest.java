package org.openfilz.dms.converter;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.enums.SortOrder;

import static org.junit.jupiter.api.Assertions.*;

class SortOrderConverterTest {

    private final SortOrderConverter converter = new SortOrderConverter();

    @Test
    void convert_validAsc_returnsSortOrder() {
        assertEquals(SortOrder.ASC, converter.convert("asc"));
    }

    @Test
    void convert_validDesc_returnsSortOrder() {
        assertEquals(SortOrder.DESC, converter.convert("DESC"));
    }

    @Test
    void convert_invalidValue_throwsRuntimeException() {
        assertThrows(RuntimeException.class, () -> converter.convert("invalid"));
    }
}
