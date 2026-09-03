package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.PdfToolsProperties;
import org.openfilz.dms.dto.audit.IAuditLogDetails;
import org.openfilz.dms.dto.audit.PdfTransformAudit;
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
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.dto.response.pdf.PdfOperationResponse;
import org.openfilz.dms.dto.response.pdf.PdfPageInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.openfilz.dms.exception.FileSizeExceededException;
import org.openfilz.dms.exception.PdfToolsException;
import org.openfilz.dms.repository.SignatureEnvelopeRepository;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.pdf.PdfCompositionEngine;
import org.openfilz.dms.service.pdf.PdfCompositionEngine.Inspection;
import org.openfilz.dms.service.pdf.PdfCompositionEngine.PageRef;
import org.openfilz.dms.service.pdf.PdfTestFiles;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The orchestration rules around the engine — limits, guards on signed / encrypted / envelope-bound
 * documents, WORM, naming, write-back routing and provenance audit — with the collaborators mocked
 * and the engine partly stubbed (its real behaviour is covered by {@code PdfCompositionEngineTest}
 * and {@code PdfToolsIT}).
 */
@ExtendWith(MockitoExtension.class)
class PdfToolsServiceImplTest {

    @Mock DocumentService documentService;
    @Mock StorageService storageService;
    @Mock PdfCompositionEngine engine;
    @Mock AuditService auditService;
    @Mock SignatureEnvelopeRepository envelopeRepository;

    PdfToolsProperties props = new PdfToolsProperties();
    PdfToolsServiceImpl service;

    byte[] threePages = PdfTestFiles.pdf("P1", "P2", "P3");

    @BeforeEach
    void setUp() {
        service = new PdfToolsServiceImpl(documentService, storageService, engine, props, auditService, envelopeRepository);
        ReflectionTestUtils.setField(service, "wormMode", false);
        service.init();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Document doc(String name, String contentType, long size) {
        return Document.builder().id(UUID.randomUUID()).name(name).type(DocumentType.FILE)
                .contentType(contentType).size(size).storagePath("s/" + name).parentId(UUID.randomUUID()).build();
    }

    private void stubDownload(Document d, byte[] bytes) {
        when(documentService.findDocumentToDownloadById(d.getId())).thenReturn(Mono.just(d));
        doReturn(Mono.just(new ByteArrayResource(bytes))).when(storageService).loadFile(d.getStoragePath());
    }

    private static Inspection inspection(int pages, boolean encrypted, boolean signed) {
        List<PdfPageInfo> infos = new java.util.ArrayList<>();
        for (int i = 1; i <= pages; i++) infos.add(new PdfPageInfo(i, 595, 842, 0));
        return new Inspection(pages, infos, encrypted, signed, List.of());
    }

    /** The mocked engine writes a small valid PDF so the write-back can size it. */
    private void stubCompose() throws IOException {
        when(engine.compose(any(), any(), any(), any())).thenAnswer(inv -> {
            List<PageRef> refs = inv.getArgument(0);
            Path out = inv.getArgument(3);
            Files.write(out, PdfTestFiles.pdf("x"));
            return refs.size();
        });
    }

    private void stubUpload(UUID newId) {
        when(documentService.uploadDocument(any(FilePart.class), anyLong(), any(), isNull(), anyBoolean()))
                .thenAnswer(inv -> Mono.just(new UploadResponse(newId, ((FilePart) inv.getArgument(0)).filename(),
                        "application/pdf", inv.getArgument(1), null, null)));
        when(auditService.logAction(eq(AuditAction.PDF_TRANSFORM), eq(DocumentType.FILE), any(), any())).thenReturn(Mono.empty());
    }

    // ── guards ──────────────────────────────────────────────────────────────

    @Test
    void rejectsNonPdfSources() {
        Document txt = doc("notes.txt", "text/plain", 10);
        when(documentService.findDocumentToDownloadById(txt.getId())).thenReturn(Mono.just(txt));
        StepVerifier.create(service.info(txt.getId()))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(PdfToolsException.class)
                        .hasMessageContaining(PdfToolsException.NOT_A_PDF))
                .verify();
    }

    @Test
    void rejectsSourcesAboveTheSizeLimit() {
        props.setMaxInputBytes(100);
        Document big = doc("big.pdf", "application/pdf", 101);
        when(documentService.findDocumentToDownloadById(big.getId())).thenReturn(Mono.just(big));
        StepVerifier.create(service.info(big.getId()))
                .expectError(FileSizeExceededException.class)
                .verify();
    }

    @Test
    void rejectsTotalSizeAboveTheLimit() {
        props.setMaxInputBytes(150);
        Document a = doc("a.pdf", "application/pdf", 100);
        Document b = doc("b.pdf", "application/pdf", 100);
        when(documentService.findDocumentToDownloadById(a.getId())).thenReturn(Mono.just(a));
        when(documentService.findDocumentToDownloadById(b.getId())).thenReturn(Mono.just(b));
        StepVerifier.create(service.merge(new MergeRequest(List.of(new MergeSource(a.getId(), null), new MergeSource(b.getId(), null)), null, null)))
                .expectError(FileSizeExceededException.class)
                .verify();
    }

    @Test
    void refusesEncryptedSources() throws IOException {
        Document locked = doc("locked.pdf", "application/pdf", 10);
        stubDownload(locked, threePages);
        when(engine.inspect(any())).thenReturn(inspection(0, true, false));
        StepVerifier.create(service.rotate(new RotateRequest(List.of(locked.getId()), 90, null, null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(PdfToolsException.class)
                        .hasMessageContaining(PdfToolsException.PDF_ENCRYPTED))
                .verify();
    }

    @Test
    void infoReportsEncryptedInsteadOfFailing() throws IOException {
        Document locked = doc("locked.pdf", "application/pdf", 10);
        stubDownload(locked, threePages);
        when(engine.inspect(any())).thenReturn(inspection(0, true, false));
        StepVerifier.create(service.info(locked.getId()))
                .assertNext(info -> {
                    assertThat(info.encrypted()).isTrue();
                    assertThat(info.pageCount()).isZero();
                    assertThat(info.name()).isEqualTo("locked.pdf");
                })
                .verifyComplete();
    }

    @Test
    void refusesPageBudgetOverflow() throws IOException {
        props.setMaxPages(2);
        Document d = doc("d.pdf", "application/pdf", 10);
        stubDownload(d, threePages);
        when(engine.inspect(any())).thenReturn(inspection(3, false, false));
        StepVerifier.create(service.rotate(new RotateRequest(List.of(d.getId()), 90, null, null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(PdfToolsException.class)
                        .hasMessageContaining(PdfToolsException.PDF_TOO_MANY_PAGES))
                .verify();
    }

    @Test
    void refusesTooManySplitOutputs() throws IOException {
        props.setMaxOutputs(2);
        Document d = doc("d.pdf", "application/pdf", 10);
        stubDownload(d, threePages);
        when(engine.inspect(any())).thenReturn(inspection(3, false, false));
        StepVerifier.create(service.split(new SplitRequest(d.getId(), SplitMode.EVERY_PAGE, null, null, null, null, null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(PdfToolsException.class)
                        .hasMessageContaining(PdfToolsException.TOO_MANY_OUTPUTS))
                .verify();
    }

    @Test
    void inPlaceEditOfSignedPdfNeedsAcknowledgement() throws IOException {
        Document signed = doc("signed.pdf", "application/pdf", 10);
        stubDownload(signed, threePages);
        when(engine.inspect(any())).thenReturn(inspection(3, false, true));
        stubCompose();
        OrganizeRequest request = new OrganizeRequest(signed.getId(), List.of(new PageInstruction(null, 1, 0)), null);
        StepVerifier.create(service.organize(request))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PdfToolsException.class).hasMessageContaining(PdfToolsException.PDF_SIGNED);
                    assertThat(((PdfToolsException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                })
                .verify();
        verify(documentService, never()).replaceDocumentContent(any(), any(), any());
    }

    @Test
    void inPlaceEditOfSignedPdfProceedsWhenAcknowledged() throws IOException {
        Document signed = doc("signed.pdf", "application/pdf", 10);
        stubDownload(signed, threePages);
        when(engine.inspect(any())).thenReturn(inspection(3, false, true));
        stubCompose();
        when(envelopeRepository.findBySourceDocId(signed.getId())).thenReturn(Flux.empty());
        when(documentService.replaceDocumentContent(eq(signed.getId()), any(), any())).thenReturn(Mono.just(signed));
        when(storageService.getLatestVersionId(signed.getStoragePath())).thenReturn(Mono.just("v2"));
        when(auditService.logAction(eq(AuditAction.PDF_TRANSFORM), eq(DocumentType.FILE), any(), any())).thenReturn(Mono.empty());

        OutputTarget ack = new OutputTarget(OutputMode.NEW_VERSION, null, null, null, true);
        StepVerifier.create(service.organize(new OrganizeRequest(signed.getId(), List.of(new PageInstruction(null, 2, 90)), ack)))
                .assertNext(r -> {
                    assertThat(r.operation()).isEqualTo("organize");
                    assertThat(r.outputs()).hasSize(1);
                    assertThat(r.outputs().getFirst().documentId()).isEqualTo(signed.getId());
                    assertThat(r.outputs().getFirst().versionId()).isEqualTo("v2");
                    assertThat(r.outputs().getFirst().pageCount()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void inPlaceEditRefusedWhileAnEnvelopeIsActive() throws IOException {
        Document d = doc("contract.pdf", "application/pdf", 10);
        stubDownload(d, threePages);
        when(engine.inspect(any())).thenReturn(inspection(3, false, false));
        stubCompose();
        SignatureEnvelope active = SignatureEnvelope.builder().status(SignatureEnvelopeStatus.SENT).build();
        SignatureEnvelope done = SignatureEnvelope.builder().status(SignatureEnvelopeStatus.COMPLETED).build();
        when(envelopeRepository.findBySourceDocId(d.getId())).thenReturn(Flux.just(done, active));
        StepVerifier.create(service.rotate(new RotateRequest(List.of(d.getId()), 180, null, null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(PdfToolsException.class)
                        .hasMessageContaining(PdfToolsException.ACTIVE_SIGNATURE_ENVELOPE))
                .verify();
        verify(documentService, never()).replaceDocumentContent(any(), any(), any());
    }

    @Test
    void wormModeAllowsOnlyNewDocuments() throws IOException {
        ReflectionTestUtils.setField(service, "wormMode", true);
        Document d = doc("d.pdf", "application/pdf", 10);
        stubDownload(d, threePages);
        when(engine.inspect(any())).thenReturn(inspection(3, false, false));
        stubCompose();
        StepVerifier.create(service.rotate(new RotateRequest(List.of(d.getId()), 90, null, null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(PdfToolsException.class)
                        .hasMessageContaining(PdfToolsException.WORM_MODE))
                .verify();

        UUID newId = UUID.randomUUID();
        stubUpload(newId);
        OutputTarget asNew = new OutputTarget(OutputMode.NEW_DOCUMENT, null, null, null, null);
        StepVerifier.create(service.rotate(new RotateRequest(List.of(d.getId()), 90, null, asNew)))
                .assertNext(r -> assertThat(r.outputs().getFirst().documentId()).isEqualTo(newId))
                .verifyComplete();
    }

    // ── routing, naming, provenance ─────────────────────────────────────────

    @Test
    void mergeWritesANewDocumentNextToTheFirstSourceAndAuditsProvenance() throws IOException {
        Document a = doc("Contract.pdf", "application/pdf", 10);
        Document b = doc("Annex.pdf", "application/pdf", 10);
        stubDownload(a, threePages);
        stubDownload(b, threePages);
        when(engine.inspect(any())).thenReturn(inspection(3, false, false));
        stubCompose();
        UUID newId = UUID.randomUUID();
        stubUpload(newId);

        MergeRequest request = new MergeRequest(List.of(new MergeSource(a.getId(), "1-2"), new MergeSource(b.getId(), "3")), true, null);
        StepVerifier.create(service.merge(request))
                .assertNext(r -> {
                    assertThat(r.operation()).isEqualTo("merge");
                    assertThat(r.outputs().getFirst().documentId()).isEqualTo(newId);
                    assertThat(r.outputs().getFirst().name()).isEqualTo("Contract (merged).pdf");
                    assertThat(r.outputs().getFirst().pageCount()).isEqualTo(3);
                })
                .verifyComplete();

        ArgumentCaptor<FilePart> part = ArgumentCaptor.forClass(FilePart.class);
        verify(documentService).uploadDocument(part.capture(), anyLong(), eq(a.getParentId()), isNull(), eq(false));
        assertThat(part.getValue().filename()).isEqualTo("Contract (merged).pdf");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PageRef>> refs = ArgumentCaptor.forClass(List.class);
        verify(engine).compose(refs.capture(), any(), eq("Contract (merged)"), any());
        assertThat(refs.getValue()).extracting(PageRef::page).containsExactly(1, 2, 3);

        ArgumentCaptor<IAuditLogDetails> details = ArgumentCaptor.forClass(IAuditLogDetails.class);
        verify(auditService).logAction(eq(AuditAction.PDF_TRANSFORM), eq(DocumentType.FILE), eq(newId), details.capture());
        PdfTransformAudit audit = (PdfTransformAudit) details.getValue();
        assertThat(audit.getOperation()).isEqualTo("merge");
        assertThat(audit.getSourceDocumentIds()).containsExactly(a.getId(), b.getId());
        assertThat(audit.getPageCount()).isEqualTo(3);
        assertThat(audit.getFilename()).isEqualTo("Contract (merged).pdf");
    }

    @Test
    void mergeHonoursExplicitNameFolderAndDuplicates() throws IOException {
        Document a = doc("a.pdf", "application/pdf", 10);
        stubDownload(a, threePages);
        when(engine.inspect(any())).thenReturn(inspection(3, false, false));
        stubCompose();
        UUID folder = UUID.randomUUID();
        stubUpload(UUID.randomUUID());
        OutputTarget target = new OutputTarget(null, folder, "  bundle/2026:final  ", true, null);
        StepVerifier.create(service.merge(new MergeRequest(List.of(new MergeSource(a.getId(), null)), null, target)))
                .assertNext(r -> assertThat(r.outputs().getFirst().name()).isEqualTo("bundle 2026 final.pdf"))
                .verifyComplete();
        verify(documentService).uploadDocument(any(FilePart.class), anyLong(), eq(folder), isNull(), eq(true));
    }

    @Test
    void splitNamesPartsFromThePattern() throws IOException {
        Document d = doc("report.pdf", "application/pdf", 10);
        stubDownload(d, threePages);
        when(engine.inspect(any())).thenReturn(inspection(3, false, false));
        stubCompose();
        stubUpload(UUID.randomUUID());
        SplitOutput output = new SplitOutput(null, "{name} p{first}-{last} ({index})", null, null);
        StepVerifier.create(service.split(new SplitRequest(d.getId(), SplitMode.EVERY_N_PAGES, 2, null, null, null, output)))
                .assertNext(r -> assertThat(r.outputs()).extracting(o -> o.name())
                        .containsExactly("report p1-2 (1).pdf", "report p3-3 (2).pdf"))
                .verifyComplete();
    }

    @Test
    void splitAtPagesAndRangesAndOutline() throws IOException {
        Document d = doc("book.pdf", "application/pdf", 10);
        stubDownload(d, threePages);
        List<PdfPageInfo> pages = List.of(new PdfPageInfo(1, 1, 1, 0), new PdfPageInfo(2, 1, 1, 0), new PdfPageInfo(3, 1, 1, 0));
        when(engine.inspect(any())).thenReturn(new Inspection(3, pages, false, false,
                List.of(new org.openfilz.dms.dto.response.pdf.PdfOutlineEntry("Chapter 1", 2, 1),
                        new org.openfilz.dms.dto.response.pdf.PdfOutlineEntry("Section", 3, 2))));
        stubCompose();
        stubUpload(UUID.randomUUID());

        StepVerifier.create(service.split(new SplitRequest(d.getId(), SplitMode.AT_PAGES, null, List.of(3, 2), null, null, null)))
                .assertNext(r -> assertThat(r.outputs()).hasSize(3))
                .verifyComplete();
        StepVerifier.create(service.split(new SplitRequest(d.getId(), SplitMode.PAGE_RANGES, null, null, List.of("1-2", "3"), null, null)))
                .assertNext(r -> assertThat(r.outputs()).hasSize(2))
                .verifyComplete();
        SplitOutput titled = new SplitOutput(null, "{title}", null, null);
        StepVerifier.create(service.split(new SplitRequest(d.getId(), SplitMode.BY_OUTLINE_LEVEL, null, null, null, 1, titled)))
                .assertNext(r -> assertThat(r.outputs()).extracting(o -> o.name()).containsExactly("1.pdf", "Chapter 1.pdf"))
                .verifyComplete();
        StepVerifier.create(service.split(new SplitRequest(d.getId(), SplitMode.BY_OUTLINE_LEVEL, null, null, null, 2, titled)))
                .assertNext(r -> assertThat(r.outputs()).extracting(o -> o.name()).containsExactly("1.pdf", "Chapter 1.pdf", "Section.pdf"))
                .verifyComplete();
    }

    @Test
    void splitWithoutBookmarksIsRefused() throws IOException {
        Document d = doc("plain.pdf", "application/pdf", 10);
        stubDownload(d, threePages);
        when(engine.inspect(any())).thenReturn(inspection(3, false, false));
        StepVerifier.create(service.split(new SplitRequest(d.getId(), SplitMode.BY_OUTLINE_LEVEL, null, null, null, null, null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(PdfToolsException.class)
                        .hasMessageContaining(PdfToolsException.PDF_NO_OUTLINE))
                .verify();
    }

    @Test
    void invalidArgumentsAreBadRequests() {
        StepVerifier.create(service.merge(new MergeRequest(List.of(), null, null))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(service.organize(new OrganizeRequest(UUID.randomUUID(), List.of(), null))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(service.organize(new OrganizeRequest(UUID.randomUUID(), List.of(new PageInstruction(null, 1, 45)), null)))
                .expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(service.rotate(new RotateRequest(List.of(UUID.randomUUID()), 45, null, null))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(service.rotate(new RotateRequest(List.of(UUID.randomUUID()), 360, null, null))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(service.rotate(new RotateRequest(List.of(), 90, null, null))).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    void namingHelpers() {
        assertThat(PdfToolsServiceImpl.outputName(null, "x")).isEqualTo("x.pdf");
        assertThat(PdfToolsServiceImpl.outputName("Report.PDF", "x")).isEqualTo("Report.PDF");
        assertThat(PdfToolsServiceImpl.outputName("a/b\\c:d", "x")).isEqualTo("a b c d.pdf");
        assertThat(PdfToolsServiceImpl.outputName("..", "x")).isEqualTo("document.pdf");
        assertThat(PdfToolsServiceImpl.stripExtension("a.b.pdf")).isEqualTo("a.b");
        assertThat(PdfToolsServiceImpl.stripExtension("noext")).isEqualTo("noext");
        assertThat(PdfToolsServiceImpl.sanitizeName("x".repeat(300))).hasSize(200);
        PdfOperationResponse unused = null;
        assertThat(unused).isNull();
    }
}
