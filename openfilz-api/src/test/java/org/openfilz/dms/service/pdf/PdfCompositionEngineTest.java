package org.openfilz.dms.service.pdf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfilz.dms.dto.response.pdf.PdfOutlineEntry;
import org.openfilz.dms.service.pdf.PdfCompositionEngine.Inspection;
import org.openfilz.dms.service.pdf.PdfCompositionEngine.OutlineSpec;
import org.openfilz.dms.service.pdf.PdfCompositionEngine.PageRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfCompositionEngineTest {

    @TempDir
    Path dir;

    private final PdfCompositionEngine engine = new PdfCompositionEngine();
    private Path a;
    private Path b;

    @BeforeEach
    void files() throws IOException {
        a = write("a.pdf", PdfTestFiles.pdf(List.of("A1", "A2", "A3"), Map.of(1, "Intro", 3, "Annex")));
        b = write("b.pdf", PdfTestFiles.pdf("B1", "B2"));
    }

    @Test
    void inspectReportsPagesAndOutline() throws IOException {
        Inspection info = engine.inspect(a);
        assertThat(info.pageCount()).isEqualTo(3);
        assertThat(info.encrypted()).isFalse();
        assertThat(info.signed()).isFalse();
        assertThat(info.pages()).hasSize(3);
        assertThat(info.pages().getFirst().number()).isEqualTo(1);
        assertThat(info.pages().getFirst().width()).isEqualTo(595.28);
        assertThat(info.pages().getFirst().height()).isEqualTo(841.89);
        assertThat(info.pages().getFirst().rotation()).isZero();
        assertThat(info.outline()).extracting(PdfOutlineEntry::title).containsExactly("Intro", "Annex");
        assertThat(info.outline()).extracting(PdfOutlineEntry::page).containsExactly(1, 3);
        assertThat(info.outline()).extracting(PdfOutlineEntry::level).containsOnly(1);
    }

    @Test
    void inspectFlagsPasswordProtectedFiles() throws IOException {
        Path locked = write("locked.pdf", PdfTestFiles.encryptedPdf("user-pw"));
        Inspection info = engine.inspect(locked);
        assertThat(info.encrypted()).isTrue();
        assertThat(info.pageCount()).isZero();

        Path openable = write("empty-pw.pdf", PdfTestFiles.encryptedPdf(""));
        Inspection info2 = engine.inspect(openable);
        assertThat(info2.encrypted()).isTrue();
        assertThat(info2.pageCount()).isEqualTo(1);
    }

    @Test
    void inspectRejectsNonPdf() throws IOException {
        Path text = write("x.pdf", "not a pdf".getBytes());
        assertThatThrownBy(() -> engine.inspect(text)).isInstanceOf(IOException.class);
    }

    @Test
    void mergeKeepsOrderAndCreatesBookmarks() throws IOException {
        Path out = dir.resolve("merged.pdf");
        int pages = engine.compose(List.of(
                        new PageRef(a, 1, 0), new PageRef(a, 2, 0), new PageRef(a, 3, 0),
                        new PageRef(b, 1, 0), new PageRef(b, 2, 0)),
                List.of(new OutlineSpec("a", 1), new OutlineSpec("b", 4)), "merged", out);
        assertThat(pages).isEqualTo(5);
        byte[] bytes = Files.readAllBytes(out);
        assertThat(PdfTestFiles.pageTexts(bytes)).containsExactly("A1", "A2", "A3", "B1", "B2");
        assertThat(PdfTestFiles.bookmarkTitles(bytes)).containsExactly("a", "b");
        Inspection info = engine.inspect(out);
        assertThat(info.outline()).extracting(PdfOutlineEntry::page).containsExactly(1, 4);
    }

    @Test
    void reorderDuplicateDeleteAndRotate() throws IOException {
        Path out = dir.resolve("organized.pdf");
        engine.compose(List.of(new PageRef(a, 3, 0), new PageRef(a, 1, 90), new PageRef(a, 1, -90)), null, null, out);
        byte[] bytes = Files.readAllBytes(out);
        assertThat(PdfTestFiles.pageTexts(bytes)).containsExactly("A3", "A1", "A1");
        assertThat(PdfTestFiles.rotation(bytes, 1)).isZero();
        assertThat(PdfTestFiles.rotation(bytes, 2)).isEqualTo(90);
        assertThat(PdfTestFiles.rotation(bytes, 3)).isEqualTo(270);
        // sources are untouched
        assertThat(PdfTestFiles.rotation(Files.readAllBytes(a), 1)).isZero();
    }

    @Test
    void rotationAccumulatesOnAlreadyRotatedPages() throws IOException {
        Path once = dir.resolve("once.pdf");
        engine.compose(List.of(new PageRef(a, 1, 90)), null, null, once);
        Path twice = dir.resolve("twice.pdf");
        engine.compose(List.of(new PageRef(once, 1, 90)), null, null, twice);
        assertThat(PdfTestFiles.rotation(Files.readAllBytes(twice), 1)).isEqualTo(180);
        Path back = dir.resolve("back.pdf");
        engine.compose(List.of(new PageRef(twice, 1, 180)), null, null, back);
        assertThat(PdfTestFiles.rotation(Files.readAllBytes(back), 1)).isZero();
    }

    @Test
    void outOfRangePageIsRejected() {
        assertThatThrownBy(() -> engine.compose(List.of(new PageRef(a, 4, 0)), null, null, dir.resolve("x.pdf")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> engine.compose(List.of(), null, null, dir.resolve("y.pdf")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outlineSpecsOutsideTheOutputAreIgnored() throws IOException {
        Path out = dir.resolve("o.pdf");
        engine.compose(List.of(new PageRef(b, 1, 0)), List.of(new OutlineSpec("ok", 1), new OutlineSpec("skip", 5)), null, out);
        assertThat(PdfTestFiles.bookmarkTitles(Files.readAllBytes(out))).containsExactly("ok");
    }

    @Test
    void rotationHelpers() {
        assertThat(PdfCompositionEngine.normalizeRotation(450)).isEqualTo(90);
        assertThat(PdfCompositionEngine.normalizeRotation(-90)).isEqualTo(270);
        assertThat(PdfCompositionEngine.normalizeRotation(360)).isZero();
        assertThat(PdfCompositionEngine.isRightAngle(180)).isTrue();
        assertThat(PdfCompositionEngine.isRightAngle(45)).isFalse();
    }

    private Path write(String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }
}
