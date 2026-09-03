package org.openfilz.dms.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.PdfToolsProperties;
import org.openfilz.dms.dto.request.pdf.MergeRequest;
import org.openfilz.dms.dto.request.pdf.MergeSource;
import org.openfilz.dms.dto.request.pdf.OrganizeRequest;
import org.openfilz.dms.dto.request.pdf.OutputMode;
import org.openfilz.dms.dto.request.pdf.OutputTarget;
import org.openfilz.dms.dto.request.pdf.PageInstruction;
import org.openfilz.dms.dto.request.pdf.RotateRequest;
import org.openfilz.dms.dto.request.pdf.SplitMode;
import org.openfilz.dms.dto.request.pdf.SplitOutput;
import org.openfilz.dms.dto.request.pdf.SplitRequest;
import org.openfilz.dms.dto.response.pdf.PdfInfo;
import org.openfilz.dms.dto.response.pdf.PdfOperationResponse;
import org.openfilz.dms.dto.response.pdf.PdfOutlineEntry;
import org.openfilz.dms.dto.response.pdf.PdfOutputInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.AbstractOpenFilzException;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.PdfToolsService;
import org.openfilz.dms.service.pdf.PageRangeParser;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The PDF tools as {@code @Tool} methods, shared by the in-app AI assistant and the MCP server.
 * A thin translation layer over {@link PdfToolsService}: resolves document names to ids with the
 * caller's access policy, applies the same role gate as the REST endpoints
 * ({@link AiToolRolePolicy}), and turns results and refusals into text a model can act on.
 * <p>
 * Built per request by {@code PdfAiToolsContributor} and bound to the caller with
 * {@link #forUser}; never a shared singleton.
 */
@Slf4j
public class PdfAiTools {

    private static final int MAX_LISTED_BOOKMARKS = 30;

    private final PdfToolsService pdfToolsService;
    private final DocumentRepository documentRepository;
    private final AiAccessPolicy accessPolicy;
    private final AiToolRolePolicy rolePolicy;
    private final PdfToolsProperties props;

    private String userEmail;
    private Authentication authentication;

    public PdfAiTools(PdfToolsService pdfToolsService, DocumentRepository documentRepository,
                      AiAccessPolicy accessPolicy, AiToolRolePolicy rolePolicy, PdfToolsProperties props) {
        this.pdfToolsService = pdfToolsService;
        this.documentRepository = documentRepository;
        this.accessPolicy = accessPolicy;
        this.rolePolicy = rolePolicy;
        this.props = props;
    }

    /** Bind the tools instance to the requesting user (fluent, used by the contributor). */
    public PdfAiTools forUser(String userEmail, Authentication authentication) {
        this.userEmail = userEmail;
        this.authentication = authentication;
        return this;
    }

    // ── tools ───────────────────────────────────────────────────────────────

    @Tool(description = "Describe a stored PDF: number of pages, page sizes, bookmarks, and whether it is "
            + "password-protected or digitally signed. Use it before splitting or reorganising pages.")
    public String getPdfInfo(
            @ToolParam(description = "Name (or id) of the PDF document") String document) {
        String denial = deny("getPdfInfo", ToolCapability.DOCUMENT_READ);
        if (denial != null) return denial;
        return run(() -> {
            Lookup lookup = resolvePdf(document);
            if (lookup.error() != null) return lookup.error();
            PdfInfo info = blockWithAuth(pdfToolsService.info(lookup.document().getId()));
            StringBuilder sb = new StringBuilder();
            sb.append("'").append(info.name()).append("' (id ").append(info.documentId()).append("): ")
                    .append(info.pageCount()).append(" page").append(info.pageCount() != 1 ? "s" : "")
                    .append(", ").append(info.size()).append(" bytes");
            if (info.encrypted()) sb.append(", PASSWORD-PROTECTED (cannot be transformed)");
            if (info.signed()) sb.append(", DIGITALLY SIGNED (page changes invalidate the signature; save results as a new document)");
            if (info.activeSignatureEnvelope()) sb.append(", BEING SIGNED (active e-Sign envelope; results can only be saved as a new document)");
            if (!info.pages().isEmpty()) {
                var first = info.pages().getFirst();
                sb.append(". First page ").append(first.width()).append("x").append(first.height()).append(" pt");
                if (first.rotation() != 0) sb.append(" rotated ").append(first.rotation()).append("°");
            }
            if (info.outline().isEmpty()) {
                sb.append(". No bookmarks.");
            } else {
                sb.append(". Bookmarks: ");
                sb.append(info.outline().stream().limit(MAX_LISTED_BOOKMARKS)
                        .map(e -> "  ".repeat(Math.max(0, e.level() - 1)) + e.title() + (e.page() != null ? " (p." + e.page() + ")" : ""))
                        .collect(Collectors.joining("; ")));
                if (info.outline().size() > MAX_LISTED_BOOKMARKS) {
                    sb.append("; … ").append(info.outline().size() - MAX_LISTED_BOOKMARKS).append(" more");
                }
            }
            return sb.toString();
        });
    }

    @Tool(description = "Merge several PDF documents into one new PDF, in the given order. "
            + "Use this when the user asks to combine, concatenate or join PDFs.")
    public String mergePdfs(
            @ToolParam(description = "Comma-separated names (or ids) of the PDFs to merge, in order") String documents,
            @ToolParam(required = false, description = "Name of the merged document (default: '<first name> (merged).pdf')") String outputName,
            @ToolParam(required = false, description = "Name (or id) of the folder for the result; default: the folder of the first PDF") String folder,
            @ToolParam(required = false, description = "Add a bookmark per source document (default false)") Boolean addBookmarks) {
        String denial = deny("mergePdfs", ToolCapability.DOCUMENT_WRITE);
        if (denial != null) return denial;
        return run(() -> {
            List<String> names = splitList(documents);
            if (names.size() < 2) return "Give at least two PDF documents to merge (comma-separated).";
            List<MergeSource> sources = new ArrayList<>();
            for (String name : names) {
                Lookup lookup = resolvePdf(name);
                if (lookup.error() != null) return lookup.error();
                sources.add(new MergeSource(lookup.document().getId(), null));
            }
            FolderLookup target = resolveFolder(folder);
            if (target.error() != null) return target.error();
            UUID folderId = target.folderId() != null ? target.folderId() : resolvePdf(names.getFirst()).document().getParentId();
            if (!canCreateIn(folderId)) return "You don't have permission to create documents in the target folder.";
            PdfOperationResponse response = blockWithAuth(pdfToolsService.merge(new MergeRequest(sources,
                    Boolean.TRUE.equals(addBookmarks),
                    new OutputTarget(OutputMode.NEW_DOCUMENT, target.folderId(), outputName, false, null))));
            return "Merged " + sources.size() + " PDFs into " + describe(response.outputs().getFirst()) + ".";
        });
    }

    @Tool(description = "Split a PDF into several new documents. Modes: 'every-page' (one document per page), "
            + "'every-n-pages' (with pagesPerPart), 'at-pages' (new part starts at each of cutPages), "
            + "'ranges' (one part per range in ranges, e.g. '1-3; 4-10; 11-'), 'bookmarks' (one part per top-level bookmark).")
    public String splitPdf(
            @ToolParam(description = "Name (or id) of the PDF to split") String document,
            @ToolParam(description = "every-page | every-n-pages | at-pages | ranges | bookmarks") String mode,
            @ToolParam(required = false, description = "every-n-pages: pages per part") Integer pagesPerPart,
            @ToolParam(required = false, description = "at-pages: comma-separated pages where a new part starts, e.g. '5,9'") String cutPages,
            @ToolParam(required = false, description = "ranges: page ranges separated by ';', e.g. '1-3; 4-10; 11-'") String ranges,
            @ToolParam(required = false, description = "bookmarks: deepest bookmark level that starts a part (default 1)") Integer bookmarkLevel,
            @ToolParam(required = false, description = "Name (or id) of the folder for the parts; default: the folder of the PDF") String folder,
            @ToolParam(required = false, description = "Put the parts in a new sub-folder named after the PDF (default false)") Boolean createSubfolder,
            @ToolParam(required = false, description = "Name pattern for the parts with {name}, {index}, {first}, {last}, {title}; default '{name}-{index}'") String namePattern) {
        String denial = deny("splitPdf", ToolCapability.DOCUMENT_WRITE);
        if (denial != null) return denial;
        return run(() -> {
            Lookup lookup = resolvePdf(document);
            if (lookup.error() != null) return lookup.error();
            SplitMode splitMode = parseSplitMode(mode);
            if (splitMode == null) return "Unknown split mode '" + mode + "'. Use every-page, every-n-pages, at-pages, ranges or bookmarks.";
            FolderLookup target = resolveFolder(folder);
            if (target.error() != null) return target.error();
            UUID folderId = target.folderId() != null ? target.folderId() : lookup.document().getParentId();
            if (!canCreateIn(folderId)) return "You don't have permission to create documents in the target folder.";
            List<Integer> cuts = null;
            if (cutPages != null && !cutPages.isBlank()) {
                cuts = new ArrayList<>();
                for (String token : splitList(cutPages)) {
                    try {
                        cuts.add(Integer.parseInt(token.trim()));
                    } catch (NumberFormatException e) {
                        return "cutPages must be comma-separated page numbers, got '" + token + "'.";
                    }
                }
            }
            List<String> rangeList = ranges != null && !ranges.isBlank()
                    ? Arrays.stream(ranges.split("[;|]")).map(String::trim).filter(s -> !s.isEmpty()).toList() : null;
            SplitRequest request = new SplitRequest(lookup.document().getId(), splitMode, pagesPerPart, cuts, rangeList,
                    bookmarkLevel, new SplitOutput(target.folderId(), namePattern, Boolean.TRUE.equals(createSubfolder), false));
            PdfOperationResponse response = blockWithAuth(pdfToolsService.split(request));
            return "Split '" + lookup.document().getName() + "' into " + response.outputs().size() + " documents: "
                    + response.outputs().stream().map(PdfAiTools::describe).collect(Collectors.joining(", ")) + ".";
        });
    }

    @Tool(description = "Rotate all or selected pages of a PDF by 90, 180 or 270 degrees clockwise. "
            + "By default the PDF is updated in place (new version); use saveAs='new-document' to keep the original.")
    public String rotatePdf(
            @ToolParam(description = "Name (or id) of the PDF") String document,
            @ToolParam(description = "Clockwise degrees: 90, 180 or 270 (use 270 for 90° counter-clockwise)") Integer angle,
            @ToolParam(required = false, description = "Pages to rotate, e.g. '1-3,7', 'odd', 'even'; default all") String pages,
            @ToolParam(required = false, description = "'new-version' (default, updates the PDF in place) or 'new-document'") String saveAs) {
        String denial = deny("rotatePdf", ToolCapability.DOCUMENT_WRITE);
        if (denial != null) return denial;
        return run(() -> {
            Lookup lookup = resolvePdf(document);
            if (lookup.error() != null) return lookup.error();
            if (angle == null) return "angle is required (90, 180 or 270).";
            OutputTarget target = outputFor(saveAs, lookup.document(), null);
            String permission = requireWritePermission(target, lookup.document());
            if (permission != null) return permission;
            PdfOperationResponse response = blockWithAuth(pdfToolsService.rotate(
                    new RotateRequest(List.of(lookup.document().getId()), angle, pages, target)));
            return "Rotated " + (pages == null || pages.isBlank() ? "all pages" : "pages " + pages) + " of '"
                    + lookup.document().getName() + "' by " + angle + "°: " + describe(response.outputs().getFirst()) + ".";
        });
    }

    @Tool(description = "Delete pages from a PDF. By default the PDF is updated in place (new version); "
            + "use saveAs='new-document' to keep the original.")
    public String deletePdfPages(
            @ToolParam(description = "Name (or id) of the PDF") String document,
            @ToolParam(description = "Pages to remove, e.g. '2,5-7', 'odd', 'even'") String pages,
            @ToolParam(required = false, description = "'new-version' (default) or 'new-document'") String saveAs) {
        String denial = deny("deletePdfPages", ToolCapability.DOCUMENT_WRITE);
        if (denial != null) return denial;
        return run(() -> {
            Lookup lookup = resolvePdf(document);
            if (lookup.error() != null) return lookup.error();
            if (pages == null || pages.isBlank()) return "pages is required (which pages to delete).";
            int pageCount = pageCount(lookup.document());
            Set<Integer> remove = new LinkedHashSet<>(PageRangeParser.parse(pages, pageCount));
            List<PageInstruction> keep = new ArrayList<>();
            for (int p = 1; p <= pageCount; p++) {
                if (!remove.contains(p)) keep.add(new PageInstruction(null, p, 0));
            }
            if (keep.isEmpty()) return "That would remove every page; a PDF needs at least one page.";
            OutputTarget target = outputFor(saveAs, lookup.document(), null);
            String permission = requireWritePermission(target, lookup.document());
            if (permission != null) return permission;
            PdfOperationResponse response = blockWithAuth(pdfToolsService.organize(
                    new OrganizeRequest(lookup.document().getId(), keep, target)));
            return "Removed " + remove.size() + " page" + (remove.size() != 1 ? "s" : "") + " from '"
                    + lookup.document().getName() + "': " + describe(response.outputs().getFirst()) + ".";
        });
    }

    @Tool(description = "Extract selected pages of a PDF into a new PDF document (the original is left unchanged).")
    public String extractPdfPages(
            @ToolParam(description = "Name (or id) of the PDF") String document,
            @ToolParam(description = "Pages to extract, in order, e.g. '1-3,7', 'odd', 'even'") String pages,
            @ToolParam(required = false, description = "Name of the new document (default: '<name> (edited).pdf')") String outputName,
            @ToolParam(required = false, description = "Name (or id) of the folder for the result; default: the folder of the PDF") String folder) {
        String denial = deny("extractPdfPages", ToolCapability.DOCUMENT_WRITE);
        if (denial != null) return denial;
        return run(() -> {
            Lookup lookup = resolvePdf(document);
            if (lookup.error() != null) return lookup.error();
            if (pages == null || pages.isBlank()) return "pages is required (which pages to extract).";
            FolderLookup target = resolveFolder(folder);
            if (target.error() != null) return target.error();
            UUID folderId = target.folderId() != null ? target.folderId() : lookup.document().getParentId();
            if (!canCreateIn(folderId)) return "You don't have permission to create documents in the target folder.";
            List<PageInstruction> selection = PageRangeParser.parse(pages, pageCount(lookup.document())).stream()
                    .map(p -> new PageInstruction(null, p, 0)).toList();
            PdfOperationResponse response = blockWithAuth(pdfToolsService.organize(new OrganizeRequest(
                    lookup.document().getId(), selection,
                    new OutputTarget(OutputMode.NEW_DOCUMENT, target.folderId(), outputName, false, null))));
            return "Extracted " + selection.size() + " page" + (selection.size() != 1 ? "s" : "") + " of '"
                    + lookup.document().getName() + "' into " + describe(response.outputs().getFirst()) + ".";
        });
    }

    @Tool(description = "Reorder the pages of a PDF. Give the new order as page numbers, e.g. '3,1,2'; pages not "
            + "listed keep their relative order after the listed ones. By default the PDF is updated in place.")
    public String reorderPdfPages(
            @ToolParam(description = "Name (or id) of the PDF") String document,
            @ToolParam(description = "New page order, e.g. '3,1,2' or '5-8,1-4'") String pageOrder,
            @ToolParam(required = false, description = "'new-version' (default) or 'new-document'") String saveAs) {
        String denial = deny("reorderPdfPages", ToolCapability.DOCUMENT_WRITE);
        if (denial != null) return denial;
        return run(() -> {
            Lookup lookup = resolvePdf(document);
            if (lookup.error() != null) return lookup.error();
            if (pageOrder == null || pageOrder.isBlank()) return "pageOrder is required, e.g. '3,1,2'.";
            int pageCount = pageCount(lookup.document());
            LinkedHashSet<Integer> order = new LinkedHashSet<>(PageRangeParser.parse(pageOrder, pageCount));
            for (int p = 1; p <= pageCount; p++) order.add(p);
            List<PageInstruction> instructions = order.stream().map(p -> new PageInstruction(null, p, 0)).toList();
            OutputTarget target = outputFor(saveAs, lookup.document(), null);
            String permission = requireWritePermission(target, lookup.document());
            if (permission != null) return permission;
            PdfOperationResponse response = blockWithAuth(pdfToolsService.organize(
                    new OrganizeRequest(lookup.document().getId(), instructions, target)));
            return "Reordered the pages of '" + lookup.document().getName() + "': " + describe(response.outputs().getFirst()) + ".";
        });
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    /** A document lookup: either the document or a message explaining why it could not be resolved. */
    record Lookup(Document document, String error) {
    }

    record FolderLookup(UUID folderId, String error) {
    }

    private interface ToolBody {
        String call() throws Exception;
    }

    private String run(ToolBody body) {
        if (!props.isActive()) {
            return "The PDF tools are disabled on this OpenFilz deployment (openfilz.pdf-tools.active=false).";
        }
        try {
            return body.call();
        } catch (AbstractOpenFilzException | IllegalArgumentException e) {
            log.debug("[AI-TOOL] PDF tool refused: {}", e.getMessage());
            return "Could not perform the operation: " + e.getMessage();
        } catch (Exception e) {
            log.error("[AI-TOOL] PDF tool failed", e);
            return "Error: " + e.getMessage();
        }
    }

    private String deny(String toolName, ToolCapability capability) {
        if (rolePolicy == null || rolePolicy.isAllowed(authentication, capability)) {
            return null;
        }
        log.warn("[AI-TOOL] {} refused: caller lacks the role for {}", toolName, capability);
        return "Not permitted: your OpenFilz role does not allow this operation (" + capability
                + "). Ask an administrator for the required role.";
    }

    private <T> T blockWithAuth(Mono<T> mono) {
        return (authentication != null
                ? mono.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                : mono).block();
    }

    private boolean canRead(UUID documentId) {
        return Boolean.TRUE.equals(accessPolicy.canRead(documentId, userEmail).block());
    }

    private boolean canModify(UUID documentId) {
        return Boolean.TRUE.equals(accessPolicy.canModify(documentId, userEmail).block());
    }

    private boolean canCreateIn(UUID folderId) {
        return folderId == null
                ? Boolean.TRUE.equals(accessPolicy.canCreateAtRoot(userEmail).block())
                : canModify(folderId);
    }

    private OutputTarget outputFor(String saveAs, Document document, String outputName) {
        boolean newDocument = saveAs != null && saveAs.trim().toLowerCase(Locale.ROOT).replace('_', '-').startsWith("new-doc");
        return newDocument
                ? new OutputTarget(OutputMode.NEW_DOCUMENT, document.getParentId(), outputName, false, null)
                : new OutputTarget(OutputMode.NEW_VERSION, null, null, null, null);
    }

    private String requireWritePermission(OutputTarget target, Document document) {
        if (target.mode() == OutputMode.NEW_VERSION) {
            return canModify(document.getId()) ? null
                    : "You don't have permission to modify '" + document.getName() + "'. Try saveAs='new-document'.";
        }
        return canCreateIn(document.getParentId()) ? null
                : "You don't have permission to create documents in the folder of '" + document.getName() + "'.";
    }

    private int pageCount(Document document) {
        PdfInfo info = blockWithAuth(pdfToolsService.info(document.getId()));
        if (info.encrypted()) {
            throw new IllegalArgumentException("'" + document.getName() + "' is password-protected and cannot be transformed");
        }
        return info.pageCount();
    }

    private Lookup resolvePdf(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return new Lookup(null, "A document name (or id) is required.");
        }
        UUID id = parseUuid(nameOrId.trim());
        List<Document> candidates;
        if (id != null) {
            Document byId = blockWithAuth(documentRepository.findById(id));
            candidates = byId != null ? List.of(byId) : List.of();
        } else {
            List<Document> found = blockWithAuth(documentRepository.findByNameContainingIgnoreCaseAndActiveTrue(nameOrId.trim()).collectList());
            candidates = found != null ? found : List.of();
        }
        List<Document> pdfs = candidates.stream()
                .filter(d -> d.getType() == DocumentType.FILE && isPdf(d) && canRead(d.getId()))
                .toList();
        if (pdfs.isEmpty()) {
            return new Lookup(null, "No PDF document matching '" + nameOrId + "' was found (or you cannot access it). "
                    + "Use queryDocuments to find the exact name.");
        }
        if (pdfs.size() == 1) {
            return new Lookup(pdfs.getFirst(), null);
        }
        List<Document> exact = pdfs.stream().filter(d -> d.getName().equalsIgnoreCase(nameOrId.trim())).toList();
        if (exact.size() == 1) {
            return new Lookup(exact.getFirst(), null);
        }
        return new Lookup(null, "Several PDFs match '" + nameOrId + "': "
                + pdfs.stream().limit(8).map(d -> "'" + d.getName() + "' (id " + d.getId() + ")").collect(Collectors.joining(", "))
                + ". Use the id.");
    }

    private FolderLookup resolveFolder(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return new FolderLookup(null, null);
        }
        UUID id = parseUuid(nameOrId.trim());
        List<Document> candidates;
        if (id != null) {
            Document byId = blockWithAuth(documentRepository.findById(id));
            candidates = byId != null ? List.of(byId) : List.of();
        } else {
            List<Document> found = blockWithAuth(documentRepository.findByNameContainingIgnoreCaseAndActiveTrue(nameOrId.trim()).collectList());
            candidates = found != null ? found : List.of();
        }
        List<Document> folders = candidates.stream()
                .filter(d -> d.getType() == DocumentType.FOLDER && canRead(d.getId()))
                .toList();
        List<Document> exact = folders.stream().filter(d -> d.getName().equalsIgnoreCase(nameOrId.trim())).toList();
        if (exact.size() == 1) {
            return new FolderLookup(exact.getFirst().getId(), null);
        }
        if (folders.size() == 1) {
            return new FolderLookup(folders.getFirst().getId(), null);
        }
        if (folders.isEmpty()) {
            return new FolderLookup(null, "No folder named '" + nameOrId + "' exists. Create it first (createFolder) or omit the folder.");
        }
        return new FolderLookup(null, "Several folders match '" + nameOrId + "': "
                + folders.stream().limit(8).map(d -> "'" + d.getName() + "' (id " + d.getId() + ")").collect(Collectors.joining(", "))
                + ". Use the id.");
    }

    private static boolean isPdf(Document d) {
        return "application/pdf".equalsIgnoreCase(d.getContentType())
                || (d.getName() != null && d.getName().toLowerCase(Locale.ROOT).endsWith(".pdf"));
    }

    private static SplitMode parseSplitMode(String mode) {
        if (mode == null) return null;
        return switch (mode.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "every-page", "each-page", "pages" -> SplitMode.EVERY_PAGE;
            case "every-n-pages", "every-n", "n-pages", "chunks" -> SplitMode.EVERY_N_PAGES;
            case "at-pages", "at", "cut" -> SplitMode.AT_PAGES;
            case "ranges", "range", "page-ranges" -> SplitMode.PAGE_RANGES;
            case "bookmarks", "bookmark", "outline", "by-outline-level" -> SplitMode.BY_OUTLINE_LEVEL;
            default -> null;
        };
    }

    private static List<String> splitList(String csv) {
        if (csv == null) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String describe(PdfOutputInfo output) {
        return "'" + output.name() + "' (id " + output.documentId() + ", " + output.pageCount() + " page"
                + (output.pageCount() != 1 ? "s" : "") + (output.versionId() != null ? ", new version" : "") + ")";
    }

    /** For tests: the bookmark entries rendered by {@link #getPdfInfo}. */
    static String renderOutline(List<PdfOutlineEntry> outline) {
        return outline.stream().map(e -> e.title() + (e.page() != null ? " (p." + e.page() + ")" : ""))
                .collect(Collectors.joining("; "));
    }
}
