package org.openfilz.dms.service.unzip;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Fails as soon as more bytes are read than the entry declared in the central directory: a forged
 * size cannot make an entry inflate past the limits checked when the extraction was planned.
 */
public class SizeLimitedInputStream extends FilterInputStream {

    private final long limit;
    private final String entryPath;
    private long count;

    public SizeLimitedInputStream(InputStream in, long limit, String entryPath) {
        super(in);
        this.limit = limit;
        this.entryPath = entryPath;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            count(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            count(n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        count(skipped);
        return skipped;
    }

    private void count(long n) throws IOException {
        count += n;
        if (count > limit) {
            throw new IOException("ZIP entry '" + entryPath + "' inflates beyond its declared size of " + limit + " bytes");
        }
    }
}
