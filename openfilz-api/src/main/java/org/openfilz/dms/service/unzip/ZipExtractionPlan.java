package org.openfilz.dms.service.unzip;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.openfilz.dms.config.UnzipProperties;
import org.openfilz.dms.dto.response.UnzipResponse.SkipReason;
import org.openfilz.dms.dto.response.UnzipResponse.UnzipSkippedEntry;
import org.openfilz.dms.exception.UnzipException;
import org.openfilz.dms.service.StorageService;

import java.util.*;
import java.util.function.Predicate;

/**
 * What extracting an archive will create, computed from its central directory alone — before any
 * byte is inflated or written. Entry paths are normalised ({@code \} → {@code /}, empty and
 * {@code .} segments dropped); an entry that tries to leave the destination ({@code ..}, a drive
 * letter) or carries an invalid name is refused, never resolved. Implicit parent folders (archives
 * that only list files) are added. Archive-wide limits ({@link UnzipProperties}) abort the whole
 * operation; per-entry problems only skip that entry.
 */
public record ZipExtractionPlan(List<List<String>> foldersByDepth,
                                List<FileEntry> files,
                                List<UnzipSkippedEntry> skipped,
                                long totalFileBytes) {

    /** Folder path of the destination itself. */
    public static final String ROOT = "";

    private static final String SEPARATOR = StorageService.FOLDER_SEPARATOR;
    private static final int MAX_NAME_LENGTH = 255;
    private static final Set<String> IGNORED_NAMES = Set.of(".DS_Store", "Thumbs.db", "desktop.ini");
    private static final String MAC_RESOURCE_FORK_DIR = "__MACOSX";

    /**
     * A file to extract.
     *
     * @param path       normalised path inside the archive
     * @param parentPath normalised path of its folder ({@link #ROOT} for the top level)
     * @param name       document name
     * @param size       uncompressed size from the central directory
     */
    public record FileEntry(ZipArchiveEntry entry, String path, String parentPath, String name, long size) {
    }

    /**
     * @param readable whether the entry data can be inflated ({@code ZipFile::canReadEntryData}: false when
     *                 encrypted or compressed with an unsupported method)
     */
    public static ZipExtractionPlan of(Iterable<ZipArchiveEntry> entries, Predicate<ZipArchiveEntry> readable,
                                       UnzipProperties props) {
        Set<String> folders = new HashSet<>();
        Map<String, FileEntry> files = new LinkedHashMap<>();
        List<UnzipSkippedEntry> skipped = new ArrayList<>();
        long total = 0;
        int count = 0;

        for (ZipArchiveEntry entry : entries) {
            if (++count > props.getMaxEntries()) {
                throw UnzipException.tooLarge(UnzipException.ZIP_TOO_MANY_ENTRIES,
                        "the archive has more than " + props.getMaxEntries() + " entries");
            }
            String rawName = entry.getName();
            List<String> segments = segments(rawName);
            if (segments == null) {
                skipped.add(new UnzipSkippedEntry(rawName, SkipReason.UNSAFE_PATH, null));
                continue;
            }
            if (segments.isEmpty() || isIgnored(segments)) {
                continue;
            }
            if (entry.isDirectory()) {
                addWithAncestors(folders, segments, segments.size());
                continue;
            }
            String path = String.join(SEPARATOR, segments);
            if (!readable.test(entry)) {
                skipped.add(new UnzipSkippedEntry(path, SkipReason.UNSUPPORTED, "encrypted or unsupported compression method"));
                continue;
            }
            long size = entry.getSize();
            if (size < 0) {
                skipped.add(new UnzipSkippedEntry(path, SkipReason.UNSUPPORTED, "unknown uncompressed size"));
                continue;
            }
            checkRatio(entry, path, size, props);
            if (files.containsKey(path)) {
                skipped.add(new UnzipSkippedEntry(path, SkipReason.DUPLICATE_NAME, "listed twice in the archive"));
                continue;
            }
            total += size;
            if (total > props.getMaxUncompressedBytes()) {
                throw UnzipException.tooLarge(UnzipException.ZIP_TOO_LARGE,
                        "the archive expands to more than " + props.getMaxUncompressedBytes() + " bytes");
            }
            addWithAncestors(folders, segments, segments.size() - 1);
            String parentPath = String.join(SEPARATOR, segments.subList(0, segments.size() - 1));
            files.put(path, new FileEntry(entry, path, parentPath, segments.getLast(), size));
        }

        // A path that is both a folder and a file: the folder wins, the file is skipped.
        List<FileEntry> fileList = new ArrayList<>(files.size());
        for (FileEntry file : files.values()) {
            if (folders.contains(file.path())) {
                skipped.add(new UnzipSkippedEntry(file.path(), SkipReason.DUPLICATE_NAME, "a folder of the same name is in the archive"));
                total -= file.size();
            } else {
                fileList.add(file);
            }
        }
        return new ZipExtractionPlan(byDepth(folders), fileList, skipped, total);
    }

    /** Normalised, validated segments of an entry name; {@code null} when the path is unsafe or invalid. */
    static List<String> segments(String rawName) {
        List<String> segments = new ArrayList<>();
        String[] parts = rawName.replace('\\', '/').split(SEPARATOR);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..") || (i == 0 && part.length() == 2 && part.charAt(1) == ':')) {
                return null;
            }
            // '#' separates the storage prefix from the original name: uploads strip it too.
            String name = part.replace(StorageService.FILENAME_SEPARATOR, "").strip();
            if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || name.chars().anyMatch(Character::isISOControl)) {
                return null;
            }
            segments.add(name);
        }
        return segments;
    }

    /** Folder path of the parent of {@code path} ({@link #ROOT} for a top-level folder). */
    public static String parentOf(String path) {
        int i = path.lastIndexOf(SEPARATOR);
        return i < 0 ? ROOT : path.substring(0, i);
    }

    /** Last segment of {@code path}. */
    public static String nameOf(String path) {
        return path.substring(path.lastIndexOf(SEPARATOR) + 1);
    }

    private static boolean isIgnored(List<String> segments) {
        return segments.getFirst().equals(MAC_RESOURCE_FORK_DIR) || IGNORED_NAMES.contains(segments.getLast());
    }

    private static void checkRatio(ZipArchiveEntry entry, String path, long size, UnzipProperties props) {
        long compressed = entry.getCompressedSize();
        if (size > props.getRatioThresholdBytes() && compressed > 0 && size / compressed > props.getMaxCompressionRatio()) {
            throw UnzipException.tooLarge(UnzipException.ZIP_BOMB,
                    "entry '" + path + "' has a suspicious compression ratio (" + size / compressed + ":1)");
        }
    }

    private static void addWithAncestors(Set<String> folders, List<String> segments, int depth) {
        for (int i = 1; i <= depth; i++) {
            folders.add(String.join(SEPARATOR, segments.subList(0, i)));
        }
    }

    private static List<List<String>> byDepth(Set<String> folders) {
        TreeMap<Integer, List<String>> levels = new TreeMap<>();
        for (String folder : folders) {
            int depth = (int) folder.chars().filter(c -> c == '/').count();
            levels.computeIfAbsent(depth, _ -> new ArrayList<>()).add(folder);
        }
        levels.values().forEach(Collections::sort);
        return List.copyOf(levels.values());
    }
}
