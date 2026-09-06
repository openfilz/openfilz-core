package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import org.openfilz.dms.enums.AiProvider;

import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for the AI document chat feature.
 * Maps to openfilz.ai.* properties in application.yml.
 * <p>
 * Deliberately NOT gated on {@code openfilz.ai.active}: in GraalVM native images bean
 * conditions are evaluated at build time, so the whole AI feature is toggled at runtime —
 * the beans always exist ({@code @Lazy} where their dependencies require the AI providers)
 * and the entry points consult {@link #isActive()} per request.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "openfilz.ai")
public class AiProperties {

    /**
     * Master runtime switch for the whole AI feature. Read at runtime (never as a bean
     * condition) so it stays toggleable in GraalVM native images.
     */
    private boolean active = false;

    /**
     * Longest answer accepted from the JSON-contract calls (tier-2 insights, smart filing stage 2),
     * passed as {@code maxTokens}. The contract itself fits in a few hundred tokens; the cap exists
     * because a small local model at temperature 0 otherwise loops on it until its context shifts
     * (22 000 tokens per document in the early Ollama trials). It cannot be tight, though: a
     * thinking model (Gemini) counts its thoughts against the same limit, and 512 left it a few
     * dozen visible tokens — every answer was truncated and rejected.
     */
    private int maxAnswerTokens = 4096;

    /**
     * The system prompt used by the AI assistant.
     */
    private String systemPrompt = """
            You are OpenFilz AI Assistant, a helpful document management assistant.
            You help users find, organize, and understand their documents stored in OpenFilz.
            You can search for documents, summarize content, reorganize folders, and answer questions about document contents.
            Always be concise and helpful. When performing actions, confirm what you did.
            If you are unsure about an action, ask the user to confirm before proceeding.
            Be efficient: never repeat a tool call you already made with the same arguments in this turn; plan the fewest steps needed.
            """;

    /**
     * Embedding configuration.
     */
    private EmbeddingConfig embedding = new EmbeddingConfig();

    /**
     * Automatic failover to another chat model when the configured one runs out of quota.
     */
    private Fallback fallback = new Fallback();

    /**
     * In-app chat assistant tuning (the MCP server is unaffected).
     */
    private Chat chat = new Chat();

    @Data
    public static class Chat {
        /**
         * Kill switch for the <em>in-app chat assistant only</em>. When false, {@code /api/v1/ai/chat**}
         * and the per-user BYOK settings answer 404 and the frontend hides the chat button and the
         * "Organise with AI" action ({@code Settings.aiChatActive}); everything else the AI feature
         * does — embeddings, semantic retrieval, document insights, smart filing, the by-kind
         * reorganisation and the MCP server — keeps working.
         * <p>
         * This is what makes a <em>light</em> deployment expressible: with the {@code prototype} or
         * {@code learned} category classifier nothing calls a chat model, so a deployment can turn
         * the chat off, set {@code spring.ai.model.chat=none} and run classification and filing with
         * no LLM at all (see {@code docs/ai-overview.md} §6). Read at runtime, never a bean condition.
         */
        private boolean active = true;

        /**
         * Simple class names of {@code McpToolContributor}s whose tools the chat assistant must
         * NOT bind even though they opt in (e.g. {@code OrganizeAiToolsContributor},
         * {@code SignatureAiToolsContributor}, {@code PdfAiToolsContributor}). Small local models
         * degrade as the tool schema grows — they start refusing or emit tool calls as text — so a
         * deployment on such a model can trim the surface to what it uses. Read per request.
         */
        private List<String> excludedContributors = new ArrayList<>();

        /**
         * Names of single tools the chat assistant must NOT bind (e.g. {@code getDocumentActivity}),
         * the finer-grained twin of {@link #excludedContributors} for tools that live in
         * {@code DocumentAiTools}. The MCP server is not affected. Read per request.
         */
        private List<String> excludedTools = new ArrayList<>();
    }

    /**
     * Reorganisation tools ({@code planReorganization}): inventory cache and per-user rate cap.
     * See {@code ReorganizationInventoryCache}.
     */
    private Reorganization reorganization = new Reorganization();

    /** Tier-2 document insights (AI-derived category / summary / entities at ingestion). */
    private Insights insights = new Insights();

    /** Smart filing on upload: OpenFilz chooses the destination folder on the user's request. */
    private AutoFile autoFile = new AutoFile();

    @Data
    public static class AutoFile {
        /** Master switch; needs {@code openfilz.ai.active} and embeddings. */
        private boolean active = false;
        /** Initial value of the per-user switch. */
        private boolean defaultForUsers = false;
        /** Deployment ceiling for the per-user "may create folders" option. */
        private boolean allowNewFolders = true;
        /** Documents one upload batch may file; the rest stay put. */
        private int maxPerBatch = 200;
        /** Concurrent filings. */
        private int concurrency = 2;
        /** Nearest documents consulted by the neighbour vote. */
        private int neighbourTopK = 20;
        /** The leading folder must hold this share of the neighbours' similarity weight. */
        private double neighbourMinShare = 0.6;
        /** ...and its best neighbour must be at least this similar. */
        private double neighbourMinSimilarity = 0.5;
        /**
         * Only neighbours at least this fraction as similar as the best hit vote: the long tail of
         * "everything scores 0.6" an embedding model returns for unrelated documents falls away.
         */
        private double neighbourMinRelativeSimilarity = 0.85;
        /**
         * A winning folder must be a home for the document's kind: among its files with a known
         * category the dominant one must be the document's and hold this share of them, else the
         * vote is discarded and the model decides — a mixed folder never wins stage 1.
         */
        private double neighbourMinFolderPurity = 0.7;
        /**
         * How a winning folder is judged a home for the document: by the tier-2 categories of its
         * files ({@code CATEGORY}), by their similarity to the document ({@code SIMILARITY} — no
         * category needed, one filtered vector query), or both ({@code BOTH}). Default {@code CATEGORY}:
         * on the reference corpus the category guards filed 92 % of the documents right and none into
         * a grab-bag, while the similarity judgement abstained far more often (see the docs).
         */
        private Coherence coherence = Coherence.CATEGORY;
        /**
         * Similarity coherence: the members' similarities to the document form one cluster (a home)
         * unless two consecutive values, sorted, are further apart than this — then the share above
         * the gap is the folder's purity for the document.
         */
        private double folderSimilarityGap = 0.1;
        /** A folder with fewer known members than this is not judged (a young folder passes). */
        private int folderMinMembers = 3;
        /**
         * How stage 1 picks the folder: the neighbour vote ({@code VOTE}, default — folders ranked by
         * their neighbours' similarity weight) or the fit ({@code FIT} — the voted folders re-ranked by
         * purity × the mean similarity of their close members, so a small tight folder of the same
         * kind beats a large mixed one).
         */
        private Stage1 stage1 = Stage1.VOTE;
        /**
         * The rule stage between the vote and the model: a document of a known kind with no home
         * among its neighbours goes to the scope's folder for that kind ({@code Invoices},
         * {@code Factures}…) — found by name in any language, or created, named in the language of
         * the existing folder names — without asking a model. Off: straight to the model.
         */
        private boolean ruleFolders = true;
        /** The language of a rule-created folder when neither the existing folder names nor the document tell. */
        private String defaultLanguage = "en";
        /** Folder names per language and kind on top of the built-in table: {@code folder-names.fr.invoice: Factures}. */
        private Map<String, Map<String, String>> folderNames = new LinkedHashMap<>();
        /** Minimum model confidence to move into an existing folder. */
        private double llmMinConfidence = 0.7;
        /** Minimum model confidence to create a new folder. */
        private double newFolderMinConfidence = 0.85;
        /** New folders may be at most this many levels below an existing one. */
        private int newFolderMaxDepth = 2;
        /** How long a filing waits for the document's tier-2 insight before deciding without it. */
        private Duration waitForInsights = Duration.ofSeconds(30);

        public enum Coherence { CATEGORY, SIMILARITY, BOTH }

        public enum Stage1 { VOTE, FIT }
    }

    @Data
    public static class Insights {
        /** Runtime toggle of the enrichment; needs {@code openfilz.ai.active} too. */
        private boolean active = false;
        /** {@code provider:model} for the enrichment (e.g. {@code anthropic:claude-haiku-4-5}); empty = the chat model. */
        private String model = "";
        /** Characters of text sent per document. */
        private int maxChars = 6000;
        /** Files larger than this are not enriched (tier 1 is still written). */
        private DataSize maxFileSize = DataSize.ofMegabytes(50);
        /** Concurrent model calls. */
        private int concurrency = 2;
        /** Files enriched per day; beyond it rows are SKIPPED and a later backfill picks them up. Zero disables the cap. */
        private int dailyLimit = 2000;
        /** The closed category list the model must pick from ({@code other} is always accepted). */
        private List<String> categories = new ArrayList<>(List.of(
                "invoice", "quote", "contract", "report", "letter", "cv", "presentation", "spreadsheet",
                "form", "id-document", "receipt", "minutes", "specification", "manual", "other"));
        /** Who names the category: the chat model, the prototype classifier, or the classifier first and the model when unsure. */
        private Classifier classifier = new Classifier();

        @Data
        public static class Classifier {
            /** How the tier-2 category is produced. */
            public enum Mode {
                /** The chat model answers the full insight (category, summary, keywords, language, entities). */
                LLM,
                /** The prototype classifier alone: category only, no model call, no summary or entities. */
                PROTOTYPE,
                /**
                 * The library's own labelled documents classify (the nearest ones vote), the prototype
                 * descriptions as the cold start; no model.
                 */
                LEARNED,
                /** The learned classifier first (descriptions as cold start); the model only when its confidence is below {@code min-confidence}. */
                AUTO
            }

            private Mode mode = Mode.LLM;
            /** In {@code auto} mode, a prototype verdict at or above this confidence is kept without asking the model. */
            private double minConfidence = 0.5;
            /** Softmax temperature over the cosine similarities: lower = sharper confidences. */
            private double temperature = 0.02;
            /** Best similarity below which no category fits and the answer is {@code other}; zero disables the floor. */
            private double minSimilarity = 0.0;
            /** Characters of the document head embedded for the classification. */
            private int maxChars = 2000;
            /** Task prefix prepended to prototypes and documents alike (nomic models expect {@code "classification: "}). */
            private String prefix = "";
            /** Prototype description per category, overriding the built-in one for that key. */
            private Map<String, String> prototypes = new LinkedHashMap<>();
            /** The learned classifier ({@code learned} and {@code auto} modes). */
            private Learned learned = new Learned();

            @Data
            public static class Learned {
                /** Nearest labelled documents that vote. */
                private int k = 5;
                /** Fewer labelled neighbours than this: the cold-start classifier answers. */
                private int minNeighbours = 3;
                /** Neighbours below this similarity do not vote. */
                private double minSimilarity = 0.5;
                /** The winning share of the vote below which the cold-start classifier answers. */
                private double minConfidence = 0.5;
                /** Whose labels teach: the model's and the user's by default, never the classifier's own or the descriptions'. */
                private java.util.List<org.openfilz.dms.service.insight.LearnedCategoryClassifier.Source> learnFrom =
                        new ArrayList<>(java.util.List.of(org.openfilz.dms.service.insight.LearnedCategoryClassifier.Source.MODEL,
                                org.openfilz.dms.service.insight.LearnedCategoryClassifier.Source.USER));
            }
        }
    }

    @Data
    public static class Reorganization {
        /**
         * How long a produced inventory is served again for the same user and request shape. It
         * is dropped earlier as soon as one of the user's tool calls mutates the library. Zero
         * disables the cache.
         */
        private Duration inventoryCacheTtl = Duration.ofMinutes(2);
        /** Inventories a user may produce per {@link #planRateWindow}; cached hits do not count. Zero disables the cap. */
        private int planRateLimit = 20;
        private Duration planRateWindow = Duration.ofMinutes(10);
        /** By-kind split: a folder is judged only from this many categorised files. */
        private int splitMinFiles = 6;
        /** By-kind split: a kind gets its own sub-folder only from this many files. */
        private int splitMinGroup = 3;
        /** By-kind split: a folder whose dominant kind holds at least this share is left alone. */
        private double splitMinPurity = 0.7;
    }

    /**
     * Ollama provider switches.
     */
    private Provider ollama = new Provider();

    /** The in-process embedding provider (ONNX Runtime inside the API). */
    private Transformers transformers = new Transformers();

    @Data
    public static class Transformers {

        private Embedding embedding = new Embedding();

        @Data
        public static class Embedding {
            /** Serve embeddings from inside the API ({@code TRANSFORMERS_EMBEDDING_ENABLED}); wins over Ollama/OpenAI when several are enabled. */
            private boolean enabled = false;
            /** The label recorded in the embedding registry — change it when the model changes, never otherwise. */
            private String model = "nomic-embed-text-v1.5";
            /** The ONNX model file: a URL (fetched once into the cache), a {@code file:} path or a {@code classpath:} resource. */
            private String modelUri = "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/onnx/model_quantized.onnx";
            /** The Hugging Face {@code tokenizer.json} of the model. */
            private String tokenizerUri = "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/tokenizer.json";
            /** Where the downloaded model and tokenizer are kept between restarts (mount it in a container). */
            private String cacheDirectory = "";
            /** Cache the downloaded resources at all. */
            private boolean cacheEnabled = true;
            /** The ONNX output holding the token embeddings, mean-pooled into the vector. */
            private String modelOutputName = "last_hidden_state";
            /** ONNX Runtime CUDA device; -1 = CPU. */
            private int gpuDeviceId = -1;
        }
    }

    /**
     * OpenAI provider switches.
     */
    private Provider openai = new Provider();

    /**
     * Anthropic (Claude) provider switches. Chat only — Anthropic has no embeddings API,
     * so {@code anthropic.embedding.enabled} is ignored (embeddings resolve to Ollama/OpenAI).
     */
    private Provider anthropic = new Provider();

    /**
     * Google Gemini provider switches (GenAI / Gemini Developer API, API-key auth).
     * Chat only in OpenFilz: {@code google.embedding.enabled} is ignored — the pgvector
     * schema is pinned to the 768-dim output of the Ollama/OpenAI embedding models.
     */
    private Provider google = new Provider();

    /**
     * Per-provider switches deciding which Spring AI model auto-configuration is activated.
     * Consumed by {@link AiModelProviderEnvironmentPostProcessor}, which turns them into the
     * {@code spring.ai.model.chat} / {@code spring.ai.model.embedding} selectors Spring AI 2.0
     * gates its provider auto-configurations on.
     */
    @Data
    public static class Provider {

        private Toggle chat = new Toggle();

        private Toggle embedding = new Toggle();

        @Data
        public static class Toggle {
            /**
             * Whether this provider serves that kind of model.
             */
            private boolean enabled = false;
        }
    }

    /**
     * Chat-model failover: what to try when the configured model refuses to answer.
     * <p>
     * Aimed squarely at free provider tiers, whose per-minute and per-day allowances are small
     * enough to hit during normal use. When a chat call fails with an exhausted quota, a retired
     * model, or an unreachable provider, OpenFilz retries the same question on the next model in
     * {@link #chain} and benches the failed one for a cooldown so later requests skip it outright.
     * See {@code AiFallbackChain} and {@code AiFailoverPolicy}.
     * <p>
     * A credential failure never triggers failover — answering from a different model would hide
     * a broken API key instead of surfacing it.
     */
    @Data
    public static class Fallback {

        /** Master switch; off by default so existing deployments behave exactly as before. */
        private boolean enabled = false;

        /**
         * Models to fall back to, in order, as {@code provider:model} entries — for example
         * {@code google:gemini-3.6-flash,anthropic:claude-haiku-4-5,openai:gpt-4o-mini}.
         * Providers: {@code google}, {@code anthropic}, {@code openai}, {@code openai-compatible};
         * each needs its server API key configured, and entries without one are skipped with a
         * warning. The active chat model is always tried first and needs no entry here.
         */
        private List<String> chain = new ArrayList<>();

        /**
         * Additional API keys per provider, tried in order — the answer to a free tier whose
         * quota is charged <em>per key</em> rather than per model.
         * <p>
         * Once every {@link #chain} model for a provider is out of quota on the key in use, the
         * next key in that provider's pool takes over and those models are available again. Each
         * provider keeps its own pool, so a chain that mixes providers always reaches for the key
         * belonging to whichever provider it lands on.
         * <p>
         * Leave a provider's pool empty to keep using its single {@code spring.ai.*.api-key}.
         */
        private Map<AiProvider, List<String>> keys = new LinkedHashMap<>();

        /**
         * How long a model is benched after an exhausted quota, an overloaded provider, or a
         * connection failure. Short by design: per-minute allowances refill quickly, and an
         * expired cooldown silently returns the model to rotation.
         */
        private Duration quotaCooldown = Duration.ofMinutes(5);

        /**
         * How long a model is benched after a 404 (retired, renamed, or not enabled for this key).
         * Much longer than {@link #quotaCooldown} because that model is not coming back on its
         * own — the cooldown just stops every request paying for the same 404 until an operator
         * updates the configuration.
         */
        private Duration unavailableCooldown = Duration.ofHours(6);

        /**
         * How startup reacts when the chain names a provider the deployment has no API key for.
         * FAIL_FAST (default) refuses to start; WARN logs and carries on with a shorter chain.
         * <p>
         * Fail-fast is safe as a default because failover is opt-in: only a deployment that
         * configured a chain can trip it, and a chain entry that can never be built is a typo,
         * not a decision — better caught at boot than on the first exhausted quota.
         */
        private Validation validation = Validation.FAIL_FAST;

        public enum Validation {
            FAIL_FAST, WARN
        }
    }

    /**
     * Chunk size for splitting documents before embedding.
     */
    @Data
    public static class EmbeddingConfig {
        /**
         * Default chunk size in characters for text splitting.
         */
        private int chunkSize = 1000;

        /**
         * Overlap between chunks in characters to preserve context.
         */
        private int chunkOverlap = 200;

        /**
         * Maximum number of similar chunks to retrieve for RAG context.
         */
        private int topK = 5;

        /**
         * Documents embedded in parallel by the backfill ({@code POST /api/v1/ai/embeddings/backfill}):
         * what re-embeds a library after a provider switch, or repairs a failed upload embedding.
         */
        private int backfillConcurrency = 2;

        /**
         * Minimum similarity threshold (0.0 - 1.0) for vector search results.
         */
        private double similarityThreshold = 0.7;

        /**
         * How {@link EmbeddingRegistryGuard} reacts when the configured embedding model no longer
         * matches the one that indexed the existing vectors (or its dimensions don't fit the
         * vector_store schema). FAIL_FAST (default) refuses to start; WARN logs an error and
         * starts anyway, accepting degraded RAG results.
         */
        private Validation validation = Validation.FAIL_FAST;

        public enum Validation {
            FAIL_FAST, WARN
        }
    }
}
