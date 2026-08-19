package org.openfilz.dms.utils;

import io.r2dbc.spi.Readable;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.request.PageCriteria;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.Suggest;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentSearchUtilTest {

    private final DocumentSearchUtil util = new DocumentSearchUtil();

    // --- toLong ---

    @Test
    void toLong_withSize_parses() {
        assertEquals(123L, DocumentSearchUtil.toLong(Map.of(DocumentSearchUtil.FILTER_SIZE, "123")));
    }

    @Test
    void toLong_withoutSize_returnsNull() {
        assertNull(DocumentSearchUtil.toLong(Map.of("other", "x")));
    }

    // --- splitWithSpaces ---

    @Test
    void splitWithSpaces_null_returnsNull() {
        assertNull(DocumentSearchUtil.splitWithSpaces(null));
    }

    @Test
    void splitWithSpaces_empty_returnsEmpty() {
        assertEquals("", DocumentSearchUtil.splitWithSpaces(""));
    }

    @Test
    void splitWithSpaces_camelCaseUnderscoreHyphen_normalised() {
        assertEquals("my file name", DocumentSearchUtil.splitWithSpaces("myFile_name"));
        assertEquals("a b c", DocumentSearchUtil.splitWithSpaces("a-b-c"));
    }

    // --- toPageCriteria ---

    @Test
    void toPageCriteria_withSort_usesFieldAndOrder() {
        PageCriteria pc = util.toPageCriteria(new SortInput("name", SortOrder.DESC), 2, 50);
        assertEquals("name", pc.sortBy());
        assertEquals(SortOrder.DESC, pc.sortOrder());
        assertEquals(2, pc.pageNumber());
        assertEquals(50, pc.pageSize());
    }

    @Test
    void toPageCriteria_nullSort_leavesSortFieldsNull() {
        PageCriteria pc = util.toPageCriteria(null, 0, 10);
        assertNull(pc.sortBy());
        assertNull(pc.sortOrder());
    }

    // --- totMetadataMap ---

    @Test
    void totMetadataMap_withMetadataPrefixedKeys_stripsPrefix() {
        Map<String, Object> result = util.totMetadataMap(Map.of(
                DocumentSearchUtil.FILTER_METADATA + "author", "alice",
                "ignored", "x"));
        assertEquals(Map.of("author", "alice"), result);
    }

    @Test
    void totMetadataMap_noMetadataKeys_returnsNull() {
        assertNull(util.totMetadataMap(Map.of("foo", "bar")));
    }

    // --- toDate ---

    @Test
    void toDate_valid_parses() {
        assertNotNull(util.toDate(Map.of("d", "2024-01-01T00:00:00Z"), "d"));
    }

    @Test
    void toDate_missing_returnsNull() {
        assertNull(util.toDate(Map.of(), "d"));
    }

    @Test
    void toDate_invalid_returnsNull() {
        assertNull(util.toDate(Map.of("d", "not-a-date"), "d"));
    }

    // --- toDocumentType / toUuid ---

    @Test
    void toDocumentType_valueAndNull() {
        assertEquals(DocumentType.FOLDER, util.toDocumentType(Map.of(DocumentSearchUtil.FILTER_TYPE, "FOLDER")));
        assertNull(util.toDocumentType(Map.of()));
    }

    @Test
    void toUuid_valueAndNull() {
        UUID id = UUID.randomUUID();
        assertEquals(id, util.toUuid(id.toString()));
        assertNull(util.toUuid(null));
    }

    // --- toFilterMap ---

    @Test
    void toFilterMap_nullOrEmpty_returnsNull() {
        assertNull(util.toFilterMap(null));
        assertNull(util.toFilterMap(List.of()));
    }

    @Test
    void toFilterMap_withFilters_buildsMap() {
        Map<String, String> result = util.toFilterMap(List.of(
                new FilterInput("type", "FILE"),
                new FilterInput("extension", "pdf")));
        assertEquals("FILE", result.get("type"));
        assertEquals("pdf", result.get("extension"));
    }

    // --- toSuggest ---

    @Test
    void toSuggest_buildsSuggestFromRow() {
        UUID id = UUID.randomUUID();
        Readable row = mock(Readable.class);
        when(row.get(0, UUID.class)).thenReturn(id);
        when(row.get(1, String.class)).thenReturn("report.pdf");
        when(row.get(2, String.class)).thenReturn("FILE");

        Suggest suggest = util.toSuggest(row);
        assertEquals(id, suggest.id());
        assertEquals("report", suggest.s());
        assertEquals("pdf", suggest.ext());
    }

    // --- toListFolderRequest ---

    @Test
    void toListFolderRequest_nullFilters_usesQueryOnly() {
        ListFolderRequest req = util.toListFolderRequest("  hello  ", null, null, 0, 20);
        assertEquals("hello", req.nameLike());
        assertNotNull(req.pageInfo());
    }

    @Test
    void toListFolderRequest_withFilters_mapsAllFields() {
        UUID parent = UUID.randomUUID();
        Map<String, String> filters = Map.ofEntries(
                Map.entry(DocumentSearchUtil.FILTER_PARENT_ID, parent.toString()),
                Map.entry(DocumentSearchUtil.FILTER_TYPE, "FILE"),
                Map.entry(DocumentSearchUtil.FILTER_EXTENSION, "pdf"),
                Map.entry(DocumentSearchUtil.FILTER_SIZE, "999"),
                Map.entry(DocumentSearchUtil.FILTER_CREATED_BY, "alice"),
                Map.entry(DocumentSearchUtil.FILTER_FAVORITE, "true"),
                Map.entry(DocumentSearchUtil.FILTER_METADATA + "tag", "x"));

        ListFolderRequest req = util.toListFolderRequest("q", filters, new SortInput("name", SortOrder.ASC), 1, 10);

        assertEquals(parent, req.id());
        assertEquals(DocumentType.FILE, req.type());
        assertEquals(999L, req.size());
        assertEquals("alice", req.createdBy());
        assertEquals(Boolean.TRUE, req.favorite());
        assertEquals(Map.of("tag", "x"), req.metadata());
    }

    @Test
    void toListFolderRequest_blankFavorite_isNull() {
        Map<String, String> filters = Map.of(DocumentSearchUtil.FILTER_FAVORITE, "");
        ListFolderRequest req = util.toListFolderRequest("q", filters, null, 0, 10);
        assertNull(req.favorite());
    }
}
