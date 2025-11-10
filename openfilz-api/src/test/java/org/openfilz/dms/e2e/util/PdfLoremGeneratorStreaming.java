package org.openfilz.dms.e2e.util;

import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.WriterProperties;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class PdfLoremGeneratorStreaming {

    private static final String LOREM = """
        Lorem ipsum dolor sit amet, consectetur adipiscing elit.
        Sed non risus. Suspendisse lectus tortor, dignissim sit amet, adipiscing nec, ultricies sed, dolor.
        Cras elementum ultrices diam. Maecenas ligula massa, varius a, semper congue, euismod non, mi.
        Proin porttitor, orci nec nonummy molestie, enim est eleifend mi, non fermentum diam nisl sit amet erat.
        Duis semper. Duis arcu massa, scelerisque vitae, consequat in, pretium a, enim.
        Pellentesque congue. Ut in risus volutpat libero pharetra tempor.
        Cras vestibulum bibendum augue. Praesent egestas leo in pede.
        Praesent blandit odio eu enim. Pellentesque sed dui ut augue blandit sodales.
        Vestibulum ante ipsum primis in faucibus orci luctus et ultrices posuere cubilia Curae;
        Aliquam nibh. Mauris ac mauris sed pede pellentesque fermentum.
        Maecenas adipiscing ante non diam sodales hendrerit.
        """;

    private static final int QUEUE_CAPACITY = 64;

    public static void main(String[] args) throws Exception {
        generate("test fichier.pdf", 2L);
    }

    public static void generate(String outputFile, long targetSizeKB) throws Exception {

        long targetBytes = targetSizeKB * 1024L;

        long start = System.currentTimeMillis();
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        AtomicBoolean producing = new AtomicBoolean(true);

        Thread producer = new Thread(() -> produceLorem(queue, targetBytes, producing));
        Thread consumer = new Thread(() -> {
            try {
                consumeToPDF(outputFile, queue, producing);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();

        long duration = System.currentTimeMillis() - start;
        System.out.printf("✅ PDF '%s' généré (~%d MB) en %.2f secondes%n",
                outputFile, targetSizeKB, duration / 1000.0);
    }

    private static void produceLorem(BlockingQueue<String> queue, long targetBytes, AtomicBoolean producing) {
        Random random = new Random();
        String[] words = LOREM.split(" ");
        StringBuilder buffer = new StringBuilder(8192);

        long produced = 0;
        try {
            while (produced < targetBytes) {
                buffer.setLength(0);
                for (int i = 0; i < 2000; i++) {
                    buffer.append(words[random.nextInt(words.length)]).append(' ');
                }
                String chunk = buffer.toString();
                produced += chunk.getBytes().length;
                queue.put(chunk);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            producing.set(false);
        }
    }

    private static void consumeToPDF(String outputFile, BlockingQueue<String> queue, AtomicBoolean producing)
            throws IOException {
        try (PdfWriter writer = new PdfWriter(
                new FileOutputStream(outputFile),
                new WriterProperties().setCompressionLevel(0));
             PdfDocument pdf = new PdfDocument(writer)) {

            PdfPage page = pdf.addNewPage();
            PdfCanvas canvas = new PdfCanvas(page);
            canvas.beginText();
            canvas.setFontAndSize(PdfFontFactory.createFont(), 10);

            float y = 800;
            while (producing.get() || !queue.isEmpty()) {
                String chunk = queue.poll();
                if (chunk == null) {
                    Thread.sleep(5);
                    continue;
                }

                String[] lines = chunk.split("(?<=\\G.{400})"); // découpe le texte en lignes de 400 caractères
                for (String line : lines) {
                    if (y < 50) {
                        canvas.endText();
                        page = pdf.addNewPage();
                        canvas = new PdfCanvas(page);
                        canvas.beginText();
                        canvas.setFontAndSize(PdfFontFactory.createFont(), 10);
                        y = 800;
                    }
                    canvas.moveText(50, y);
                    canvas.showText(line);
                    y -= 12;
                }
            }

            canvas.endText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
