package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.request.ReorganizationPlanRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure parsing helpers of the reorganisation tools — no Spring, no database. */
class OrganizeAiToolsParsePlanTest {

    @Test
    void parsesAPlainJsonPlan() {
        ReorganizationPlanRequest plan = OrganizeAiTools.parsePlan("""
                {"rootFolder":"root","rationale":"by year","moves":[{"document":"a","target":"2026/Invoices"}],"createFolders":["Archive"]}""");
        assertThat(plan).isNotNull();
        assertThat(plan.rootFolder()).isEqualTo("root");
        assertThat(plan.rationale()).isEqualTo("by year");
        assertThat(plan.moves()).containsExactly(new ReorganizationPlanRequest.Move("a", "2026/Invoices"));
        assertThat(plan.createFolders()).containsExactly("Archive");
    }

    @Test
    void toleratesAMarkdownCodeFence() {
        ReorganizationPlanRequest plan = OrganizeAiTools.parsePlan("""
                ```json
                {"moves":[{"document":"a","target":""}]}
                ```""");
        assertThat(plan).isNotNull();
        assertThat(plan.moves()).hasSize(1);
    }

    @Test
    void ignoresUnknownFieldsAndRequiresMoves() {
        assertThat(OrganizeAiTools.parsePlan("""
                {"moves":[{"document":"a","target":"x","note":"?"}],"extra":1}""")).isNotNull();
        assertThat(OrganizeAiTools.parsePlan("{\"rootFolder\":\"root\"}")).isNull();
        assertThat(OrganizeAiTools.parsePlan("   ")).isNull();
        assertThatThrownBy(() -> OrganizeAiTools.parsePlan("{not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void normalizesTargetPaths() {
        assertThat(ReorganizationPlanService.normalizePath(" Finance / Invoices/ ")).containsExactly("Finance", "Invoices");
        assertThat(ReorganizationPlanService.normalizePath("Finance\\Invoices")).containsExactly("Finance", "Invoices");
        assertThat(ReorganizationPlanService.normalizePath("")).isEmpty();
        assertThat(ReorganizationPlanService.normalizePath("/")).isEmpty();
        assertThat(ReorganizationPlanService.normalizePath(null)).isEmpty();
        assertThat(ReorganizationPlanService.normalizePath("../etc")).isNull();
        assertThat(ReorganizationPlanService.normalizePath("a/" + "x".repeat(256))).isNull();
        assertThat(ReorganizationPlanService.join("/", "A")).isEqualTo("/A");
        assertThat(ReorganizationPlanService.join("/A", "B")).isEqualTo("/A/B");
    }

    @Test
    void rendersAPlanForTheModel() {
        var item = new org.openfilz.dms.dto.response.ReorganizationPlanView.Item(
                java.util.UUID.randomUUID(), "report.pdf", "FILE", "/Inbox", "/Inbox/Finance", false, true, null);
        var blocked = new org.openfilz.dms.dto.response.ReorganizationPlanView.Item(
                null, "ghost", null, null, null, false, false, "No document 'ghost' is visible to you.");
        var view = new org.openfilz.dms.dto.response.ReorganizationPlanView(java.util.UUID.randomUUID(), "PROPOSED",
                null, "/Inbox", "By topic", List.of(item, blocked), List.of("/Inbox/Finance"), 1, 1, "u@x", null, null, null);
        String rendered = OrganizeAiTools.render(view);
        assertThat(rendered).contains("1 move(s) ready, 1 blocked")
                .contains("Folders to create: /Inbox/Finance")
                .contains("report.pdf (file): /Inbox → /Inbox/Finance (new folder)  [ready]")
                .contains("ghost  [blocked: No document 'ghost' is visible to you.]");
    }
}
