package org.openfilz.dms.service.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.McpProperties;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.ai.DocumentAiTools;
import org.openfilz.dms.service.ai.DocumentAiToolsFactory;
import org.openfilz.dms.utils.ImageUtils;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.openfilz.dms.security.JwtTokenParser.EMAIL;

/**
 * The MCP-native front-end for document downloads — the one place the MCP layer hand-builds
 * specifications instead of adapting {@code @Tool} callbacks.
 * <p>
 * <b>Why it exists:</b> an adapted tool returns a {@code String}, which the Spring AI MCP bridge
 * wraps into a single {@code TextContent} — there is no way to attach the {@code resource_link}
 * content block that lets an MCP client fetch a document's original bytes. So:
 * <ul>
 *   <li>{@code downloadDocument} is registered as a native tool specification whose result
 *       carries BOTH a {@code resource_link} ({@code openfilz://documents/{id}}) and a text
 *       fallback (the extracted text — exactly what the adapted route used to return). A client
 *       that understands resource links follows the link with {@code resources/read}; a
 *       tools-only client (n8n, plain agent frameworks) ignores the unknown block, per spec, and
 *       keeps today's behaviour. The server cannot branch on client capability — MCP's
 *       {@code initialize} declares none for resource consumption — so one result serves both
 *       audiences. For an image the result also carries an {@code image} block (re-encoded,
 *       {@code openfilz.mcp.inline-image-max-dimension}): claude.ai follows neither the link nor
 *       {@code resources/read}, but it shows an {@code image} block to its model.</li>
 *   <li>a resource template {@code openfilz://documents/{id}} serves {@code resources/read}
 *       with the raw bytes (base64 blob), authenticated and authorized per call.</li>
 * </ul>
 * Only the MCP wire shape lives here. The logic — name resolution, the role gate, the per-user
 * access policy, text extraction, byte loading with the size cap — is
 * {@link DocumentAiTools#fetchDownload}/{@link DocumentAiTools#fetchContent}, bound per call to
 * the authenticated caller exactly like the adapted tools. "The MCP layer never defines a tool"
 * therefore still holds where it matters: this class could not leak a document the tool layer
 * would refuse.
 * <p>
 * <b>Security:</b> {@code resources/read} rides the same authenticated {@code POST /mcp} as
 * {@code tools/call} — Spring Security has validated the JWT before the transport sees the
 * request, {@code McpConfig}'s contextExtractor forwards the {@code Authentication}, and every
 * handler here fails closed without it. An {@code openfilz://} URI is an inert identifier: no
 * bearer credential ever leaves the connection (unlike a signed URL), replaying a URI under
 * another token gets that caller's own access decision, and an id the caller cannot read answers
 * exactly like an id that does not exist (no existence oracle).
 * <p>
 * <b>Registration:</b> Spring AI's stateless server auto-configuration merges every bean of type
 * {@code List<SyncToolSpecification>} / {@code List<SyncResourceTemplateSpecification>}
 * (via {@code ObjectProvider.stream().flatMap(...)}) with the callback-converted tools, so these
 * beans compose with {@link McpToolCallbackProvider}'s surface. The {@code openfilz.mcp.active}
 * flag is read at bean-creation time — mirroring the provider, never a bean condition
 * (native-safe): when off, both beans contribute nothing.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class McpDocumentResources implements UserInfoService {

    /** The tool this class serves natively; {@link DocumentAiToolsContributor#nativeTools()} points here. */
    public static final String DOWNLOAD_TOOL = "downloadDocument";

    /** URI scheme for document resources; the suffix must be exactly the document UUID. */
    public static final String DOCUMENT_URI_PREFIX = "openfilz://documents/";

    /** The advertised template ({@code resources/templates/list}). */
    public static final String DOCUMENT_URI_TEMPLATE = DOCUMENT_URI_PREFIX + "{id}";

    private final DocumentAiToolsFactory toolsFactory;
    private final McpProperties mcpProperties;

    @Bean
    public List<McpStatelessServerFeatures.SyncToolSpecification> downloadDocumentSpecification() {
        if (!mcpProperties.isActive()) {
            return List.of();
        }
        McpSchema.Tool definition = McpSchema.Tool.builder()
                .name(DOWNLOAD_TOOL)
                .description("Get a document's content and the original file. Returns the extracted text "
                        + "for text/PDF/Office files, plus a resource link (openfilz://documents/{id}): "
                        + "fetch it with resources/read on this same connection to obtain the original "
                        + "file bytes. For an image (photo, scan), the picture itself is attached to the "
                        + "result so you can look at it with your own vision.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("documentName", Map.of(
                                "type", "string",
                                "description", "The name or reference of the document")),
                        "required", List.of("documentName")))
                .build();
        return List.of(new McpStatelessServerFeatures.SyncToolSpecification(definition, this::download));
    }

    @Bean
    public List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> documentResourceTemplate() {
        if (!mcpProperties.isActive()) {
            return List.of();
        }
        // Only the template is advertised — resources/list stays empty on purpose. The list is
        // assembled once at startup for the whole deployment, so enumerating documents there
        // would be both impossible (per-caller) and a leak; a static template string leaks
        // nothing, and per-call authorization happens in the read handler.
        McpSchema.ResourceTemplate template = new McpSchema.ResourceTemplate(
                DOCUMENT_URI_TEMPLATE,
                "document",
                "OpenFilz document",
                "The raw bytes of a document, by UUID (from queryDocuments or a downloadDocument "
                        + "resource link). Only documents the authenticated caller can read.",
                null, // mime type varies per document
                null);
        return List.of(new McpStatelessServerFeatures.SyncResourceTemplateSpecification(template, this::readDocument));
    }

    // ---------------------------------------------------------------- handlers

    /** tools/call downloadDocument → resource_link + text fallback, or a text-only refusal. */
    private McpSchema.CallToolResult download(McpTransportContext context, McpSchema.CallToolRequest request) {
        Authentication authentication = McpAuthenticationWebFilter.authenticationFrom(context);
        if (authentication == null) {
            log.warn("MCP tool '{}' called without an authenticated caller — refused", DOWNLOAD_TOOL);
            return textResult("Not authenticated: this MCP server requires a bearer token identifying "
                    + "an OpenFilz user.");
        }
        Object argument = request.arguments() == null ? null : request.arguments().get("documentName");
        if (!(argument instanceof String documentName) || documentName.isBlank()) {
            return textResult("Provide the documentName to download.");
        }
        // One fetch serves every block: the gates, the storage read and the signed link happen
        // once, and for an image the bytes come back here instead of a Tika pass over a photo
        int maxDimension = mcpProperties.getInlineImageMaxDimension();
        DocumentAiTools.DocumentDownload download = boundTools(authentication)
                .fetchDownload(documentName, maxDimension > 0 ? mcpProperties.getInlineImageMaxSourceBytes() : 0L);
        if (download.error() != null) {
            return textResult(download.error());
        }
        Document doc = download.document();
        McpSchema.ResourceLink link = McpSchema.ResourceLink.builder()
                .uri(DOCUMENT_URI_PREFIX + doc.getId())
                .name(doc.getName())
                .mimeType(doc.getContentType())
                .size(doc.getSize())
                .description("The original file. Fetch it with resources/read on this same connection.")
                .build();
        McpSchema.ImageContent image = imageContent(doc, download.imageBytes(), maxDimension);
        String text = image != null
                ? ("'%s' is an image (%s, %d bytes), attached to this result so you can look at it "
                        + "directly (re-encoded for viewing: %s). The resource link returns the original "
                        + "file (resources/read); it can also be downloaded in a browser (%s): %s").formatted(
                        doc.getName(), contentTypeOf(doc), sizeOf(doc), imageNote(image),
                        download.downloadHint(), download.downloadUrl())
                : download.extractedText() != null
                ? ("Content of '%s' (%s, %d bytes):\n\n%s\n\n"
                        + "The resource link above returns the original file (resources/read); it can also "
                        + "be downloaded in a browser (%s): %s").formatted(
                        doc.getName(), contentTypeOf(doc), sizeOf(doc), download.extractedText(),
                        download.downloadHint(), download.downloadUrl())
                : ("'%s' is a %s file (%d bytes); its content is not text. Fetch the original bytes via "
                        + "the resource link above (resources/read), or download it in a browser "
                        + "(%s): %s").formatted(
                        doc.getName(), contentTypeOf(doc), sizeOf(doc),
                        download.downloadHint(), download.downloadUrl());
        List<McpSchema.Content> blocks = new ArrayList<>();
        blocks.add(link);
        if (image != null) {
            blocks.add(image);
        }
        blocks.add(McpSchema.TextContent.builder(text).build());
        log.debug("[MCP] {} served '{}' as resource link{} + text", DOWNLOAD_TOOL, doc.getName(),
                image != null ? " + image" : "");
        return McpSchema.CallToolResult.builder()
                .content(blocks)
                .isError(false)
                .build();
    }

    /** resources/read openfilz://documents/{id} → base64 blob of the original bytes. */
    private McpSchema.ReadResourceResult readDocument(McpTransportContext context,
                                                      McpSchema.ReadResourceRequest request) {
        String uri = request.uri();
        Authentication authentication = McpAuthenticationWebFilter.authenticationFrom(context);
        if (authentication == null) {
            // Fail closed like the tool route. (In practice Spring Security answers 401 long
            // before an unauthenticated request reaches the transport.)
            log.warn("MCP resources/read '{}' without an authenticated caller — refused", uri);
            throw McpError.RESOURCE_NOT_FOUND.apply(uri);
        }
        UUID documentId = documentIdFrom(uri);
        if (documentId == null) {
            throw McpError.RESOURCE_NOT_FOUND.apply(uri);
        }
        DocumentAiTools.DocumentContent content = boundTools(authentication)
                .fetchContent(documentId, mcpProperties.getMaxResourceSizeBytes());
        if (content.error() != null) {
            log.debug("[MCP] resources/read {} refused for {}: {}", uri, authentication.getName(), content.error());
            if (content.notFound()) {
                // Same code and shape for "does not exist" and "not yours to see"
                throw McpError.RESOURCE_NOT_FOUND.apply(uri);
            }
            throw McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST).message(content.error()).build();
        }
        log.debug("[MCP] resources/read {} served {} bytes to {}",
                uri, content.bytes().length, authentication.getName());
        return new McpSchema.ReadResourceResult(List.of(
                McpSchema.BlobResourceContents.builder(uri, Base64.getEncoder().encodeToString(content.bytes()))
                        .mimeType(content.document().getContentType())
                        .build()));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The picture itself as an MCP {@code image} block, or null when there is nothing to attach:
     * the document is not an image, the feature is off
     * ({@code openfilz.mcp.inline-image-max-dimension=0}), the file was above
     * {@code openfilz.mcp.inline-image-max-source-bytes}, or its format cannot be decoded (HEIC…).
     * Hosts such as claude.ai consume neither resource links nor {@code resources/read}, but they
     * hand an {@code image} block to their model as a real image — the only way such a host can
     * see a photo stored in OpenFilz without an OpenFilz-side vision model.
     * <p>
     * Pure encoding: the bytes were read by {@link DocumentAiTools#fetchDownload(String, long)}
     * on the same fetch that produced the link, behind the same role gate and access policy, so
     * attaching the image costs no second lookup or storage round trip.
     */
    private McpSchema.ImageContent imageContent(Document doc, byte[] imageBytes, int maxDimension) {
        if (imageBytes == null || maxDimension <= 0) {
            return null;
        }
        try {
            ImageUtils.EncodedImage encoded = ImageUtils.toVisionImage(imageBytes, maxDimension);
            if (encoded == null) {
                log.debug("[MCP] {} no image block for '{}': {} cannot be decoded", DOWNLOAD_TOOL, doc.getName(),
                        doc.getContentType());
                return null;
            }
            return McpSchema.ImageContent.builder(Base64.getEncoder().encodeToString(encoded.bytes()), encoded.mimeType())
                    .meta(Map.of("width", encoded.width(), "height", encoded.height()))
                    .build();
        } catch (Exception | LinkageError e) {
            // Never fail the download over the preview: the link and the text still answer
            log.warn("[MCP] {} could not re-encode image '{}': {}", DOWNLOAD_TOOL, doc.getName(), e.toString());
            return null;
        }
    }

    private static String imageNote(McpSchema.ImageContent image) {
        Object width = image.meta() == null ? null : image.meta().get("width");
        Object height = image.meta() == null ? null : image.meta().get("height");
        return width == null || height == null ? image.mimeType()
                : "%s, %sx%s px".formatted(image.mimeType(), width, height);
    }

    /** Strict template match: {@code openfilz://documents/<uuid>} and nothing else. */
    private static UUID documentIdFrom(String uri) {
        if (uri == null || !uri.startsWith(DOCUMENT_URI_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(uri.substring(DOCUMENT_URI_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * A tools instance bound to the caller, exactly like the adapted route's
     * {@code contributor.bind(email, authentication)}. Null {@code ChatModel} on purpose: only
     * {@code describeImage} consumes one, and this front-end never calls it.
     */
    private DocumentAiTools boundTools(Authentication authentication) {
        return toolsFactory.create(null, getUserAttribute(authentication, EMAIL), authentication);
    }

    private static McpSchema.CallToolResult textResult(String text) {
        // isError stays false: refusals and not-found answers are normal results here, matching
        // the adapted tools (McpToolUtils marks nothing as isError either).
        return McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder(text).build()))
                .isError(false)
                .build();
    }

    private static String contentTypeOf(Document doc) {
        return doc.getContentType() == null ? "unknown type" : doc.getContentType();
    }

    private static long sizeOf(Document doc) {
        return doc.getSize() == null ? 0L : doc.getSize();
    }
}
