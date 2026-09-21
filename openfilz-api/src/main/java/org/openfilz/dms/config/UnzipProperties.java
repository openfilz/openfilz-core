package org.openfilz.dms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for server-side ZIP extraction ({@code openfilz.unzip.*}). The limits protect the
 * instance against archive bombs: they are checked against the ZIP central directory before a
 * single byte is written, and again while each entry is inflated (a lying central directory
 * cannot bypass them).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "openfilz.unzip")
public class UnzipProperties {

    /** Maximum number of entries (files and folders) one archive may contain. */
    private int maxEntries = 10_000;

    /** Maximum total uncompressed size in bytes of one archive (default 4 GB). */
    private long maxUncompressedBytes = 4L * 1024 * 1024 * 1024;

    /**
     * Maximum uncompressed/compressed ratio of one entry. Only applied to entries larger than
     * {@link #ratioThresholdBytes}, so that small, highly repetitive text files stay accepted.
     */
    private int maxCompressionRatio = 200;

    /** Entries below this uncompressed size are exempt from the compression-ratio check (1 MB). */
    private long ratioThresholdBytes = 1024 * 1024;

    /**
     * How many entries are inflated and written to storage at the same time, per operation. Each
     * one holds a database connection while its row is written, so keep it below the R2DBC pool
     * size ({@code spring.r2dbc.pool.max-size}) or the extraction starves other requests of
     * connections. Measured on 2 000 × 4 KB files with the default pool of 10 — local storage:
     * 4 → 11 s, 8 → 4.8 s, 16 → 5.3 s; MinIO: 4 → 22 s, 8 → 11 s, 16 → 5.2 s (storage-bound, so a
     * MinIO deployment gains from 16 once the pool is raised accordingly).
     */
    private int parallelism = 8;
}
