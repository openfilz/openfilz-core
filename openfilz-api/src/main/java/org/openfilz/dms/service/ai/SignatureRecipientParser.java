package org.openfilz.dms.service.ai;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the free-form recipient list a model hands to {@code sendForSignature}. Accepted forms,
 * separated by {@code ;}, {@code ,} or newlines:
 * <ul>
 *   <li>{@code alice@example.com}</li>
 *   <li>{@code Alice Smith <alice@example.com>} or {@code Alice Smith alice@example.com}</li>
 *   <li>{@code Tenant: Alice Smith <alice@example.com>} — role-bound, for templates
 *       ({@code =} works as well as {@code :})</li>
 *   <li>{@code cc: bob@example.com} — a carbon-copy recipient who does not sign</li>
 *   <li>a JSON array of {@code {"email", "name", "role", "cc"}} objects</li>
 * </ul>
 */
final class SignatureRecipientParser {

    record Recipient(String role, String name, String email, boolean cc) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ANGLE = Pattern.compile("^(.*?)\\s*<\\s*([^<>\\s]+)\\s*>\\s*$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s<>@,;]+@[^\\s<>@,;]+\\.[^\\s<>@,;]+$");

    private SignatureRecipientParser() {
    }

    static List<Recipient> parse(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String text = input.trim();
        if (text.startsWith("[")) {
            return parseJson(text);
        }
        List<Recipient> recipients = new ArrayList<>();
        for (String token : text.split("[;,\\n]")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                recipients.add(parseOne(trimmed));
            }
        }
        return recipients;
    }

    static Recipient parseOne(String token) {
        String role = null;
        boolean cc = false;
        String rest = token.trim();
        int separator = roleSeparator(rest);
        if (separator > 0) {
            String prefix = rest.substring(0, separator).trim();
            if (!prefix.isEmpty() && !prefix.contains("@") && !prefix.contains("<")) {
                role = prefix;
                rest = rest.substring(separator + 1).trim();
            }
        }
        if (role != null && "cc".equalsIgnoreCase(role)) {
            cc = true;
            role = null;
        }
        String name = null;
        String email;
        Matcher angle = ANGLE.matcher(rest);
        if (angle.matches()) {
            name = blankToNull(angle.group(1));
            email = angle.group(2).trim();
        } else {
            String[] parts = rest.split("\\s+");
            int emailIndex = -1;
            for (int i = parts.length - 1; i >= 0; i--) {
                if (parts[i].contains("@")) {
                    emailIndex = i;
                    break;
                }
            }
            if (emailIndex < 0) {
                throw new IllegalArgumentException("'" + token + "' has no email address.");
            }
            email = parts[emailIndex];
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i != emailIndex) sb.append(parts[i]).append(' ');
            }
            name = blankToNull(sb.toString());
        }
        if (!EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("'" + email + "' is not a valid email address.");
        }
        return new Recipient(role, name, email.toLowerCase(Locale.ROOT), cc);
    }

    @SuppressWarnings("unchecked")
    private static List<Recipient> parseJson(String json) {
        List<Map<String, Object>> entries;
        try {
            entries = JSON.readValue(json, List.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("recipients is not a valid JSON array: " + e.getMessage());
        }
        List<Recipient> recipients = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof String s) {
                recipients.add(parseOne(s));
                continue;
            }
            if (!(entry instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Each recipient must be an object with an 'email'.");
            }
            String email = stringOf(map.get("email"));
            if (email == null || !EMAIL.matcher(email).matches()) {
                throw new IllegalArgumentException("Recipient " + map + " has no valid 'email'.");
            }
            String role = stringOf(map.get("role"));
            boolean cc = Boolean.TRUE.equals(map.get("cc")) || "cc".equalsIgnoreCase(role);
            if (cc) role = null;
            recipients.add(new Recipient(role, stringOf(map.get("name")), email.toLowerCase(Locale.ROOT), cc));
        }
        return recipients;
    }

    /** Index of the first {@code :} or {@code =} that precedes any {@code @} or {@code <}, or -1. */
    private static int roleSeparator(String s) {
        int limit = s.length();
        int at = s.indexOf('@');
        int lt = s.indexOf('<');
        if (at >= 0) limit = Math.min(limit, at);
        if (lt >= 0) limit = Math.min(limit, lt);
        int colon = s.indexOf(':');
        int equals = s.indexOf('=');
        int best = -1;
        if (colon > 0 && colon < limit) best = colon;
        if (equals > 0 && equals < limit && (best < 0 || equals < best)) best = equals;
        return best;
    }

    private static String stringOf(Object value) {
        return value == null ? null : blankToNull(value.toString());
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim().replaceAll("^[\"']|[\"']$", "").trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
