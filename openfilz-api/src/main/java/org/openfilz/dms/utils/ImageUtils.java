package org.openfilz.dms.utils;

import com.luciad.imageio.webp.WebPWriteParam;
import com.twelvemonkeys.imageio.metadata.CompoundDirectory;
import com.twelvemonkeys.imageio.metadata.Directory;
import com.twelvemonkeys.imageio.metadata.Entry;
import com.twelvemonkeys.imageio.metadata.exif.EXIFReader;
import com.twelvemonkeys.imageio.metadata.exif.TIFF;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import reactor.core.publisher.Mono;

import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

@Slf4j
@UtilityClass
public class ImageUtils {

    private static final int TARGET_MAX_SIZE_KB = 5;
    private static final int TARGET_MAX_BYTES = TARGET_MAX_SIZE_KB * 1024;

    // EXIF Orientation tag
    private static final int EXIF_ORIENTATION_TAG = TIFF.TAG_ORIENTATION;

    // Size of buffer to read for EXIF extraction (64KB is plenty for JPEG headers)
    private static final int EXIF_BUFFER_SIZE = 64 * 1024;

    public static Mono<byte[]> resourceToWebpThumbnail(Resource resource) {
        return Mono.fromCallable(() -> {
            // Use a single stream with BufferedInputStream for mark/reset support
            // This avoids issues with storage backends that don't support multiple getInputStream() calls
            try (InputStream rawIs = resource.getInputStream();
                 BufferedInputStream bis = new BufferedInputStream(rawIs, EXIF_BUFFER_SIZE)) {

                // 1. Check if JPEG and extract EXIF orientation (mark/reset to reuse stream)
                bis.mark(EXIF_BUFFER_SIZE);
                byte[] headerBuffer = readLimitedBytes(bis, EXIF_BUFFER_SIZE);
                int orientation = readExifOrientationFromBytes(headerBuffer);
                log.info("EXIF orientation detected: {}", orientation);

                // Reset stream to beginning for image decoding
                bis.reset();

                // 2. Read image with subsampling
                BufferedImage raw;
                try (ImageInputStream iis = new MemoryCacheImageInputStream(bis)) {
                    Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                    if (!readers.hasNext()) {
                        throw new IllegalArgumentException("Unsupported image format");
                    }

                    ImageReader reader = readers.next();
                    reader.setInput(iis, true, true); // Can ignore metadata, we already have orientation

                    // Apply subsampling for faster reading of large images
                    ImageReadParam param = reader.getDefaultReadParam();
                    param.setSourceSubsampling(4, 4, 0, 0);

                    raw = reader.read(0, param);
                    reader.dispose();
                }

                log.debug("Raw image size: {}x{}", raw.getWidth(), raw.getHeight());

                // 3. Apply EXIF orientation correction
                BufferedImage oriented = applyExifOrientation(raw, orientation);
                log.debug("Oriented image size: {}x{}", oriented.getWidth(), oriented.getHeight());

                // 4. Resize to thumbnail
                BufferedImage resized = resizeAndCrop(oriented, 100);

                // 5. Compress to WebP with target size
                return compressWebpToTargetSize(resized, TARGET_MAX_BYTES);
            }
        });
    }

    /**
     * Read up to maxBytes from the input stream without loading the entire stream.
     */
    private static byte[] readLimitedBytes(InputStream is, int maxBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(maxBytes);
        byte[] buffer = new byte[8192];
        int totalRead = 0;
        int bytesRead;

        while (totalRead < maxBytes && (bytesRead = is.read(buffer, 0, Math.min(buffer.length, maxBytes - totalRead))) != -1) {
            baos.write(buffer, 0, bytesRead);
            totalRead += bytesRead;
        }

        return baos.toByteArray();
    }

    /**
     * Read EXIF orientation directly from image bytes using TwelveMonkeys EXIFReader.
     * This is the most reliable method as it parses the EXIF segment directly.
     */
    private int readExifOrientationFromBytes(byte[] imageData) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(imageData);
             ImageInputStream iis = new MemoryCacheImageInputStream(bais)) {

            // Find JPEG APP1 (EXIF) segment
            // JPEG starts with FFD8, APP1 is FFE1
            int b1 = iis.read();
            int b2 = iis.read();

            if (b1 != 0xFF || b2 != 0xD8) {
                // Not a JPEG - PNG and other formats don't have EXIF orientation
                // Return default orientation (no rotation needed)
                log.debug("Not a JPEG file, using default orientation");
                return 1;
            }

            // Scan for APP1 marker (EXIF data)
            while (true) {
                int marker1 = iis.read();
                if (marker1 != 0xFF) {
                    break;
                }

                int marker2 = iis.read();
                if (marker2 == -1) {
                    break;
                }

                // Skip padding bytes
                while (marker2 == 0xFF) {
                    marker2 = iis.read();
                }

                // Read segment length
                int lengthHigh = iis.read();
                int lengthLow = iis.read();
                if (lengthHigh == -1 || lengthLow == -1) {
                    break;
                }
                int length = (lengthHigh << 8) | lengthLow;

                // APP1 marker (0xE1) contains EXIF
                if (marker2 == 0xE1) {
                    // Read the segment data
                    byte[] segmentData = new byte[length - 2];
                    iis.readFully(segmentData);

                    // Check for "Exif\0\0" header
                    if (segmentData.length >= 6 &&
                            segmentData[0] == 'E' && segmentData[1] == 'x' &&
                            segmentData[2] == 'i' && segmentData[3] == 'f' &&
                            segmentData[4] == 0 && segmentData[5] == 0) {

                        // Parse EXIF data (after the 6-byte header)
                        byte[] exifData = new byte[segmentData.length - 6];
                        System.arraycopy(segmentData, 6, exifData, 0, exifData.length);

                        return parseExifOrientation(exifData);
                    }
                } else if (marker2 == 0xDA) {
                    // Start of scan - no more metadata
                    break;
                } else {
                    // Skip this segment
                    iis.skipBytes(length - 2);
                }
            }
        } catch (Exception e) {
            log.debug("Error reading EXIF from JPEG: {}", e.getMessage());
        }

        return 1; // Default orientation
    }

    /**
     * Parse EXIF TIFF structure to find orientation tag.
     */
    private int parseExifOrientation(byte[] exifData) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(exifData);
             ImageInputStream iis = new MemoryCacheImageInputStream(bais)) {

            // Use TwelveMonkeys EXIFReader to parse the TIFF structure
            EXIFReader exifReader = new EXIFReader();
            Directory directory = exifReader.read(iis);

            if (directory instanceof CompoundDirectory compoundDir) {
                // Search through all directories (IFD0, EXIF IFD, etc.)
                for (int i = 0; i < compoundDir.directoryCount(); i++) {
                    Directory subDir = compoundDir.getDirectory(i);
                    Integer orientation = findOrientationInDirectory(subDir);
                    if (orientation != null && orientation != 1) {
                        log.debug("Found orientation {} in directory {}", orientation, i);
                        return orientation;
                    }
                }
            } else if (directory != null) {
                Integer orientation = findOrientationInDirectory(directory);
                if (orientation != null) {
                    return orientation;
                }
            }
        } catch (Exception e) {
            log.debug("Error parsing EXIF structure: {}", e.getMessage());
        }

        return 1;
    }

    /**
     * Find orientation entry in a single directory.
     */
    private Integer findOrientationInDirectory(Directory directory) {
        if (directory == null) {
            return null;
        }

        for (Entry entry : directory) {
            if (entry.getIdentifier() instanceof Integer id && id == EXIF_ORIENTATION_TAG) {
                Object value = entry.getValue();
                if (value instanceof Number num) {
                    return num.intValue();
                }
            }
        }
        return null;
    }

    /**
     * Compress image to WebP format with target maximum size.
     * Uses LOSSY compression for better size control.
     */
    private byte[] compressWebpToTargetSize(BufferedImage image, int maxBytes) throws IOException {
        BufferedImage rgbImage = convertToRgb(image);

        float[] qualityLevels = {0.85f, 0.75f, 0.65f, 0.55f, 0.45f, 0.35f, 0.25f, 0.15f, 0.05f};

        for (float quality : qualityLevels) {
            byte[] webp = writeWebpLossy(rgbImage, quality);
            log.debug("WebP at quality {}: {} bytes", quality, webp.length);
            if (webp.length <= maxBytes) {
                return webp;
            }
        }

        log.warn("Could not compress WebP to target size of {} bytes, returning smallest possible", maxBytes);
        return writeWebpLossy(rgbImage, 0.01f);
    }

    /**
     * Convert ARGB image to RGB (removes alpha channel).
     */
    private BufferedImage convertToRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }

        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private BufferedImage resizeAndCrop(BufferedImage src, int maxSize) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();

        if (srcW <= maxSize && srcH <= maxSize) {
            return src;
        }

        double scale = Math.min(
                (double) maxSize / srcW,
                (double) maxSize / srcH
        );

        int targetW = (int) Math.round(srcW * scale);
        int targetH = (int) Math.round(srcH * scale);

        BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, targetW, targetH);
        g.drawImage(src, 0, 0, targetW, targetH, null);
        g.dispose();

        return resized;
    }

    /**
     * Apply EXIF orientation transformation.
     *
     * EXIF Orientation values:
     * 1 = Normal (0° rotation)
     * 2 = Flip horizontal
     * 3 = Rotate 180°
     * 4 = Flip vertical
     * 5 = Transpose (rotate 90° CW + flip horizontal)
     * 6 = Rotate 90° CW (portrait photo taken with phone rotated right)
     * 7 = Transverse (rotate 90° CCW + flip horizontal)
     * 8 = Rotate 90° CCW (portrait photo taken with phone rotated left)
     */
    private BufferedImage applyExifOrientation(BufferedImage src, int orientation) {
        if (orientation == 1) {
            return src;
        }

        int w = src.getWidth();
        int h = src.getHeight();
        int newW = w;
        int newH = h;

        // For orientations 5-8, width and height are swapped
        if (orientation >= 5 && orientation <= 8) {
            newW = h;
            newH = w;
        }

        BufferedImage dst = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, newW, newH);

        // Apply transformation based on orientation
        AffineTransform tx = getExifTransform(orientation, w, h);
        g.drawImage(src, tx, null);
        g.dispose();

        log.info("Applied EXIF orientation {}: {}x{} -> {}x{}", orientation, w, h, newW, newH);
        return dst;
    }

    /**
     * Get the AffineTransform for a given EXIF orientation.
     */
    private AffineTransform getExifTransform(int orientation, int width, int height) {
        AffineTransform tx = new AffineTransform();

        switch (orientation) {
            case 2 -> {
                // Flip horizontal
                tx.scale(-1, 1);
                tx.translate(-width, 0);
            }
            case 3 -> {
                // Rotate 180°
                tx.translate(width, height);
                tx.rotate(Math.PI);
            }
            case 4 -> {
                // Flip vertical
                tx.scale(1, -1);
                tx.translate(0, -height);
            }
            case 5 -> {
                // Transpose: rotate 90° CW then flip horizontal
                tx.rotate(Math.PI / 2);
                tx.scale(1, -1);
            }
            case 6 -> {
                // Rotate 90° CW
                tx.translate(height, 0);
                tx.rotate(Math.PI / 2);
            }
            case 7 -> {
                // Transverse: rotate 90° CW then flip vertical
                tx.translate(height, width);
                tx.rotate(Math.PI / 2);
                tx.scale(1, -1);
            }
            case 8 -> {
                // Rotate 90° CCW (270° CW)
                tx.translate(0, width);
                tx.rotate(-Math.PI / 2);
            }
            default -> log.warn("Unknown EXIF orientation: {}", orientation);
        }

        return tx;
    }

    /**
     * Write image as WebP using LOSSY compression.
     */
    private byte[] writeWebpLossy(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("webp");
        if (!writers.hasNext()) {
            throw new IOException("No WebP writer available");
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();

        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);

        String[] compressionTypes = param.getCompressionTypes();
        if (compressionTypes != null && compressionTypes.length > 0) {
            param.setCompressionType(compressionTypes[WebPWriteParam.LOSSY_COMPRESSION]);
        }
        param.setCompressionQuality(quality);

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }

        return out.toByteArray();
    }
}
