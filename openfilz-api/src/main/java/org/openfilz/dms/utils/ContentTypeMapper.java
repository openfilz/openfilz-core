package org.openfilz.dms.utils;

import lombok.experimental.UtilityClass;
import org.springframework.http.MediaType;

import java.util.Map;

@UtilityClass
public class ContentTypeMapper {

    private static final Map<String, String> EXTENSION_TO_CONTENT_TYPE = Map.ofEntries(
            // Documents
            Map.entry("pdf", MediaType.APPLICATION_PDF_VALUE),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("txt", MediaType.TEXT_PLAIN_VALUE),
            Map.entry("csv", "text/csv"),
            Map.entry("tsv", "text/tab-separated-values"),
            Map.entry("rtf", "application/rtf"),
            Map.entry("log", MediaType.TEXT_PLAIN_VALUE),
            Map.entry("md", "text/markdown"),
            Map.entry("rst", MediaType.TEXT_PLAIN_VALUE),
            Map.entry("ini", MediaType.TEXT_PLAIN_VALUE),
            Map.entry("cfg", MediaType.TEXT_PLAIN_VALUE),
            Map.entry("conf", MediaType.TEXT_PLAIN_VALUE),
            Map.entry("env", MediaType.TEXT_PLAIN_VALUE),
            Map.entry("sql", "application/x-sql"),
            Map.entry("graphql", "application/graphql"),
            Map.entry("ts", "application/typescript"),

            // Images
            Map.entry("jpg", MediaType.IMAGE_JPEG_VALUE),
            Map.entry("jpeg", MediaType.IMAGE_JPEG_VALUE),
            Map.entry("png", MediaType.IMAGE_PNG_VALUE),
            Map.entry("gif", MediaType.IMAGE_GIF_VALUE),
            Map.entry("bmp", "image/bmp"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            
            // Audio
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("m4a", "audio/mp4"),
            
            // Video
            Map.entry("mp4", "video/mp4"),
            Map.entry("avi", "video/x-msvideo"),
            Map.entry("mpeg", "video/mpeg"),
            Map.entry("webm", "video/webm"),
            Map.entry("mov", "video/quicktime"),
            
            // Archives
            Map.entry("zip", MediaType.APPLICATION_OCTET_STREAM_VALUE),
            Map.entry("rar", MediaType.APPLICATION_OCTET_STREAM_VALUE),
            Map.entry("7z", MediaType.APPLICATION_OCTET_STREAM_VALUE),
            Map.entry("tar", "application/x-tar"),
            Map.entry("gz", "application/gzip"),
            
            // Web
            Map.entry("html", MediaType.TEXT_HTML_VALUE),
            Map.entry("htm", MediaType.TEXT_HTML_VALUE),
            Map.entry("xhtml", MediaType.APPLICATION_XHTML_XML_VALUE),
            Map.entry("css", "text/css"),
            Map.entry("js", "application/javascript"),
            Map.entry("json", MediaType.APPLICATION_JSON_VALUE),
            Map.entry("jsonl", MediaType.APPLICATION_JSON_VALUE),
            Map.entry("xml", MediaType.APPLICATION_XML_VALUE),
            Map.entry("yml", MediaType.APPLICATION_YAML_VALUE),
            Map.entry("yaml", MediaType.APPLICATION_YAML_VALUE)
    );

    /**
     * Maps a file extension to its corresponding HTTP Content-Type.
     *
     * @param extension the file extension (without the dot), e.g., "pdf"
     * @return the corresponding Content-Type string, or "application/octet-stream" if unknown
     */
    public static String getContentType(String extension) {
        if (extension == null || extension.isBlank()) {
            return null;
        }
        String normalizedExtension = extension.toLowerCase().trim();
        return EXTENSION_TO_CONTENT_TYPE.get(normalizedExtension);
    }
}