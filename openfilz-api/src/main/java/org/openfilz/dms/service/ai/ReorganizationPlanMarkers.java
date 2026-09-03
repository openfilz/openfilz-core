package org.openfilz.dms.service.ai;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code [[reorg-plan:id]]} marker the chat pipeline appends to an assistant message when a
 * reorganisation plan was proposed during the turn. The frontend renders the plan as an
 * interactive proposal card in place of the marker; the marker is persisted with the message so
 * the card survives a reload, and stripped from the history the model sees so it never mimics it.
 */
public final class ReorganizationPlanMarkers {

    public static final Pattern PATTERN = Pattern.compile("\\[\\[reorg-plan:([0-9a-fA-F-]{36})]]");

    private ReorganizationPlanMarkers() {
    }

    public static String marker(UUID planId) {
        return "[[reorg-plan:" + planId + "]]";
    }

    /** Append a marker per plan id not already present in the text (one per line, after a blank line). */
    public static String append(String text, List<UUID> planIds) {
        if (planIds == null || planIds.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text == null ? "" : text);
        for (UUID planId : planIds) {
            String marker = marker(planId);
            if (sb.indexOf(marker) >= 0) continue;
            if (!sb.isEmpty()) sb.append(sb.charAt(sb.length() - 1) == '\n' ? "\n" : "\n\n");
            sb.append(marker);
        }
        return sb.toString();
    }

    /** Remove every marker (and the blank lines that carried it). */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher matcher = PATTERN.matcher(text);
        if (!matcher.find()) return text;
        return matcher.replaceAll("").replaceAll("\\n{3,}", "\n\n").stripTrailing();
    }
}
