package org.openfilz.dms.service.ai;

/**
 * Base URLs in the form the OpenAI SDK expects. Spring AI builds every OpenAI client on the vendor
 * SDK, which appends {@code /chat/completions} to the base URL as it is given: the base URL is the
 * API root <em>with</em> its version segment ({@code https://api.openai.com/v1}). The same host
 * without it ({@code https://api.openai.com}, the form deployments were given for years) sends
 * every call to {@code https://api.openai.com/chat/completions}, which OpenAI answers with 404, a
 * valid key included.
 * <p>
 * No Spring type here: the environment post-processor uses it before the context exists.
 */
public final class OpenAiBaseUrls {

    /** The OpenAI platform's API root, version segment included. */
    public static final String PLATFORM = "https://api.openai.com/v1";

    private static final String PLATFORM_HOST = "https://api.openai.com";
    private static final String VERSION_SEGMENT = "/v1";

    private OpenAiBaseUrls() {
    }

    /**
     * The deployment's own OpenAI endpoint ({@code spring.ai.openai.base-url}, i.e.
     * {@code OPENAI_BASE_URL}): unset or the bare platform host means the platform; any other
     * address (an OpenAI-compatible server) is used as it is written, since only its operator
     * knows where its API root is.
     */
    public static String deployment(String configured) {
        String url = configured == null ? "" : configured.trim();
        String bare = withoutTrailingSlashes(url);
        return bare.isEmpty() || bare.equalsIgnoreCase(PLATFORM_HOST) ? PLATFORM : url;
    }

    /**
     * The OpenFilz AI gateway, whose chat endpoint is {@code /v1/chat/completions}: its address is
     * configured as the gateway's root, so the version segment is added unless it is there already.
     */
    public static String gateway(String url) {
        String base = withoutTrailingSlashes(url == null ? "" : url.trim());
        return base.endsWith(VERSION_SEGMENT) ? base : base + VERSION_SEGMENT;
    }

    private static String withoutTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
