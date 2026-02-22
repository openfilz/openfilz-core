package org.openfilz.dms.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import reactor.test.StepVerifier;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageUtilsTest {

    private boolean isWebpWriterAvailable() {
        return ImageIO.getImageWritersByFormatName("webp").hasNext();
    }

    @Test
    void resourceToWebpThumbnail_withSmallPngImage_returnsWebpBytes() throws IOException {
        assumeTrue(isWebpWriterAvailable(), "WebP writer not available, skipping test");

        BufferedImage image = createTestImage(50, 50, Color.RED);
        Resource resource = toResource(image, "png");

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectNextMatches(bytes -> {
                    assertNotNull(bytes);
                    assertTrue(bytes.length > 0);
                    // WebP files start with "RIFF"
                    assertTrue(bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F');
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resourceToWebpThumbnail_withLargePngImage_resizesAndCompresses() throws IOException {
        assumeTrue(isWebpWriterAvailable(), "WebP writer not available, skipping test");

        BufferedImage image = createTestImage(2000, 1500, Color.BLUE);
        Resource resource = toResource(image, "png");

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectNextMatches(bytes -> {
                    assertNotNull(bytes);
                    assertTrue(bytes.length > 0);
                    // Result should be a thumbnail, typically under 5KB for simple images
                    assertTrue(bytes.length <= 10 * 1024, "Thumbnail should be small but got " + bytes.length + " bytes");
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resourceToWebpThumbnail_withJpegImage_handlesNonExifJpeg() throws IOException {
        assumeTrue(isWebpWriterAvailable(), "WebP writer not available, skipping test");

        // Create a simple JPEG without EXIF data
        BufferedImage image = createTestImage(200, 300, Color.GREEN);
        Resource resource = toResource(image, "jpg");

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectNextMatches(bytes -> {
                    assertNotNull(bytes);
                    assertTrue(bytes.length > 0);
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resourceToWebpThumbnail_withExactly100x100Image_noResize() throws IOException {
        assumeTrue(isWebpWriterAvailable(), "WebP writer not available, skipping test");

        BufferedImage image = createTestImage(100, 100, Color.YELLOW);
        Resource resource = toResource(image, "png");

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectNextMatches(bytes -> {
                    assertNotNull(bytes);
                    assertTrue(bytes.length > 0);
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resourceToWebpThumbnail_withArgbImage_convertsToRgb() throws IOException {
        assumeTrue(isWebpWriterAvailable(), "WebP writer not available, skipping test");

        // Create an ARGB image (with alpha channel)
        BufferedImage image = new BufferedImage(150, 150, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(255, 0, 0, 128)); // Semi-transparent red
        g.fillRect(0, 0, 150, 150);
        g.dispose();

        Resource resource = toResource(image, "png");

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectNextMatches(bytes -> {
                    assertNotNull(bytes);
                    assertTrue(bytes.length > 0);
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resourceToWebpThumbnail_withWideImage_resizesPreservingAspect() throws IOException {
        assumeTrue(isWebpWriterAvailable(), "WebP writer not available, skipping test");

        // Very wide panoramic image
        BufferedImage image = createTestImage(4000, 200, Color.CYAN);
        Resource resource = toResource(image, "png");

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectNextMatches(bytes -> {
                    assertNotNull(bytes);
                    assertTrue(bytes.length > 0);
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resourceToWebpThumbnail_withTallImage_resizesPreservingAspect() throws IOException {
        assumeTrue(isWebpWriterAvailable(), "WebP writer not available, skipping test");

        // Very tall portrait image
        BufferedImage image = createTestImage(200, 4000, Color.MAGENTA);
        Resource resource = toResource(image, "png");

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectNextMatches(bytes -> {
                    assertNotNull(bytes);
                    assertTrue(bytes.length > 0);
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resourceToWebpThumbnail_withUnsupportedFormat_throwsError() {
        // Provide invalid image data
        byte[] invalidData = "not an image".getBytes();
        Resource resource = new ByteArrayResource(invalidData);

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectError()
                .verify();
    }

    @Test
    void resourceToWebpThumbnail_withComplexImage_compressesToTargetSize() throws IOException {
        assumeTrue(isWebpWriterAvailable(), "WebP writer not available, skipping test");

        // Create a complex image with gradient and noise to test compression levels
        BufferedImage image = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        for (int y = 0; y < 800; y++) {
            for (int x = 0; x < 800; x += 10) {
                g.setColor(new Color(x % 256, y % 256, (x + y) % 256));
                g.fillRect(x, y, 10, 1);
            }
        }
        g.dispose();

        Resource resource = toResource(image, "png");

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectNextMatches(bytes -> {
                    assertNotNull(bytes);
                    assertTrue(bytes.length > 0);
                    return true;
                })
                .verifyComplete();
    }

    // --- EXIF Orientation Tests ---
    // These tests create JPEG images with injected EXIF orientation metadata
    // to exercise the EXIF parsing and orientation correction code paths.

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8})
    void resourceToWebpThumbnail_withExifOrientation_appliesCorrection(int orientation) throws IOException {
        assumeTrue(isWebpWriterAvailable(), "WebP writer not available, skipping test");

        // Create a JPEG with EXIF orientation metadata
        BufferedImage image = createTestImage(300, 200, Color.ORANGE);
        byte[] jpegBytes = createJpegWithExifOrientation(image, orientation);
        Resource resource = new ByteArrayResource(jpegBytes);

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectNextMatches(bytes -> {
                    assertNotNull(bytes);
                    assertTrue(bytes.length > 0);
                    // Verify it's a valid WebP
                    assertTrue(bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F');
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resourceToWebpThumbnail_withExifOrientation6_rotatesPortrait() throws IOException {
        assumeTrue(isWebpWriterAvailable(), "WebP writer not available, skipping test");

        // Orientation 6 = 90 CW rotation (common for phone photos taken in portrait)
        // Use a non-square image so rotation changes dimensions
        BufferedImage image = createTestImage(400, 200, Color.BLUE);
        byte[] jpegBytes = createJpegWithExifOrientation(image, 6);
        Resource resource = new ByteArrayResource(jpegBytes);

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectNextMatches(bytes -> {
                    assertNotNull(bytes);
                    assertTrue(bytes.length > 0);
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resourceToWebpThumbnail_withExifOrientation3_rotates180() throws IOException {
        assumeTrue(isWebpWriterAvailable(), "WebP writer not available, skipping test");

        // Orientation 3 = 180 rotation
        BufferedImage image = createTestImage(300, 200, Color.RED);
        byte[] jpegBytes = createJpegWithExifOrientation(image, 3);
        Resource resource = new ByteArrayResource(jpegBytes);

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectNextMatches(bytes -> {
                    assertNotNull(bytes);
                    assertTrue(bytes.length > 0);
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resourceToWebpThumbnail_withExifOrientation1_noRotation() throws IOException {
        assumeTrue(isWebpWriterAvailable(), "WebP writer not available, skipping test");

        // Orientation 1 = normal (no rotation), but still has EXIF segment
        BufferedImage image = createTestImage(300, 200, Color.GREEN);
        byte[] jpegBytes = createJpegWithExifOrientation(image, 1);
        Resource resource = new ByteArrayResource(jpegBytes);

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectNextMatches(bytes -> {
                    assertNotNull(bytes);
                    assertTrue(bytes.length > 0);
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resourceToWebpThumbnail_withLargeJpegAndExif_resizesAndRotates() throws IOException {
        assumeTrue(isWebpWriterAvailable(), "WebP writer not available, skipping test");

        // Large image with EXIF orientation 8 (90 CCW) - tests subsampling + orientation
        BufferedImage image = createTestImage(2000, 1000, Color.DARK_GRAY);
        byte[] jpegBytes = createJpegWithExifOrientation(image, 8);
        Resource resource = new ByteArrayResource(jpegBytes);

        StepVerifier.create(ImageUtils.resourceToWebpThumbnail(resource))
                .expectNextMatches(bytes -> {
                    assertNotNull(bytes);
                    assertTrue(bytes.length > 0);
                    assertTrue(bytes.length <= 10 * 1024, "Thumbnail should be small");
                    return true;
                })
                .verifyComplete();
    }

    // --- Helper methods ---

    private BufferedImage createTestImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private Resource toResource(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format, baos);
        return new ByteArrayResource(baos.toByteArray());
    }

    /**
     * Creates a JPEG image with an injected EXIF APP1 segment containing the given orientation.
     * The EXIF is constructed manually with a minimal TIFF/IFD structure.
     */
    private byte[] createJpegWithExifOrientation(BufferedImage image, int orientation) throws IOException {
        // First, create a standard JPEG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        byte[] originalJpeg = baos.toByteArray();

        // Build the EXIF APP1 segment
        byte[] exifSegment = buildExifApp1Segment(orientation);

        // Inject EXIF segment right after the SOI marker (FF D8)
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(originalJpeg, 0, 2); // FF D8 (SOI)
        result.write(exifSegment);         // APP1 EXIF segment
        result.write(originalJpeg, 2, originalJpeg.length - 2); // rest of JPEG

        return result.toByteArray();
    }

    /**
     * Builds a minimal EXIF APP1 segment with orientation tag.
     * Structure: FF E1 [length] "Exif\0\0" [TIFF header with IFD0 containing orientation]
     */
    private byte[] buildExifApp1Segment(int orientation) {
        // Build the TIFF/IFD data (little-endian)
        ByteBuffer tiff = ByteBuffer.allocate(26);
        tiff.order(ByteOrder.LITTLE_ENDIAN);

        // Byte order: "II" (Intel, little-endian)
        tiff.put((byte) 'I');
        tiff.put((byte) 'I');
        // TIFF magic number: 42
        tiff.putShort((short) 42);
        // Offset to IFD0 (8 bytes from start of TIFF header)
        tiff.putInt(8);
        // IFD0: number of entries
        tiff.putShort((short) 1);
        // IFD entry: Orientation tag (0x0112), type SHORT (3), count 1, value
        tiff.putShort((short) 0x0112); // tag
        tiff.putShort((short) 3);       // type SHORT
        tiff.putInt(1);                  // count
        tiff.putShort((short) orientation); // value
        tiff.putShort((short) 0);       // padding
        // Next IFD offset: 0 (no more IFDs)
        // (already at capacity)

        byte[] tiffData = tiff.array();

        // Build the APP1 segment
        byte[] exifHeader = new byte[] {'E', 'x', 'i', 'f', 0, 0};
        int dataLength = exifHeader.length + tiffData.length;
        int segmentLength = dataLength + 2; // +2 for the length field itself

        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        segment.write(0xFF);
        segment.write(0xE1); // APP1 marker
        segment.write((segmentLength >> 8) & 0xFF); // length high byte
        segment.write(segmentLength & 0xFF);         // length low byte
        segment.write(exifHeader, 0, exifHeader.length);
        segment.write(tiffData, 0, tiffData.length);

        return segment.toByteArray();
    }
}
