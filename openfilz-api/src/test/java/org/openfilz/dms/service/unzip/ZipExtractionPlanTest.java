package org.openfilz.dms.service.unzip;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.UnzipProperties;
import org.openfilz.dms.dto.response.UnzipResponse.SkipReason;
import org.openfilz.dms.dto.response.UnzipResponse.UnzipSkippedEntry;
import org.openfilz.dms.exception.UnzipException;

import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipExtractionPlanTest {

    private static final Predicate<ZipArchiveEntry> READABLE = _ -> true;

    private static ZipArchiveEntry entry(String name, long size, long compressed) {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        if (!entry.isDirectory()) {
            entry.setSize(size);
            entry.setCompressedSize(compressed);
        }
        return entry;
    }

    private static ZipArchiveEntry file(String name) {
        return entry(name, 10, 5);
    }

    private static ZipExtractionPlan plan(UnzipProperties props, ZipArchiveEntry... entries) {
        return ZipExtractionPlan.of(List.of(entries), READABLE, props);
    }

    private static ZipExtractionPlan plan(ZipArchiveEntry... entries) {
        return plan(new UnzipProperties(), entries);
    }

    @Test
    void addsImplicitFoldersOrderedByDepth() {
        ZipExtractionPlan plan = plan(file("a/b/c/deep.txt"), file("top.txt"), entry("x/", 0, 0));

        assertThat(plan.foldersByDepth()).containsExactly(
                List.of("a", "x"), List.of("a/b"), List.of("a/b/c"));
        assertThat(plan.files()).extracting(ZipExtractionPlan.FileEntry::path, ZipExtractionPlan.FileEntry::parentPath)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a/b/c/deep.txt", "a/b/c"),
                        org.assertj.core.groups.Tuple.tuple("top.txt", ""));
        assertThat(plan.totalFileBytes()).isEqualTo(20);
    }

    @Test
    void normalisesSeparatorsAndDotSegments() {
        assertThat(ZipExtractionPlan.segments("./a\\b//c.txt")).containsExactly("a", "b", "c.txt");
        assertThat(ZipExtractionPlan.segments("/abs/x.txt")).containsExactly("abs", "x.txt");
        assertThat(ZipExtractionPlan.segments("a/#b.txt")).containsExactly("a", "b.txt");
    }

    @Test
    void refusesPathsThatLeaveTheDestination() {
        assertThat(ZipExtractionPlan.segments("../x.txt")).isNull();
        assertThat(ZipExtractionPlan.segments("a/../../x.txt")).isNull();
        assertThat(ZipExtractionPlan.segments("C:/x.txt")).isNull();
        assertThat(ZipExtractionPlan.segments("a/b\u0000c.txt")).isNull();
        assertThat(ZipExtractionPlan.segments("a/" + "n".repeat(256))).isNull();

        ZipExtractionPlan plan = plan(file("../evil.txt"), file("good.txt"));
        assertThat(plan.files()).extracting(ZipExtractionPlan.FileEntry::path).containsExactly("good.txt");
        assertThat(plan.skipped()).extracting(UnzipSkippedEntry::reason).containsExactly(SkipReason.UNSAFE_PATH);
    }

    @Test
    void ignoresOperatingSystemJunk() {
        ZipExtractionPlan plan = plan(file("__MACOSX/a/._x.txt"), file("a/.DS_Store"), file("Thumbs.db"), file("a/x.txt"));

        assertThat(plan.files()).extracting(ZipExtractionPlan.FileEntry::path).containsExactly("a/x.txt");
        assertThat(plan.foldersByDepth()).containsExactly(List.of("a"));
        assertThat(plan.skipped()).isEmpty();
    }

    @Test
    void skipsUnreadableAndDuplicateEntries() {
        ZipArchiveEntry locked = file("locked.txt");
        ZipExtractionPlan plan = ZipExtractionPlan.of(
                List.of(locked, file("dup.txt"), file("dup.txt"), file("clash"), file("clash/inner.txt")),
                e -> e != locked, new UnzipProperties());

        assertThat(plan.files()).extracting(ZipExtractionPlan.FileEntry::path).containsExactly("dup.txt", "clash/inner.txt");
        assertThat(plan.skipped()).extracting(UnzipSkippedEntry::path, UnzipSkippedEntry::reason).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("locked.txt", SkipReason.UNSUPPORTED),
                org.assertj.core.groups.Tuple.tuple("dup.txt", SkipReason.DUPLICATE_NAME),
                org.assertj.core.groups.Tuple.tuple("clash", SkipReason.DUPLICATE_NAME));
        assertThat(plan.totalFileBytes()).isEqualTo(20);
    }

    @Test
    void refusesTooManyEntries() {
        UnzipProperties props = new UnzipProperties();
        props.setMaxEntries(2);

        assertThatThrownBy(() -> plan(props, file("a"), file("b"), file("c")))
                .isInstanceOfSatisfying(UnzipException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UnzipException.ZIP_TOO_MANY_ENTRIES));
    }

    @Test
    void refusesArchivesThatExpandTooMuch() {
        UnzipProperties props = new UnzipProperties();
        props.setMaxUncompressedBytes(15);

        assertThatThrownBy(() -> plan(props, file("a"), file("b")))
                .isInstanceOfSatisfying(UnzipException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UnzipException.ZIP_TOO_LARGE));
    }

    @Test
    void refusesSuspiciousCompressionRatiosAboveTheThreshold() {
        UnzipProperties props = new UnzipProperties();

        // 10 MB from 10 KB (1000:1): a bomb.
        assertThatThrownBy(() -> plan(props, entry("bomb.bin", 10L * 1024 * 1024, 10L * 1024)))
                .isInstanceOfSatisfying(UnzipException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UnzipException.ZIP_BOMB));
        // A small, very repetitive file stays accepted.
        assertThat(plan(props, entry("log.txt", 500_000, 100)).files()).hasSize(1);
    }
}
