package org.openfilz.dms.repository;

import io.r2dbc.spi.Readable;

import java.util.function.Function;

public interface SqlQueryUtils {

    default Function<Readable, Long> getCountMappingFunction() {
        return row -> row.get(0, Long.class);
    }

}
