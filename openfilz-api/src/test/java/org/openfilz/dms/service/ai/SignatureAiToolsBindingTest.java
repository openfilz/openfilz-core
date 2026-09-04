package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.signature.InstantiateTemplateRequest.RoleBinding;
import org.openfilz.dms.dto.signature.SignatureRecipientInput;
import org.openfilz.dms.dto.signature.SignatureTemplateDTO;
import org.openfilz.dms.dto.signature.SignatureTemplateRole;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.enums.SignatureRecipientRole;
import org.openfilz.dms.service.ai.SignatureRecipientParser.Recipient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The two envelope-building strategies of {@link SignatureAiTools}: default placement and template role binding. */
class SignatureAiToolsBindingTest {

    private static final Recipient ALICE = new Recipient(null, "Alice", "alice@example.com", false);
    private static final Recipient BOB = new Recipient(null, "Bob", "bob@example.com", false);
    private static final Recipient CC = new Recipient(null, null, "copy@example.com", true);

    @Test
    void defaultPlacementPutsOneSignatureFieldPerSignerOnTheLastPage() {
        List<SignatureRecipientInput> inputs = SignatureAiTools.defaultPlacement(List.of(ALICE, BOB, CC), 3, false);

        assertThat(inputs).hasSize(3);
        SignatureRecipientInput alice = inputs.get(0);
        assertThat(alice.email()).isEqualTo("alice@example.com");
        assertThat(alice.effectiveRole()).isEqualTo(SignatureRecipientRole.SIGNER);
        assertThat(alice.effectiveFields()).hasSize(1);
        assertThat(alice.effectiveFields().getFirst().type()).isEqualTo(SignatureFieldType.SIGNATURE);
        assertThat(alice.effectiveFields().getFirst().page()).isEqualTo(2);
        assertThat(alice.effectiveOrderIndex()).isZero();

        SignatureRecipientInput bob = inputs.get(1);
        assertThat(bob.effectiveFields().getFirst().y()).isGreaterThan(alice.effectiveFields().getFirst().y());
        assertThat(bob.effectiveOrderIndex()).isZero();

        SignatureRecipientInput copy = inputs.get(2);
        assertThat(copy.effectiveRole()).isEqualTo(SignatureRecipientRole.CC);
        assertThat(copy.effectiveFields()).isEmpty();
    }

    @Test
    void sequentialSigningOrdersSignersAsGiven() {
        List<SignatureRecipientInput> inputs = SignatureAiTools.defaultPlacement(List.of(ALICE, CC, BOB), 1, true);
        assertThat(inputs.get(0).effectiveOrderIndex()).isZero();
        assertThat(inputs.get(2).effectiveOrderIndex()).isEqualTo(1);
        assertThat(inputs.get(2).effectiveFields().getFirst().page()).isZero();
    }

    @Test
    void fieldsStayInsideThePage() {
        List<Recipient> many = java.util.stream.IntStream.range(0, 16)
                .mapToObj(i -> new Recipient(null, "S" + i, "s" + i + "@example.com", false)).toList();
        List<SignatureRecipientInput> inputs = SignatureAiTools.defaultPlacement(many, 1, false);
        for (SignatureRecipientInput input : inputs) {
            var field = input.effectiveFields().getFirst();
            assertThat(field.x() + field.w()).isLessThanOrEqualTo(1.0);
            assertThat(field.y() + field.h()).isLessThanOrEqualTo(1.0);
        }
    }

    @Test
    void bindsRolesByNameCaseInsensitively() {
        SignatureTemplateDTO template = template("Lease", "Tenant", "Landlord");
        List<RoleBinding> bindings = SignatureAiTools.bindRoles(template, List.of(
                new Recipient("landlord", "Bob", "bob@example.com", false),
                new Recipient("TENANT", "Alice", "alice@example.com", false)));

        assertThat(bindings).extracting(RoleBinding::role).containsExactly("Landlord", "Tenant");
        assertThat(bindings).extracting(RoleBinding::email).containsExactly("bob@example.com", "alice@example.com");
    }

    @Test
    void bindsRolesInOrderWhenNoneIsNamedAndCountsMatch() {
        SignatureTemplateDTO template = template("Lease", "Tenant", "Landlord");
        List<RoleBinding> bindings = SignatureAiTools.bindRoles(template, List.of(ALICE, BOB, CC));
        assertThat(bindings).extracting(RoleBinding::role).containsExactly("Tenant", "Landlord");
        assertThat(bindings).extracting(RoleBinding::email).containsExactly("alice@example.com", "bob@example.com");
    }

    @Test
    void refusesUnknownRolesAndCountMismatches() {
        SignatureTemplateDTO template = template("Lease", "Tenant", "Landlord");
        assertThatThrownBy(() -> SignatureAiTools.bindRoles(template,
                List.of(new Recipient("Buyer", "X", "x@example.com", false), new Recipient("Tenant", "Y", "y@example.com", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Buyer");
        assertThatThrownBy(() -> SignatureAiTools.bindRoles(template, List.of(ALICE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one recipient per role");
        assertThatThrownBy(() -> SignatureAiTools.bindRoles(template,
                List.of(new Recipient("Tenant", "Y", "y@example.com", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Landlord");
    }

    private static SignatureTemplateDTO template(String name, String... roles) {
        List<SignatureTemplateRole> templateRoles = java.util.stream.IntStream.range(0, roles.length)
                .mapToObj(i -> new SignatureTemplateRole(roles[i], i, SignatureRecipientRole.SIGNER, null)).toList();
        return new SignatureTemplateDTO(UUID.randomUUID(), "owner@example.com", name, null, null, templateRoles,
                List.of(), null, 30, false, null, null);
    }
}
