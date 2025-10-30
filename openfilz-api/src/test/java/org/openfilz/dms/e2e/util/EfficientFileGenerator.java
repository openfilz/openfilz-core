package org.openfilz.dms.e2e.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.random.RandomGenerator;

public class EfficientFileGenerator {
    
    private static final int BUFFER_SIZE = 8 * 1024 * 1024; // 8MB buffer
    
    /**
     * Fastest method: Pre-allocate file and write in large chunks
     * Uses direct ByteBuffer for zero-copy operations
     */
    public static void generateFilePreallocated(Path file, long sizeBytes, ContentType type) 
            throws IOException {
        
        try (FileChannel channel = FileChannel.open(file, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            
            // Pre-allocate file space (improves performance)
            channel.truncate(0);
            
            ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            byte[] content = generateContent(BUFFER_SIZE, type);
            
            long remaining = sizeBytes;
            
            while (remaining > 0) {
                int chunkSize = (int) Math.min(remaining, BUFFER_SIZE);
                
                buffer.clear();
                buffer.put(content, 0, chunkSize);
                buffer.flip();
                
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                
                remaining -= chunkSize;
            }
            
            // Force write to disk
            channel.force(true);
        }
    }
    
    /**
     * Memory-mapped approach: Fastest for huge files
     * Leverages OS-level optimizations
     */
    public static void generateFileMapped(Path file, long sizeBytes, ContentType type) 
            throws IOException {
        
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            
            byte[] content = generateContent(BUFFER_SIZE, type);
            long remaining = sizeBytes;
            long position = 0;
            
            while (remaining > 0) {
                long chunkSize = Math.min(remaining, BUFFER_SIZE);
                
                var buffer = channel.map(
                    FileChannel.MapMode.READ_WRITE,
                    position,
                    chunkSize
                );
                
                int written = 0;
                while (written < chunkSize) {
                    int toWrite = (int) Math.min(content.length, chunkSize - written);
                    buffer.put(content, 0, toWrite);
                    written += toWrite;
                }
                
                position += chunkSize;
                remaining -= chunkSize;
            }
        }
    }
    
    /**
     * Sparse file creation: Instant for supported filesystems
     * Creates file of specified size without writing data
     */
    public static void generateSparseFile(Path file, long sizeBytes) 
            throws IOException {
        
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.SPARSE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            
            // Set file size without writing data
            channel.position(sizeBytes - 1);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
    }
    
    /**
     * Generate content based on type
     */
    private static byte[] generateContent(int size, ContentType type) {
        return switch (type) {
            case ZEROS -> new byte[size]; // All zeros
            case REPEATED_TEXT -> generateRepeatedText(size);
            case RANDOM -> generateRandom(size);
            case LOREM_IPSUM -> generateLoremIpsum(size);
        };
    }
    
    private static byte[] generateRepeatedText(int size) {
        String pattern = "The quick brown fox jumps over the lazy dog. 0123456789\n";
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[size];
        
        for (int i = 0; i < size; i++) {
            result[i] = patternBytes[i % patternBytes.length];
        }
        return result;
    }
    
    private static byte[] generateRandom(int size) {
        RandomGenerator rng = RandomGenerator.getDefault();
        byte[] result = new byte[size];
        rng.nextBytes(result);
        
        // Make it valid ASCII text
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (32 + (result[i] & 0x7F) % 95); // Printable ASCII
        }
        return result;
    }
    
    private static byte[] generateLoremIpsum(int size) {
        String lorem = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
                      "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. " +
                      "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris.\n";
        byte[] loremBytes = lorem.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[size];
        
        for (int i = 0; i < size; i++) {
            result[i] = loremBytes[i % loremBytes.length];
        }
        return result;
    }
    
    public enum ContentType {
        ZEROS,          // All zeros (fastest)
        REPEATED_TEXT,  // Repeating pattern (fast)
        LOREM_IPSUM,    // Lorem ipsum text (readable)
        RANDOM          // Random content (slowest but more realistic)
    }
    
    // Helper method to parse size strings like "100MB", "5GB"
    public static long parseSize(String sizeStr) {
        sizeStr = sizeStr.toUpperCase().trim();
        
        long multiplier = 1;
        if (sizeStr.endsWith("KB")) {
            multiplier = 1024L;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2);
        } else if (sizeStr.endsWith("MB")) {
            multiplier = 1024L * 1024;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2);
        } else if (sizeStr.endsWith("GB")) {
            multiplier = 1024L * 1024 * 1024;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2);
        }
        
        return Long.parseLong(sizeStr.trim()) * multiplier;
    }
    
    // Usage examples
    public static void main(String[] args) {
        try {
            // Example 1: Generate 100MB file with repeated text (fastest with content)
            long size = parseSize("100MB");
            Path file = Path.of("testfile_100mb.txt");
            
            long start = System.currentTimeMillis();
            generateFilePreallocated(file, size, ContentType.REPEATED_TEXT);
            long elapsed = System.currentTimeMillis() - start;
            
            System.out.printf("Generated %s in %d ms (%.2f MB/s)%n",
                formatSize(size), elapsed, size / 1024.0 / 1024.0 / (elapsed / 1000.0));
            
            // Example 2: Generate 1GB sparse file (instant)
            Path sparseFile = Path.of("sparse_1gb.txt");
            start = System.currentTimeMillis();
            generateSparseFile(sparseFile, parseSize("1GB"));
            elapsed = System.currentTimeMillis() - start;
            System.out.printf("Generated sparse file in %d ms%n", elapsed);
            
            // Example 3: Generate with different content types
            generateFilePreallocated(Path.of("zeros.txt"), parseSize("10MB"), 
                ContentType.ZEROS);
            generateFileMapped(Path.of("lorem.txt"), parseSize("50MB"), 
                ContentType.LOREM_IPSUM);
            
            System.out.println("All files generated successfully!");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        } else if (bytes >= 1024L * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else if (bytes >= 1024L) {
            return String.format("%.2f KB", bytes / 1024.0);
        }
        return bytes + " bytes";
    }
}