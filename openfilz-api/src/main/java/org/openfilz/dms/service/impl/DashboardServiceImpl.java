package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.response.DashboardStatisticsResponse;
import org.openfilz.dms.dto.response.FileTypeStats;
import org.openfilz.dms.dto.response.StorageBreakdown;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.DashboardService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

/**
 * Implementation of Dashboard Service
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardServiceImpl implements DashboardService {

    private final DocumentDAO documentDAO;

    // File type patterns for categorization
    private static final String DOCUMENTS_PATTERN = "application/%";
    private static final String IMAGES_PATTERN = "image/%";
    private static final String VIDEOS_PATTERN = "video/%";
    private static final String AUDIO_PATTERN = "audio/%";

    @Override
    public Mono<DashboardStatisticsResponse> getDashboardStatistics() {
        log.debug("Fetching dashboard statistics");

        // Get total file and folder counts
        Mono<Long> totalFilesMono = documentDAO.countFilesByType(DocumentType.FILE);
        Mono<Long> totalFoldersMono = documentDAO.countFilesByType(DocumentType.FOLDER);

        // Get storage statistics
        Mono<StorageBreakdown> storageMono = getStorageBreakdown();

        // Get file type counts (for now, we'll use the same storage data)
        Mono<List<FileTypeStats>> fileTypeCountsMono = getFileTypeCounts();

        return Mono.zip(totalFilesMono, totalFoldersMono, storageMono, fileTypeCountsMono)
                .map(tuple -> {
                    Long totalFiles = tuple.getT1();
                    Long totalFolders = tuple.getT2();
                    StorageBreakdown storage = tuple.getT3();
                    List<FileTypeStats> fileTypeCounts = tuple.getT4();

                    log.debug("Dashboard statistics: {} files, {} folders, {} bytes used",
                            totalFiles, totalFolders, storage.totalStorageUsed());

                    return new DashboardStatisticsResponse(
                            totalFiles,
                            totalFolders,
                            storage,
                            fileTypeCounts
                    );
                });
    }

    private Mono<StorageBreakdown> getStorageBreakdown() {
        // Get total storage used
        Mono<Long> totalStorageMono = documentDAO.getTotalStorageUsed();

        // Get storage by content type
        Mono<Long> documentsSizeMono = documentDAO.getTotalStorageByContentType(DOCUMENTS_PATTERN);
        Mono<Long> imagesSizeMono = documentDAO.getTotalStorageByContentType(IMAGES_PATTERN);
        Mono<Long> videosSizeMono = documentDAO.getTotalStorageByContentType(VIDEOS_PATTERN);
        Mono<Long> audioSizeMono = documentDAO.getTotalStorageByContentType(AUDIO_PATTERN);

        return Mono.zip(totalStorageMono, documentsSizeMono, imagesSizeMono, videosSizeMono, audioSizeMono)
                .map(tuple -> {
                    Long totalStorage = tuple.getT1();
                    Long documentsSize = tuple.getT2();
                    Long imagesSize = tuple.getT3();
                    Long videosSize = tuple.getT4();
                    Long audioSize = tuple.getT5();

                    // Calculate "other" as the difference
                    Long othersSize = totalStorage - documentsSize - imagesSize - videosSize - audioSize;

                    List<FileTypeStats> breakdown = Arrays.asList(
                            new FileTypeStats("documents", null, documentsSize),
                            new FileTypeStats("images", null, imagesSize),
                            new FileTypeStats("videos", null, videosSize),
                            new FileTypeStats("audio", null, audioSize),
                            new FileTypeStats("others", null, Math.max(0, othersSize))
                    );

                    // For now, set total available to null (no quota system)
                    return new StorageBreakdown(totalStorage, null, breakdown);
                });
    }

    private Mono<List<FileTypeStats>> getFileTypeCounts() {
        // Count files by content type pattern
        Mono<Long> documentsCountMono = countFilesByContentType(DOCUMENTS_PATTERN);
        Mono<Long> imagesCountMono = countFilesByContentType(IMAGES_PATTERN);
        Mono<Long> videosCountMono = countFilesByContentType(VIDEOS_PATTERN);
        Mono<Long> audioCountMono = countFilesByContentType(AUDIO_PATTERN);
        Mono<Long> totalFilesMono = documentDAO.countFilesByType(DocumentType.FILE);

        return Mono.zip(documentsCountMono, imagesCountMono, videosCountMono, audioCountMono, totalFilesMono)
                .map(tuple -> {
                    Long documentsCount = tuple.getT1();
                    Long imagesCount = tuple.getT2();
                    Long videosCount = tuple.getT3();
                    Long audioCount = tuple.getT4();
                    Long totalFiles = tuple.getT5();

                    // Calculate "others" as the difference
                    Long othersCount = totalFiles - documentsCount - imagesCount - videosCount - audioCount;

                    return Arrays.asList(
                            new FileTypeStats("documents", documentsCount, null),
                            new FileTypeStats("images", imagesCount, null),
                            new FileTypeStats("videos", videosCount, null),
                            new FileTypeStats("audio", audioCount, null),
                            new FileTypeStats("others", Math.max(0, othersCount), null)
                    );
                });
    }

    private Mono<Long> countFilesByContentType(String contentTypePattern) {
        return documentDAO.countFilesByContentType(contentTypePattern);
    }
}
