package org.openfilz.dms.service.pdf;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.openfilz.dms.dto.response.pdf.PdfOutlineEntry;
import org.openfilz.dms.dto.response.pdf.PdfPageInfo;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single PDFBox-backed primitive behind every PDF tool: a <b>page composition</b>. The output
 * is exactly the listed pages, in order, each taken from a source file and optionally rotated.
 * Merge, reorder, delete, duplicate, rotate, extract and insert are one composition; split is
 * several.
 * <p>
 * Pure and blocking: callers run it on {@code boundedElastic}. Sources and the output are files, and
 * PDFBox is configured with a temp-file stream cache so a large merge never has to fit in the heap.
 * {@code importPage} keeps each page's resources, annotations and content streams as they are; form
 * fields (AcroForm) are not carried over as fields — their widgets stay visible as annotations.
 */
@Slf4j
@Component
public class PdfCompositionEngine {

    /** Deeper bookmarks are not reported (they are never split points anyway). */
    static final int MAX_OUTLINE_DEPTH = 4;
    /** Safety cap on the outline entries reported for a document. */
    static final int MAX_OUTLINE_ENTRIES = 2000;

    /**
     * One output page.
     *
     * @param source   the source PDF file
     * @param page     1-based page number in the source
     * @param rotation extra clockwise rotation in degrees (multiple of 90)
     */
    public record PageRef(Path source, int page, int rotation) {
    }

    /**
     * A bookmark to create in the output.
     *
     * @param title     bookmark title
     * @param firstPage 1-based page in the <em>output</em> it points to
     */
    public record OutlineSpec(String title, int firstPage) {
    }

    /** What {@link #inspect(Path)} learns about a PDF. */
    public record Inspection(int pageCount, List<PdfPageInfo> pages, boolean encrypted, boolean signed,
                             List<PdfOutlineEntry> outline) {
    }

    /**
     * Reads the structure of a PDF. A password-protected file yields {@code encrypted=true} with no
     * page information rather than an error, so the caller can report it.
     *
     * @throws IOException when the file is not a readable PDF
     */
    public Inspection inspect(Path pdf) throws IOException {
        try (PDDocument doc = open(pdf)) {
            List<PdfPageInfo> pages = new ArrayList<>(doc.getNumberOfPages());
            int number = 1;
            for (PDPage page : doc.getPages()) {
                PDRectangle box = page.getMediaBox();
                pages.add(new PdfPageInfo(number++, round(box.getWidth()), round(box.getHeight()), page.getRotation()));
            }
            boolean signed = !doc.getSignatureDictionaries().isEmpty();
            return new Inspection(doc.getNumberOfPages(), pages, doc.isEncrypted(), signed, readOutline(doc));
        } catch (InvalidPasswordException e) {
            return new Inspection(0, List.of(), true, false, List.of());
        }
    }

    /**
     * Writes a new PDF made of {@code pages}, in order, to {@code target}.
     *
     * @param pages   the output pages (at least one)
     * @param outline bookmarks to create, or null/empty for none
     * @param title   document title to stamp in the info dictionary, or null
     * @param target  output file (overwritten)
     * @return the number of pages written
     * @throws IOException              on a PDFBox failure
     * @throws IllegalArgumentException when a page number is out of range for its source
     */
    public int compose(List<PageRef> pages, List<OutlineSpec> outline, String title, Path target) throws IOException {
        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("A PDF needs at least one page");
        }
        Map<Path, PDDocument> sources = new LinkedHashMap<>();
        try (PDDocument out = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            List<PDPage> outPages = new ArrayList<>(pages.size());
            for (PageRef ref : pages) {
                PDDocument src = sources.get(ref.source());
                if (src == null) {
                    src = open(ref.source());
                    sources.put(ref.source(), src);
                }
                int count = src.getNumberOfPages();
                if (ref.page() < 1 || ref.page() > count) {
                    throw new IllegalArgumentException("Page " + ref.page() + " is out of range (the document has "
                            + count + " page" + (count > 1 ? "s" : "") + ")");
                }
                PDPage srcPage = src.getPage(ref.page() - 1);
                PDPage imported = out.importPage(srcPage);
                imported.setRotation(normalizeRotation(srcPage.getRotation() + ref.rotation()));
                outPages.add(imported);
            }
            if (outline != null && !outline.isEmpty()) {
                PDDocumentOutline root = new PDDocumentOutline();
                out.getDocumentCatalog().setDocumentOutline(root);
                for (OutlineSpec spec : outline) {
                    if (spec.firstPage() < 1 || spec.firstPage() > outPages.size()) {
                        continue;
                    }
                    PDOutlineItem item = new PDOutlineItem();
                    item.setTitle(spec.title());
                    item.setDestination(outPages.get(spec.firstPage() - 1));
                    root.addLast(item);
                }
                root.openNode();
            }
            PDDocumentInformation info = out.getDocumentInformation();
            info.setProducer("OpenFilz");
            if (title != null && !title.isBlank()) {
                info.setTitle(title);
            }
            Calendar now = Calendar.getInstance();
            info.setCreationDate(now);
            info.setModificationDate(now);
            out.save(target.toFile());
            return outPages.size();
        } finally {
            // Sources must stay open until the output is saved (imported pages share their streams).
            for (PDDocument src : sources.values()) {
                try {
                    src.close();
                } catch (IOException e) {
                    log.debug("Could not close source PDF: {}", e.getMessage());
                }
            }
        }
    }

    /** Clockwise degrees folded into 0, 90, 180 or 270. */
    public static int normalizeRotation(int degrees) {
        int r = degrees % 360;
        if (r < 0) {
            r += 360;
        }
        return r;
    }

    /** True when {@code degrees} is a multiple of 90. */
    public static boolean isRightAngle(int degrees) {
        return degrees % 90 == 0;
    }

    private static PDDocument open(Path pdf) throws IOException {
        return Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache());
    }

    private static double round(float value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private List<PdfOutlineEntry> readOutline(PDDocument doc) {
        List<PdfOutlineEntry> entries = new ArrayList<>();
        try {
            PDDocumentOutline root = doc.getDocumentCatalog().getDocumentOutline();
            if (root != null) {
                walkOutline(doc, root, 1, entries);
            }
        } catch (RuntimeException e) {
            // A damaged outline must not make the document unusable for page operations.
            log.debug("Ignoring unreadable PDF outline: {}", e.getMessage());
        }
        return entries;
    }

    private void walkOutline(PDDocument doc, PDOutlineNode node, int level, List<PdfOutlineEntry> entries) {
        for (PDOutlineItem item : node.children()) {
            if (entries.size() >= MAX_OUTLINE_ENTRIES) {
                return;
            }
            Integer page = null;
            try {
                PDPage target = item.findDestinationPage(doc);
                if (target != null) {
                    int index = doc.getPages().indexOf(target);
                    if (index >= 0) {
                        page = index + 1;
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.debug("Unresolvable bookmark destination '{}': {}", item.getTitle(), e.getMessage());
            }
            entries.add(new PdfOutlineEntry(item.getTitle(), page, level));
            if (level < MAX_OUTLINE_DEPTH) {
                walkOutline(doc, item, level + 1, entries);
            }
        }
    }
}
