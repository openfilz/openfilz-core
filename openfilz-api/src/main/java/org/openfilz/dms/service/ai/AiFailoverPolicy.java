package org.openfilz.dms.service.ai;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies a chat-model failure to decide whether OpenFilz may transparently fail over to
 * the next model in the fallback chain (see {@link AiFallbackChain}).
 * <p>
 * Deliberately dependency-free: each provider SDK reports quota and availability differently
 * (Google GenAI throws {@code com.google.genai.errors.ClientException} carrying an HTTP status
 * in its message, the Anthropic and OpenAI SDKs throw typed {@code RateLimitException}s, Spring
 * AI wraps all of them in a generic {@code RuntimeException("Failed to generate content")}), and
 * OpenFilz must not compile against — nor reflect over, which GraalVM native images dislike —
 * any of those types. So classification walks the cause chain and matches on HTTP status,
 * exception simple name, and message keywords only.
 * <p>
 * A credential problem is reported as its own verdict, {@link Failure#CREDENTIALS_REJECTED},
 * rather than folded into {@link Failure#NOT_FAILOVER}: what to do about a refused key depends on
 * <em>whose</em> key it is. The active model's key must surface — silently answering elsewhere
 * would hide a misconfiguration an operator has to fix. A key from a fallback pool is the opposite
 * case: the pool exists so that one key failing is survivable, so the caller disables that key and
 * keeps going (see {@code AiChatServiceImpl}). Neither is decided here; this class only names the
 * failure.
 */
public final class AiFailoverPolicy {

    private AiFailoverPolicy() {
    }

    /** What went wrong, and whether another model is worth trying. */
    public enum Failure {

        /** 429 / RESOURCE_EXHAUSTED — the per-minute or per-day quota is spent. Retry elsewhere. */
        QUOTA_EXHAUSTED(true),

        /** 404 — the model was retired, renamed, or is not enabled for this key. Retry elsewhere. */
        MODEL_UNAVAILABLE(true),

        /** 5xx, connection failures, timeouts — the provider is down or unreachable. Retry elsewhere. */
        PROVIDER_OVERLOADED(true),

        /**
         * The provider refused the API key itself (401/403 with no quota wording, or a typed
         * authentication exception). Another <em>model</em> on the same key would fail
         * identically, so {@link #shouldFailover()} is false; another <em>key</em> may well
         * work, which is the caller's decision to make.
         */
        CREDENTIALS_REJECTED(false),

        /** A malformed request or an OpenFilz bug — surface it, never mask it. */
        NOT_FAILOVER(false);

        private final boolean failover;

        Failure(boolean failover) {
            this.failover = failover;
        }

        /** Whether OpenFilz should try the next model in the chain for this failure. */
        public boolean shouldFailover() {
            return failover;
        }
    }

    /** Guards against a pathological (or cyclic) cause chain. */
    private static final int MAX_CAUSE_DEPTH = 12;

    /** HTTP statuses we recognise; anything else parsed out of a message is ignored as noise. */
    private static final Set<Integer> KNOWN_STATUSES = Set.of(
            400, 401, 402, 403, 404, 408, 409, 413, 422, 425, 429, 500, 502, 503, 504, 529);

    /**
     * Status at the very start of the message — the shape the Google GenAI SDK uses
     * ({@code "404 . This model models/gemini-2.5-flash is no longer available…"}).
     */
    private static final Pattern LEADING_STATUS = Pattern.compile("^\\s*(\\d{3})\\b");

    /** Status introduced by a label, e.g. {@code "status code: 429"} or {@code "HTTP 503"}. */
    private static final Pattern LABELLED_STATUS =
            Pattern.compile("(?:status|code|http)\\D{0,10}?(\\d{3})\\b", Pattern.CASE_INSENSITIVE);

    private static final String[] QUOTA_KEYWORDS = {
            "resource_exhausted", "resource exhausted", "rate limit", "rate_limit", "ratelimit",
            "too many requests", "quota", "exceeded your current", "billing hard limit",
            "insufficient_quota", "requests per minute", "requests per day"
    };

    private static final String[] MODEL_GONE_KEYWORDS = {
            "no longer available", "not available", "not found", "does not exist", "unknown model",
            "model_not_found", "invalid model", "unsupported model", "is not supported",
            "not found for api version", "deprecated"
    };

    private static final String[] AUTH_KEYWORDS = {
            "api key", "api_key", "apikey", "unauthenticated", "unauthorized", "permission_denied",
            "permission denied", "invalid authentication", "invalid_authentication", "credential",
            "authentication_error", "forbidden", "access denied"
    };

    private static final String[] OVERLOAD_KEYWORDS = {
            "overloaded", "service unavailable", "service_unavailable", "unavailable",
            "try again later", "temporarily", "internal error", "internal server error",
            "backend error", "bad gateway", "deadline exceeded", "timed out", "timeout",
            "connection reset", "connection refused", "temporary failure in name resolution"
    };

    /** Exception simple names (substring match) that identify a failure without parsing the message. */
    private static final String[] QUOTA_TYPES = {"RateLimit", "ResourceExhausted", "TooManyRequests", "Quota"};
    private static final String[] MODEL_GONE_TYPES = {"NotFound", "ModelNotFound"};
    private static final String[] AUTH_TYPES = {"Authentication", "PermissionDenied", "Unauthorized", "Forbidden"};
    private static final String[] OVERLOAD_TYPES = {
            "Overloaded", "ServiceUnavailable", "ServerException", "InternalServer",
            "UnknownHost", "ConnectException", "SocketTimeout", "Timeout", "ConnectTimeout"
    };

    /**
     * Classify a failure thrown by (or wrapped around) a chat-model call.
     * <p>
     * The whole cause chain is inspected because Spring AI wraps provider exceptions in a
     * generic {@code RuntimeException}: the actionable signal is always further down. The first
     * cause that yields one of the three retryable verdicts wins, so a definite "429" deep in the
     * chain is not masked by an uninformative wrapper at the top.
     * <p>
     * {@link Failure#CREDENTIALS_REJECTED} is the weakest verdict: it is remembered but does not
     * stop the walk. Provider messages mention "API key" freely — a retired-model or quota error
     * often does — and letting that wording short-circuit would turn a spent quota into a
     * "your key is broken" verdict, which now has the side effect of disabling a pool key.
     */
    public static Failure classify(Throwable error) {
        Failure verdict = Failure.NOT_FAILOVER;
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            Failure found = classifySingle(current);
            if (found.shouldFailover()) {
                return found;
            }
            if (found == Failure.CREDENTIALS_REJECTED && verdict == Failure.NOT_FAILOVER) {
                verdict = found;
            }
            Throwable cause = current.getCause();
            current = (cause == current) ? null : cause;
        }
        return verdict;
    }

    /** Classify one link of the cause chain, ignoring its causes. */
    private static Failure classifySingle(Throwable error) {
        String type = error.getClass().getSimpleName();
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);

        // Typed exceptions are unambiguous — prefer them over message archaeology.
        if (containsAny(type, QUOTA_TYPES)) return Failure.QUOTA_EXHAUSTED;
        if (containsAny(type, AUTH_TYPES)) return Failure.CREDENTIALS_REJECTED;
        if (containsAny(type, MODEL_GONE_TYPES)) return Failure.MODEL_UNAVAILABLE;
        if (containsAny(type, OVERLOAD_TYPES)) return Failure.PROVIDER_OVERLOADED;

        Integer status = extractStatus(message);
        if (status != null) {
            return switch (status) {
                case 429 -> Failure.QUOTA_EXHAUSTED;
                // Some providers report an exhausted quota as 402/403 rather than 429, so the
                // message still decides; without quota wording these stay a credential problem.
                case 401, 402, 403 -> containsAny(message, QUOTA_KEYWORDS)
                        ? Failure.QUOTA_EXHAUSTED : Failure.CREDENTIALS_REJECTED;
                case 404 -> Failure.MODEL_UNAVAILABLE;
                case 408, 500, 502, 503, 504, 529 -> Failure.PROVIDER_OVERLOADED;
                // Any other recognised status is a request we built wrong — our bug, not the
                // model's; another model would fail identically, so do not burn the chain on it.
                default -> Failure.NOT_FAILOVER;
            };
        }

        if (containsAny(message, QUOTA_KEYWORDS)) return Failure.QUOTA_EXHAUSTED;
        if (containsAny(message, AUTH_KEYWORDS)) return Failure.CREDENTIALS_REJECTED;
        if (containsAny(message, MODEL_GONE_KEYWORDS)) return Failure.MODEL_UNAVAILABLE;
        if (containsAny(message, OVERLOAD_KEYWORDS)) return Failure.PROVIDER_OVERLOADED;
        return Failure.NOT_FAILOVER;
    }

    /**
     * Pull an HTTP status out of a message, without guessing.
     * <p>
     * Only a leading status or an explicitly labelled one counts: a bare three-digit scan would
     * happily read "429" out of a model name or a token count and fail over for no reason.
     */
    private static Integer extractStatus(String message) {
        Integer leading = firstMatch(LEADING_STATUS, message);
        if (leading != null) return leading;
        return firstMatch(LABELLED_STATUS, message);
    }

    private static Integer firstMatch(Pattern pattern, String message) {
        Matcher matcher = pattern.matcher(message);
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            if (KNOWN_STATUSES.contains(value)) return value;
        }
        return null;
    }

    /**
     * Case-insensitive on <em>both</em> sides: the keyword tables are lower case but the type
     * tables are CamelCase, and comparing a lower-cased class name against {@code "RateLimit"}
     * silently never matches — which would quietly reduce every typed-exception rule above to
     * dead code and leave classification to message archaeology alone.
     */
    private static boolean containsAny(String haystack, String[] needles) {
        String lower = haystack.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * One line for a log or a stored reason: the verdict and the deepest informative message,
     * e.g. {@code "QUOTA_EXHAUSTED: 429 . RESOURCE_EXHAUSTED. You exceeded your current quota."}.
     * Spring AI's wrapper says only "Failed to generate content"; what an operator needs is
     * always further down the cause chain.
     */
    public static String describe(Throwable error) {
        Failure failure = classify(error);
        String detail = rootMessage(error);
        return failure == Failure.NOT_FAILOVER ? detail : failure + ": " + detail;
    }

    /** The deepest non-blank message in the cause chain, or the deepest exception's type name. */
    public static String rootMessage(Throwable error) {
        String message = null;
        Throwable deepest = error;
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage().trim();
            }
            deepest = current;
            Throwable cause = current.getCause();
            current = (cause == current) ? null : cause;
        }
        if (message != null) return message;
        return deepest == null ? "unknown error" : deepest.getClass().getSimpleName();
    }
}
