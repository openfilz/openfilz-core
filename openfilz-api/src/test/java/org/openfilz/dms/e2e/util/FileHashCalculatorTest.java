package org.openfilz.dms.e2e.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.utils.FileHashCalculator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Disabled
public class FileHashCalculatorTest {


    @Test
    void shouldReturnSameHash() throws IOException, NoSuchAlgorithmException {
        Path file10GB = Path.of(System.getProperty("java.io.tmpdir"), "10Go-" + UUID.randomUUID() + ".txt");
        Path file100Mo = Path.of(System.getProperty("java.io.tmpdir"), "100Mo-" + UUID.randomUUID() + ".txt");
        Path file25Mo = Path.of(System.getProperty("java.io.tmpdir"), "25Mo-" + UUID.randomUUID() + ".txt");
        Path file500Ko = Path.of(System.getProperty("java.io.tmpdir"), "500Ko-" + UUID.randomUUID() + ".txt");

        EfficientFileGenerator.generateFilePreallocated(file10GB, 10L* 1024 * 1024 * 1024, EfficientFileGenerator.ContentType.LOREM_IPSUM);
        EfficientFileGenerator.generateFilePreallocated(file100Mo, 100 * 1024 * 1024, EfficientFileGenerator.ContentType.LOREM_IPSUM);
        EfficientFileGenerator.generateFilePreallocated(file25Mo, 25 * 1024 * 1024, EfficientFileGenerator.ContentType.LOREM_IPSUM);
        EfficientFileGenerator.generateFilePreallocated(file500Ko, 500 * 1024, EfficientFileGenerator.ContentType.LOREM_IPSUM);

        Instant t0 = Instant.now();
        String hash1 = FileHashCalculator.calculateHashMapped(file10GB, "SHA-256");
        Instant t1 = Instant.now();
        String hash2 = FileHashCalculator.calculateHashMapped(file100Mo, "SHA-256");
        Instant t2 = Instant.now();
        String hash3 = FileHashCalculator.calculateHashMapped(file25Mo, "SHA-256");
        Instant t3 = Instant.now();
        String hash4 = FileHashCalculator.calculateHashMapped(file500Ko, "SHA-256");
        Instant t4 = Instant.now();
        System.out.println("------calculateHashMapped-----");
        System.out.println("10Go : " + Duration.between(t0, t1).toMillis());
        System.out.println("100Mo : " +  Duration.between(t1, t2).toMillis());
        System.out.println("25Mo : " + Duration.between(t2, t3).toMillis());
        System.out.println("500Ko : " + Duration.between(t3, t4).toMillis());

        t0 = Instant.now();
        String hash11 = FileHashCalculator.calculateHashDirect(file10GB, "SHA-256");
        t1 = Instant.now();
        String hash21 = FileHashCalculator.calculateHashDirect(file100Mo, "SHA-256");
        t2 = Instant.now();
        String hash31 = FileHashCalculator.calculateHashDirect(file25Mo, "SHA-256");
        t3 = Instant.now();
        String hash41 = FileHashCalculator.calculateHashDirect(file500Ko, "SHA-256");
        t4 = Instant.now();
        System.out.println("------calculateHashDirect-----");
        System.out.println("10Go : " + Duration.between(t0, t1).toMillis());
        System.out.println("100Mo : " +  Duration.between(t1, t2).toMillis());
        System.out.println("25Mo : " + Duration.between(t2, t3).toMillis());
        System.out.println("500Ko : " + Duration.between(t3, t4).toMillis());

        t0 = Instant.now();
        String hash12 = FileHashCalculator.calculateHashStream(file10GB, "SHA-256");
        t1 = Instant.now();
        String hash22 = FileHashCalculator.calculateHashStream(file100Mo, "SHA-256");
        t2 = Instant.now();
        String hash32 = FileHashCalculator.calculateHashStream(file25Mo, "SHA-256");
        t3 = Instant.now();
        String hash42 = FileHashCalculator.calculateHashStream(file500Ko, "SHA-256");
        t4 = Instant.now();
        System.out.println("------calculateHashStream-----");
        System.out.println("10Go : " + Duration.between(t0, t1).toMillis());
        System.out.println("100Mo : " +  Duration.between(t1, t2).toMillis());
        System.out.println("25Mo : " + Duration.between(t2, t3).toMillis());
        System.out.println("500Ko : " + Duration.between(t3, t4).toMillis());

        Assertions.assertEquals(hash1, hash11);
        Assertions.assertEquals(hash1, hash12);
        Assertions.assertEquals(hash2, hash21);
        Assertions.assertEquals(hash2, hash22);
        Assertions.assertEquals(hash3, hash31);
        Assertions.assertEquals(hash3, hash32);
        Assertions.assertEquals(hash4, hash41);
        Assertions.assertEquals(hash4, hash42);


        t0 = Instant.now();
        hash1 = FileHashCalculator.calculateHashMapped(file10GB, "SHA-256");
        t1 = Instant.now();
        hash2 = FileHashCalculator.calculateHashMapped(file100Mo, "SHA-256");
        t2 = Instant.now();
        hash3 = FileHashCalculator.calculateHashMapped(file25Mo, "SHA-256");
        t3 = Instant.now();
        hash4 = FileHashCalculator.calculateHashMapped(file500Ko, "SHA-256");
        t4 = Instant.now();
        System.out.println("------calculateHashMapped-----");
        System.out.println("10Go : " + Duration.between(t0, t1).toMillis());
        System.out.println("100Mo : " +  Duration.between(t1, t2).toMillis());
        System.out.println("25Mo : " + Duration.between(t2, t3).toMillis());
        System.out.println("500Ko : " + Duration.between(t3, t4).toMillis());

        t0 = Instant.now();
        hash11 = FileHashCalculator.calculateHashDirect(file10GB, "SHA-256");
        t1 = Instant.now();
        hash21 = FileHashCalculator.calculateHashDirect(file100Mo, "SHA-256");
        t2 = Instant.now();
        hash31 = FileHashCalculator.calculateHashDirect(file25Mo, "SHA-256");
        t3 = Instant.now();
        hash41 = FileHashCalculator.calculateHashDirect(file500Ko, "SHA-256");
        t4 = Instant.now();
        System.out.println("------calculateHashDirect-----");
        System.out.println("10Go : " + Duration.between(t0, t1).toMillis());
        System.out.println("100Mo : " +  Duration.between(t1, t2).toMillis());
        System.out.println("25Mo : " + Duration.between(t2, t3).toMillis());
        System.out.println("500Ko : " + Duration.between(t3, t4).toMillis());

        t0 = Instant.now();
        hash12 = FileHashCalculator.calculateHashStream(file10GB, "SHA-256");
        t1 = Instant.now();
        hash22 = FileHashCalculator.calculateHashStream(file100Mo, "SHA-256");
        t2 = Instant.now();
        hash32 = FileHashCalculator.calculateHashStream(file25Mo, "SHA-256");
        t3 = Instant.now();
        hash42 = FileHashCalculator.calculateHashStream(file500Ko, "SHA-256");
        t4 = Instant.now();
        System.out.println("------calculateHashStream-----");
        System.out.println("10Go : " + Duration.between(t0, t1).toMillis());
        System.out.println("100Mo : " +  Duration.between(t1, t2).toMillis());
        System.out.println("25Mo : " + Duration.between(t2, t3).toMillis());
        System.out.println("500Ko : " + Duration.between(t3, t4).toMillis());

        Files.deleteIfExists(file10GB);
        Files.deleteIfExists(file100Mo);
        Files.deleteIfExists(file25Mo);
        Files.deleteIfExists(file500Ko);
    }

}
