package org.openfilz.dms.utils;

import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.request.PageCriteria;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.Suggest;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Component
public class DocumentSearchUtil {
    public static final String FILTER_TYPE = "type";
    public static final String FILTER_EXTENSION = "extension";
    public static final String FILTER_SIZE = "size";
    public static final String FILTER_PARENT_ID = "parentId";
    public static final String FILTER_CREATED_AT_BEFORE = "createdAtBefore";
    public static final String FILTER_CREATED_AT_AFTER = "createdAtAfter";
    public static final String FILTER_UPDATED_AT_BEFORE = "updatedAtBefore";
    public static final String FILTER_UPDATED_AT_AFTER = "updatedAtAfter";
    public static final String FILTER_CREATED_BY = "createdBy";
    public static final String FILTER_UPDATED_BY = "updatedBy";
    public static final String FILTER_METADATA = "metadata.";
    public static final String FILTER_FAVORITE = "favorite";

    public static Long toLong(Map<String, String> map) {
        String size = map.get(DocumentSearchUtil.FILTER_SIZE);
        if(size != null) {
            return Long.valueOf(size);
        }
        return null;
    }

    public static String splitWithSpaces(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s
                .replaceAll("([a-z])([A-Z])", "$1 $2")  // Split camelCase
                .replaceAll("[_-]", " ")                 // Replace underscores and hyphens with spaces
                .toLowerCase()
                .replaceAll("\\s+", " ")                 // Normalize multiple spaces
                .trim();
    }

    public PageCriteria toPageCriteria(SortInput sort, int page, int size) {
        String sortBy = null;
        SortOrder sortOrder = null;
        if(sort != null) {
            sortBy = sort.field();
            sortOrder = sort.order();
        }
        return new PageCriteria(sortBy,
                sortOrder,
                page,
                size);
    }

    public Map<String, Object> totMetadataMap(Map<String, String> map) {
        Map<String, Object> metadataMap = map.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(DocumentSearchUtil.FILTER_METADATA))
                .collect(Collectors.toMap(entry -> entry.getKey().substring(DocumentSearchUtil.FILTER_METADATA.length()), Map.Entry::getValue));
        return metadataMap.isEmpty() ? null : metadataMap;
    }

    public OffsetDateTime toDate(Map<String, String> map, String field) {
        String date = map.get(field);
        if(date == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(date);
        } catch (Exception e) {
            log.error("Error parsing date: {}", date, e);
            return null;
        }
    }

    public DocumentType toDocumentType(Map<String, String> map) {
        String type = map.get(DocumentSearchUtil.FILTER_TYPE);
        return type != null ? DocumentType.valueOf(type) : null;
    }

    public UUID toUuid(String id) {
        return id != null ? UUID.fromString(id) : null;
    }

    public Map<String, String> toFilterMap(List<FilterInput> filters) {
        if(CollectionUtils.isEmpty(filters)) {
            return null;
        }
        Map<String, String> map = new HashMap<>(filters.size());
        filters.forEach(filterInput -> {
            map.put(filterInput.field(),  filterInput.value());
        });
        return map;
    }

    public Suggest toSuggest(Readable row) {
        UUID uuid = row.get(0, UUID.class);
        String name = row.get(1, String.class);
        DocumentType type = DocumentType.valueOf(row.get(2, String.class));
        String ext = FileUtils.getDocumentExtension(type, name);
        String s = FileUtils.removeFileExtension(name);
        return new Suggest(uuid, s, ext);
    }

    public ListFolderRequest toListFolderRequest(String query,
                                                  Map<String, String> filters,
                                                  SortInput sort,
                                                  int page,
                                                  int size) {
        if(filters == null) {
            return new ListFolderRequest(null,
                    null,
                    null,
                    null,
                    query != null ? query.trim() : null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true,
                    toPageCriteria(sort, page, size)
            );
        }

        return new ListFolderRequest(toUuid(filters.get(FILTER_PARENT_ID)),
                toDocumentType(filters),
                ContentTypeMapper.getContentType(filters.get(DocumentSearchUtil.FILTER_EXTENSION)),
                null,
                query,
                totMetadataMap(filters),
                DocumentSearchUtil.toLong(filters),
                toDate(filters, DocumentSearchUtil.FILTER_CREATED_AT_AFTER),
                toDate(filters, DocumentSearchUtil.FILTER_CREATED_AT_BEFORE),
                toDate(filters, DocumentSearchUtil.FILTER_UPDATED_AT_AFTER),
                toDate(filters, DocumentSearchUtil.FILTER_UPDATED_AT_BEFORE),
                filters.get(DocumentSearchUtil.FILTER_CREATED_BY),
                filters.get(DocumentSearchUtil.FILTER_UPDATED_BY),
                toBoolean(filters.get(FILTER_FAVORITE)),
                true,
                toPageCriteria(sort, page, size)
        );
    }

    private Boolean toBoolean(String bool) {
        if(bool == null || bool.isEmpty()) {
            return null;
        }
        return Boolean.valueOf(bool);
    }
}
