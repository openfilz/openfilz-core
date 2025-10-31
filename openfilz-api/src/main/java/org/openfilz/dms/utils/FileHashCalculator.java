package org.openfilz.dms.utils;

import lombok.experimental.UtilityClass;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@UtilityClass
public class FileHashCalculator {
    
    // Optimal chunk size for memory mapping (128 MB)
    private static final int CHUNK_SIZE = 1 * 1024 * 1024;
    
    /**
     * Most performant approach for large files using memory-mapped buffers
     */
    public static String calculateHashMapped(Path file, String algorithm) 
            throws IOException, NoSuchAlgorithmException {
        
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            long position = 0;
            
            while (position < fileSize) {
                long remaining = fileSize - position;
                long chunkSize = Math.min(remaining, CHUNK_SIZE);
                
                MappedByteBuffer buffer = channel.map(
                    FileChannel.MapMode.READ_ONLY, 
                    position, 
                    chunkSize
                );
                
                digest.update(buffer);
                position += chunkSize;
            }
        }
        
        return bytesToHex(digest.digest());
    }
    
    /**
     * Direct buffer approach - good for medium files
     */
    public static String calculateHashDirect(Path file, String algorithm) 
            throws IOException, NoSuchAlgorithmException {
        
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var buffer = ByteBuffer.allocateDirect(8192);
            
            while (channel.read(buffer) != -1) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        
        return bytesToHex(digest.digest());
    }
    
    /**
     * Traditional buffered stream - simplest approach
     */
    public static String calculateHashStream(Path file, String algorithm) 
            throws IOException, NoSuchAlgorithmException {
        
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        
        try (var in = new BufferedInputStream(
                Files.newInputStream(file), 8192)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        
        return bytesToHex(digest.digest());
    }
    
    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
    

}