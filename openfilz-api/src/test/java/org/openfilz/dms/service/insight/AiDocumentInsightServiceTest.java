package org.openfilz.dms.service.insight;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.config.AiProperties.Insights.Classifier.Mode;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.IndexService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.ai.AiFallbackChain;
import org.openfilz.dms.service.ai.UserChatClientResolver;
import org.openfilz.dms.service.ai.UserChatClientResolver.ResolvedChat;
import org.openfilz.dms.service.impl.TikaService;
import org.openfilz.dms.service.insight.CategoryClassifier.CategoryPrediction;
import org.openfilz.dms.service.insight.InsightsPolicy.Verdict;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@link InsightsPolicy} seam in front of the enrichment: a denied document gets a SKIPPED row,
 * a document the model may not read is classified locally or left alone, a kind the policy keeps
 * away from the model is screened by the classifier, and the permit-all policy changes nothing.
 * The worker is driven through {@code process} directly, without the queue.
 */
class AiDocumentInsightServiceTest {

    private static final String MODEL_ANSWER = """
            {"category": "invoice", "summary": "Invoice F-2026-0042 from Globex.", "keywords": ["invoice", "globex"],
             "language": "en", "entities": {"supplier": "Globex", "invoice_number": "F-2026-0042"}}""";

    private final AiProperties aiProperties = new AiProperties();
    private final DocumentInsightStore store = mock(DocumentInsightStore.class);
    private final UserChatClientResolver resolver = mock(UserChatClientResolver.class);
    private final AiFallbackChain fallbackChain = mock(AiFallbackChain.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CategoryTaxonomy taxonomy = PropertiesCategoryTaxonomy.of(List.of("invoice", "contract", "id-document", "other"), null);
    private final AtomicInteger modelCalls = new AtomicInteger();

    private CategoryClassifier classifier;
    private Verdict verdict = Verdict.permitAll();
    private AiDocumentInsightService service;

    private final Document document = Document.builder()
            .id(UUID.randomUUID()).name("invoice-0042.pdf").type(DocumentType.FILE).contentType("application/pdf")
            .size(1234L).active(true).storagePath("x").build();

    @BeforeEach
    void wire() {
        when(store.markPending(any())).thenReturn(Mono.empty());
        when(store.markSkipped(any(), any())).thenReturn(Mono.empty());
        when(store.markFailed(any(), any())).thenReturn(Mono.empty());
        when(store.saveEnrichment(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(Mono.empty());
        when(documentRepository.findById(document.getId())).thenReturn(Mono.just(document));
        // The insights model: no dedicated one, so the chat model answers; every call is counted
        ResolvedChat chat = new ResolvedChat(mock(ChatModel.class), "openai", "gpt-test");
        when(fallbackChain.configuredModel(any())).thenReturn(Optional.empty());
        when(resolver.resolve(any())).thenReturn(Mono.just(chat));
        when(fallbackChain.callWithFailover(any(), anyString(), any())).thenAnswer(invocation -> {
            modelCalls.incrementAndGet();
            return MODEL_ANSWER;
        });
    }

    @AfterEach
    void stop() {
        if (service != null) {
            service.stop();
        }
    }

    /** A classifier that always answers {@code category}, or none. */
    private void classifier(String category) {
        classifier = category == null ? null : new CategoryClassifier() {
            @Override
            public String name() {
                return "prototype:test";
            }

            @Override
            public CategoryPrediction classify(UUID documentId, String fileName, String text) {
                return new CategoryPrediction(category, 0.9, List.of());
            }
        };
    }

    @SuppressWarnings("unchecked")
    private void start(Mode mode) {
        aiProperties.getInsights().getClassifier().setMode(mode);
        ObjectProvider<IndexService> noIndex = mock(ObjectProvider.class);
        ObjectProvider<CategoryClassifier> classifiers = mock(ObjectProvider.class);
        when(classifiers.getIfAvailable()).thenAnswer(i -> classifier);
        InsightsPolicy policy = new InsightsPolicy() {
            @Override
            public Mono<Verdict> forDocument(Document d) {
                return Mono.just(verdict);
            }
        };
        service = new AiDocumentInsightService(aiProperties, store, resolver, fallbackChain, documentRepository,
                mock(StorageService.class), mock(TikaService.class), noIndex, events, new InsightCompletionSignal(),
                classifiers, policy, taxonomy);
        service.start();
    }

    private void process() {
        service.process(new AiDocumentInsightService.Task(document.getId(), "Invoice F-2026-0042 from Globex, total due 1 200 EUR.", null))
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("enrichment denied by the policy: a SKIPPED row with the reason, no model call, no pending mark")
    void deniedIsSkipped() {
        verdict = new Verdict(false, false, Set.of(), "tier 2 is off for this tenant");
        classifier("invoice");
        start(Mode.LLM);

        process();

        verify(store).markSkipped(document.getId(), "disabled by policy: tier 2 is off for this tenant");
        verify(store, never()).markPending(any());
        verify(store, never()).saveEnrichment(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(modelCalls).hasValue(0);
    }

    @Test
    @DisplayName("model barred, classifier present: a category-only row under the classifier's name, whatever the mode")
    void modelBarredClassifiesLocally() {
        verdict = new Verdict(true, false, Set.of(), "third-party models are off");
        classifier("contract");
        start(Mode.LLM);

        process();

        verify(store).saveEnrichment(eq(document.getId()),
                argThat(result -> "contract".equals(result.category()) && result.summary() == null && result.keywords().isEmpty()),
                eq("prototype:test"), eq(AiDocumentInsightService.PROMPT_VERSION));
        verify(store, never()).markSkipped(any(), any());
        assertThat(modelCalls).hasValue(0);
    }

    @Test
    @DisplayName("model barred, no classifier: SKIPPED — the text never reaches a model as a fallback")
    void modelBarredWithoutClassifierIsSkipped() {
        verdict = new Verdict(true, false, Set.of(), null);
        classifier(null);
        start(Mode.LLM);

        process();

        verify(store).markSkipped(eq(document.getId()), startsWith("the model may not be used for this document"));
        verify(store, never()).saveEnrichment(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(modelCalls).hasValue(0);
    }

    @Test
    @DisplayName("a kind the policy keeps away from the model is screened by the classifier: row model policy:<classifier>")
    void blockedCategoryIsScreened() {
        verdict = new Verdict(true, true, Set.of("ID Document"), "identity documents stay on premises");
        classifier("id-document");
        start(Mode.LLM);

        process();

        verify(store).saveEnrichment(eq(document.getId()),
                argThat(result -> "id-document".equals(result.category()) && result.summary() == null),
                eq(InsightsPolicy.SCREENED_MODEL_PREFIX + "prototype:test"), eq(AiDocumentInsightService.PROMPT_VERSION));
        assertThat(modelCalls).hasValue(0);
    }

    @Test
    @DisplayName("screening lets an unblocked kind through to the model, which then answers in full")
    void unblockedKindReachesTheModel() {
        verdict = new Verdict(true, true, Set.of("id-document"), null);
        classifier("invoice");
        start(Mode.LLM);

        process();

        verify(store).saveEnrichment(eq(document.getId()),
                argThat(result -> "invoice".equals(result.category()) && result.summary() != null
                        && result.entities().containsKey("supplier")),
                eq("openai:gpt-test"), eq(AiDocumentInsightService.PROMPT_VERSION));
        assertThat(modelCalls).hasValue(1);
    }

    @Test
    @DisplayName("the user set the kind while the model was working: the row keeps it, and the event follows the row")
    void userKindSetMeanwhileWins() {
        verdict = Verdict.permitAll();
        classifier(null);
        // The model answers "invoice"; the store kept the user's "contract" (its RETURNING category)
        when(store.saveEnrichment(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(Mono.just("contract"));
        start(Mode.LLM);

        process();

        verify(events).publishEvent(argThat((Object event) -> event instanceof org.openfilz.dms.event.DocumentInsightsReadyEvent ready
                && "contract".equals(ready.category()) && "Invoice F-2026-0042 from Globex.".equals(ready.summary())));
        assertThat(modelCalls).hasValue(1);
    }

    @Test
    @DisplayName("permit-all: the model enriches as before — full row under provider:model")
    void permitAllIsUnchanged() {
        verdict = Verdict.permitAll();
        classifier(null);
        start(Mode.LLM);

        process();

        verify(store).markPending(document.getId());
        verify(store).saveEnrichment(eq(document.getId()),
                argThat(result -> "invoice".equals(result.category()) && "en".equals(result.language())
                        && result.keywords().contains("globex")),
                eq("openai:gpt-test"), eq(AiDocumentInsightService.PROMPT_VERSION));
        verify(store, never()).markSkipped(any(), any());
        assertThat(modelCalls).hasValue(1);
    }

    @Test
    @DisplayName("a policy that fails to answer fails closed: SKIPPED, never enriched on the assumption that all is allowed")
    void failingPolicyFailsClosed() {
        classifier("invoice");
        aiProperties.getInsights().getClassifier().setMode(Mode.LLM);
        @SuppressWarnings("unchecked")
        ObjectProvider<IndexService> noIndex = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CategoryClassifier> classifiers = mock(ObjectProvider.class);
        when(classifiers.getIfAvailable()).thenReturn(classifier);
        InsightsPolicy broken = new InsightsPolicy() {
            @Override
            public Mono<Verdict> forDocument(Document d) {
                return Mono.error(new IllegalStateException("policy store unreachable"));
            }
        };
        service = new AiDocumentInsightService(aiProperties, store, resolver, fallbackChain, documentRepository,
                mock(StorageService.class), mock(TikaService.class), noIndex, events, new InsightCompletionSignal(),
                classifiers, broken, taxonomy);
        service.start();

        process();

        verify(store).markSkipped(eq(document.getId()), startsWith("disabled by policy: policy lookup failed"));
        assertThat(modelCalls).hasValue(0);
    }

    // ── auto with no model: the local verdict stands ─────────────────────────

    /** A classifier that answers {@code category} at {@code confidence}. */
    private void classifier(String category, double confidence) {
        classifier = new CategoryClassifier() {
            @Override
            public String name() {
                return "learned:test";
            }

            @Override
            public CategoryPrediction classify(UUID documentId, String fileName, String text) {
                return new CategoryPrediction(category, confidence, List.of());
            }
        };
    }

    @Test
    @DisplayName("auto, no model anywhere, unsure verdict: the category is stored (category only), the row is not FAILED; "
            + "once a model appears, the next unsure document goes to it")
    void autoWithoutModelKeepsTheLocalVerdict() {
        aiProperties.getInsights().getClassifier().setMinConfidence(0.8);
        classifier("contract", 0.3);
        when(resolver.defaultModelVersion()).thenReturn(1L);
        when(resolver.resolve(any())).thenReturn(Mono.error(
                new org.openfilz.dms.exception.AiNoModelException("no model", false)));
        start(Mode.AUTO);

        process();

        verify(store).saveEnrichment(eq(document.getId()),
                argThat(result -> "contract".equals(result.category()) && result.summary() == null),
                eq("learned:test"), eq(AiDocumentInsightService.PROMPT_VERSION));
        verify(store, never()).markFailed(any(), any());
        assertThat(modelCalls).hasValue(0);

        // An organisation or deployment model is set: the source version moves
        when(resolver.defaultModelVersion()).thenReturn(2L);
        when(resolver.resolve(any())).thenReturn(Mono.just(new ResolvedChat(mock(ChatModel.class), "openai", "gpt-test")));

        process();

        verify(store).saveEnrichment(eq(document.getId()),
                argThat(result -> "invoice".equals(result.category()) && result.summary() != null),
                eq("openai:gpt-test"), eq(AiDocumentInsightService.PROMPT_VERSION));
        assertThat(modelCalls).hasValue(1);
        verify(store, never()).markFailed(any(), any());
    }

    @Test
    @DisplayName("auto, an empty resolution is the same 'no model': the local verdict is kept")
    void autoWithEmptyResolutionKeepsTheLocalVerdict() {
        aiProperties.getInsights().getClassifier().setMinConfidence(0.8);
        classifier("contract", 0.3);
        when(resolver.resolve(any())).thenReturn(Mono.empty());
        start(Mode.AUTO);

        process();

        verify(store).saveEnrichment(eq(document.getId()), argThat(result -> "contract".equals(result.category())),
                eq("learned:test"), eq(AiDocumentInsightService.PROMPT_VERSION));
        verify(store, never()).markFailed(any(), any());
    }

    @Test
    @DisplayName("auto, a provider error (not 'no model'): the row still ends FAILED")
    void autoProviderErrorStillFails() {
        aiProperties.getInsights().getClassifier().setMinConfidence(0.8);
        classifier("contract", 0.3);
        when(fallbackChain.callWithFailover(any(), anyString(), any())).thenThrow(new RuntimeException("HTTP 429 quota exceeded"));
        start(Mode.AUTO);

        process();

        verify(store).markFailed(eq(document.getId()), any());
        verify(store, never()).saveEnrichment(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("llm mode with no model: FAILED as before (no local verdict to fall back on)")
    void llmWithoutModelStillFails() {
        classifier(null);
        when(resolver.resolve(any())).thenReturn(Mono.error(
                new org.openfilz.dms.exception.AiNoModelException("no model", false)));
        start(Mode.LLM);

        process();

        verify(store).markFailed(eq(document.getId()), any());
        verify(store, never()).saveEnrichment(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("the mode is read per document: learned at start, auto later sends the unsure document to the model")
    void modeChangeAppliesAtTheNextDocument() {
        aiProperties.getInsights().getClassifier().setMinConfidence(0.8);
        classifier("contract", 0.3);
        start(Mode.LEARNED);

        process();
        assertThat(modelCalls).hasValue(0);

        aiProperties.getInsights().getClassifier().setMode(Mode.AUTO);
        process();
        assertThat(modelCalls).hasValue(1);
    }

    // ── workers, model permits and the daily limit ───────────────────────────

    /** A file the repository knows, whose name tells the test classifier what to answer. */
    private Document file(String name) {
        Document file = Document.builder().id(UUID.randomUUID()).name(name).type(DocumentType.FILE)
                .contentType("application/pdf").size(1234L).active(true).storagePath("x").build();
        when(documentRepository.findById(file.getId())).thenReturn(Mono.just(file));
        return file;
    }

    private static void await(java.util.concurrent.CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, java.util.concurrent.TimeUnit.SECONDS)).as("reached within 10 s").isTrue();
    }

    @Test
    @DisplayName("a worker held by a model call does not stop the queue: the other workers keep receiving documents")
    void aWorkerOnTheModelDoesNotHoldBackTheQueue() throws Exception {
        aiProperties.getInsights().setLocalConcurrency(3);
        aiProperties.getInsights().setConcurrency(1);
        aiProperties.getInsights().getClassifier().setMinConfidence(0.8);
        java.util.concurrent.CountDownLatch slowEntered = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch slowGate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch modelEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch modelGate = new java.util.concurrent.CountDownLatch(1);
        classifier = new CategoryClassifier() {
            @Override
            public String name() {
                return "learned:test";
            }

            @Override
            public CategoryPrediction classify(UUID documentId, String fileName, String text) {
                if (fileName.startsWith("slow")) {
                    slowEntered.countDown();
                    try {
                        slowGate.await(20, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new CategoryPrediction("contract", fileName.startsWith("unsure") ? 0.3 : 0.9, List.of());
            }
        };
        when(fallbackChain.callWithFailover(any(), anyString(), any())).thenAnswer(invocation -> {
            modelEntered.countDown();
            modelGate.await(20, java.util.concurrent.TimeUnit.SECONDS);
            return MODEL_ANSWER;
        });
        start(Mode.AUTO);
        try {
            // Two workers busy on a local classification, the third one on a model call
            service.enqueue(file("slow-1.pdf"), "some text");
            service.enqueue(file("slow-2.pdf"), "some text");
            await(slowEntered);
            service.enqueue(file("unsure.pdf"), "some text");
            await(modelEntered);

            // The two local ones finish: their workers are free, the model call still runs
            slowGate.countDown();
            Document next = file("sure-1.pdf");
            Document last = file("sure-2.pdf");
            service.enqueue(next, "some text");
            service.enqueue(last, "some text");

            verify(store, org.mockito.Mockito.timeout(5000)).saveEnrichment(eq(next.getId()), any(), eq("learned:test"),
                    org.mockito.ArgumentMatchers.anyInt());
            verify(store, org.mockito.Mockito.timeout(5000)).saveEnrichment(eq(last.getId()), any(), eq("learned:test"),
                    org.mockito.ArgumentMatchers.anyInt());
        } finally {
            slowGate.countDown();
            modelGate.countDown();
        }
    }

    @Test
    @DisplayName("llm: a document that passed the daily limit while waiting for a model permit is checked again under it")
    void dailyLimitIsCheckedAgainUnderTheModelPermit() throws Exception {
        aiProperties.getInsights().setConcurrency(1);
        aiProperties.getInsights().setDailyLimit(1);
        classifier(null);
        java.util.concurrent.CountDownLatch modelEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch modelGate = new java.util.concurrent.CountDownLatch(1);
        // Its own counter: stubbing again runs the answer wired for every test once
        AtomicInteger calls = new AtomicInteger();
        when(fallbackChain.callWithFailover(any(), anyString(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            modelEntered.countDown();
            modelGate.await(20, java.util.concurrent.TimeUnit.SECONDS);
            return MODEL_ANSWER;
        });
        start(Mode.LLM);
        Document first = file("first.pdf");
        Document second = file("second.pdf");
        try {
            // Both pass the first check (nothing counted yet); the second one waits for the only permit
            service.process(new AiDocumentInsightService.Task(first.getId(), "some text", null)).subscribe();
            await(modelEntered);
            service.process(new AiDocumentInsightService.Task(second.getId(), "some text", null)).subscribe();
            modelGate.countDown();

            verify(store, org.mockito.Mockito.timeout(5000)).saveEnrichment(eq(first.getId()), any(), eq("openai:gpt-test"),
                    org.mockito.ArgumentMatchers.anyInt());
            verify(store, org.mockito.Mockito.timeout(5000)).markSkipped(second.getId(), "daily enrichment limit reached");
            assertThat(calls).as("the limit is one model call").hasValue(1);
            verify(store, never()).markFailed(any(), any());
        } finally {
            modelGate.countDown();
        }
    }

    @Test
    @DisplayName("auto: the daily limit spent while waiting for a model permit keeps the local verdict, without a call")
    void autoKeepsTheLocalVerdictWhenTheLimitIsSpentUnderThePermit() throws Exception {
        aiProperties.getInsights().setConcurrency(1);
        aiProperties.getInsights().setDailyLimit(1);
        aiProperties.getInsights().getClassifier().setMinConfidence(0.8);
        classifier("contract", 0.3);
        java.util.concurrent.CountDownLatch modelEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch modelGate = new java.util.concurrent.CountDownLatch(1);
        // Its own counter: stubbing again runs the answer wired for every test once
        AtomicInteger calls = new AtomicInteger();
        when(fallbackChain.callWithFailover(any(), anyString(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            modelEntered.countDown();
            modelGate.await(20, java.util.concurrent.TimeUnit.SECONDS);
            return MODEL_ANSWER;
        });
        // The model is looked up right after the local verdict passed the limit, right before the permit
        java.util.concurrent.CountDownLatch secondPassedTheLimit = new java.util.concurrent.CountDownLatch(2);
        when(resolver.defaultModelVersion()).thenAnswer(invocation -> {
            secondPassedTheLimit.countDown();
            return 1L;
        });
        start(Mode.AUTO);
        Document first = file("first.pdf");
        Document second = file("second.pdf");
        try {
            service.process(new AiDocumentInsightService.Task(first.getId(), "some text", null)).subscribe();
            await(modelEntered);
            service.process(new AiDocumentInsightService.Task(second.getId(), "some text", null)).subscribe();
            await(secondPassedTheLimit);
            modelGate.countDown();

            verify(store, org.mockito.Mockito.timeout(5000)).saveEnrichment(eq(first.getId()), any(), eq("openai:gpt-test"),
                    org.mockito.ArgumentMatchers.anyInt());
            verify(store, org.mockito.Mockito.timeout(5000)).saveEnrichment(eq(second.getId()),
                    argThat(result -> "contract".equals(result.category()) && result.summary() == null),
                    eq("learned:test"), org.mockito.ArgumentMatchers.anyInt());
            assertThat(calls).as("the limit is one model call").hasValue(1);
            verify(store, never()).markFailed(any(), any());
        } finally {
            modelGate.countDown();
        }
    }

    // ── the cached model follows the runtime model source ───────────────────

    @Test
    @DisplayName("the default model is kept while the source version holds, resolved again when it moves")
    void modelIsResolvedAgainWhenTheSourceMoves() {
        start(Mode.LLM);
        ResolvedChat first = new ResolvedChat(mock(ChatModel.class), "openai", "gpt-first");
        ResolvedChat second = new ResolvedChat(mock(ChatModel.class), "google-genai", "gemini-org");
        when(resolver.defaultModelVersion()).thenReturn(1L);
        when(resolver.resolve(any())).thenReturn(Mono.just(first));
        assertThat(service.model()).isSameAs(first);
        when(resolver.resolve(any())).thenReturn(Mono.just(second));
        assertThat(service.model()).as("same version: the cached model").isSameAs(first);

        when(resolver.defaultModelVersion()).thenReturn(2L);
        assertThat(service.model()).as("the source moved").isSameAs(second);
    }

    @Test
    @DisplayName("a removed model is never called again: no default left = the call fails")
    void removedModelStopsAtOnce() {
        start(Mode.LLM);
        ResolvedChat organisation = new ResolvedChat(mock(ChatModel.class), "google-genai", "gemini-org");
        when(resolver.defaultModelVersion()).thenReturn(1L);
        when(resolver.resolve(any())).thenReturn(Mono.just(organisation));
        assertThat(service.model()).isSameAs(organisation);

        when(resolver.defaultModelVersion()).thenReturn(2L);
        when(resolver.resolve(any())).thenReturn(Mono.error(
                new org.openfilz.dms.exception.AiNoModelException("no model", false)));
        org.assertj.core.api.Assertions.assertThatThrownBy(service::model)
                .isInstanceOf(org.openfilz.dms.exception.AiNoModelException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(service::model)
                .as("still not the removed model on the next call")
                .isInstanceOf(org.openfilz.dms.exception.AiNoModelException.class);
    }

    @Test
    @DisplayName("an empty resolution is AiNoModelException too (one 'no model' shape)")
    void emptyResolutionIsNoModel() {
        start(Mode.LLM);
        when(resolver.resolve(any())).thenReturn(Mono.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(service::model)
                .isInstanceOf(org.openfilz.dms.exception.AiNoModelException.class);
    }

    @Test
    @DisplayName("openfilz.ai.insights.model wins and is not replaced by a runtime change")
    void dedicatedInsightsModelIsPinned() {
        start(Mode.LLM);
        ResolvedChat dedicated = new ResolvedChat(mock(ChatModel.class), "openai", "gpt-insights");
        when(fallbackChain.configuredModel(any())).thenReturn(Optional.of(dedicated));
        when(resolver.defaultModelVersion()).thenReturn(1L);
        assertThat(service.model()).isSameAs(dedicated);

        when(resolver.defaultModelVersion()).thenReturn(2L);
        when(fallbackChain.configuredModel(any())).thenReturn(Optional.empty());
        assertThat(service.model()).isSameAs(dedicated);
        verify(resolver, never()).resolve(any());
    }
}
