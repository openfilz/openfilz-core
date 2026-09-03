package org.openfilz.dms.service.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds small PDFs with a known text per page (and optional bookmarks) for the PDF tools tests, and
 * reads them back so assertions can check page order, count and rotation.
 */
public final class PdfTestFiles {

    private PdfTestFiles() {
    }

    /** One A4 page per text; every page shows exactly its text. */
    public static byte[] pdf(String... pageTexts) {
        return pdf(List.of(pageTexts), Map.of());
    }

    /** As {@link #pdf(String...)}, with top-level bookmarks {@code page -> title}. */
    public static byte[] pdf(List<String> pageTexts, Map<Integer, String> bookmarks) {
        try (PDDocument doc = new PDDocument()) {
            List<PDPage> pages = new ArrayList<>();
            for (String text : pageTexts) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                pages.add(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(60, 720);
                    cs.showText(text);
                    cs.endText();
                }
            }
            if (!bookmarks.isEmpty()) {
                PDDocumentOutline root = new PDDocumentOutline();
                doc.getDocumentCatalog().setDocumentOutline(root);
                bookmarks.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
                    PDOutlineItem item = new PDOutlineItem();
                    item.setTitle(e.getValue());
                    item.setDestination(pages.get(e.getKey() - 1));
                    root.addLast(item);
                });
            }
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A one-page PDF protected with the given user password (empty = opens without a password). */
    public static byte[] encryptedPdf(String userPassword) {
        try (PDDocument doc = Loader.loadPDF(pdf("secret page"))) {
            doc.protect(new StandardProtectionPolicy("owner-pw", userPassword, new AccessPermission()));
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static int pageCount(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The trimmed text of every page, in order. */
    public static List<String> pageTexts(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<String> texts = new ArrayList<>();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                // Rotated pages extract with line breaks between glyphs — compare without whitespace.
                texts.add(stripper.getText(doc).replaceAll("\\s+", ""));
            }
            return texts;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The /Rotate value of a page (1-based). */
    public static int rotation(byte[] pdf, int page) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getPage(page - 1).getRotation();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Top-level bookmark titles, in order. */
    public static List<String> bookmarkTitles(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<String> titles = new ArrayList<>();
            PDDocumentOutline root = doc.getDocumentCatalog().getDocumentOutline();
            if (root != null) {
                for (PDOutlineItem item : root.children()) {
                    titles.add(item.getTitle());
                }
            }
            return titles;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}
