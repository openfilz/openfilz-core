package org.openfilz.dms.utils;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based coverage for {@link ImageUtils} private EXIF/transform helpers — the
 * malformed-JPEG and orientation branches the happy-path image integration tests skip.
 */
class ImageUtilsExtraTest {

    @SuppressWarnings("SameParameterValue")
    private static Object invokeStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = ImageUtils.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static int readExif(byte[] data) throws Exception {
        return (int) invokeStatic("readExifOrientationFromBytes", new Class<?>[]{byte[].class}, (Object) data);
    }

    @Test
    void readExif_nonJpeg_returnsDefaultOrientation() throws Exception {
        assertEquals(1, readExif(new byte[]{0x00, 0x01, 0x02}));
    }

    @Test
    void readExif_jpegWithNonMarkerByte_breaksAndReturnsDefault() throws Exception {
        // FFD8 then a non-0xFF byte -> marker1 != 0xFF break
        assertEquals(1, readExif(new byte[]{(byte) 0xFF, (byte) 0xD8, 0x00}));
    }

    @Test
    void readExif_jpegTruncatedAfterMarker_returnsDefault() throws Exception {
        // FFD8 FF then EOF -> marker2 == -1 break
        assertEquals(1, readExif(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));
    }

    @Test
    void readExif_jpegWithPaddingThenTruncatedLength_returnsDefault() throws Exception {
        // FFD8 FF FF E0 then EOF on length -> padding loop + length == -1 break
        assertEquals(1, readExif(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xFF, (byte) 0xE0}));
    }

    @Test
    void readExif_app1WithBogusLength_handlesExceptionAndReturnsDefault() throws Exception {
        // FFD8 FF E1 (APP1) length=0x00FF but no data -> readFully throws -> caught -> default
        assertEquals(1, readExif(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x00, (byte) 0xFF}));
    }

    @Test
    void readExif_nonApp1Segment_isSkipped() throws Exception {
        // FFD8 FF E0 (APP0) length=4, 2 bytes payload, then FF DA (SOS) -> skip then break
        assertEquals(1, readExif(new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE0, 0x00, 0x04, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xDA, 0x00, 0x04, 0x00, 0x00}));
    }

    @Test
    void findOrientationInDirectory_null_returnsNull() throws Exception {
        Class<?> dirType = Class.forName("com.twelvemonkeys.imageio.metadata.Directory");
        Object result = invokeStatic("findOrientationInDirectory", new Class<?>[]{dirType}, (Object) null);
        assertNull(result);
    }

    @Test
    void getExifTransform_unknownOrientation_returnsIdentityAndLogs() throws Exception {
        Object tx = invokeStatic("getExifTransform", new Class<?>[]{int.class, int.class, int.class}, 99, 100, 100);
        assertNotNull(tx);
    }

    @Test
    void getExifTransform_allKnownOrientations_buildTransforms() throws Exception {
        for (int o = 2; o <= 8; o++) {
            Object tx = invokeStatic("getExifTransform", new Class<?>[]{int.class, int.class, int.class}, o, 80, 120);
            assertNotNull(tx);
        }
    }

    @Test
    void applyExifOrientation_identity_returnsSameImage() throws Exception {
        BufferedImage src = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Object out = invokeStatic("applyExifOrientation", new Class<?>[]{BufferedImage.class, int.class}, src, 1);
        assertSame(src, out);
    }

    @Test
    void applyExifOrientation_rotated_swapsDimensions() throws Exception {
        BufferedImage src = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        BufferedImage out = (BufferedImage) invokeStatic("applyExifOrientation",
                new Class<?>[]{BufferedImage.class, int.class}, src, 6);
        // orientation 6 swaps width/height
        assertEquals(20, out.getWidth());
        assertEquals(40, out.getHeight());
    }

    @Test
    void convertToRgb_alreadyRgb_returnsSame() throws Exception {
        BufferedImage src = new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB);
        Object out = invokeStatic("convertToRgb", new Class<?>[]{BufferedImage.class}, src);
        assertSame(src, out);
    }

    @Test
    void convertToRgb_argb_convertsToRgb() throws Exception {
        BufferedImage src = new BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB);
        BufferedImage out = (BufferedImage) invokeStatic("convertToRgb", new Class<?>[]{BufferedImage.class}, src);
        assertEquals(BufferedImage.TYPE_INT_RGB, out.getType());
    }
}
