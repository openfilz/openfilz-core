package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.service.ai.SignatureRecipientParser.Recipient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignatureRecipientParserTest {

    @Test
    void parsesNameWithAngleBracketEmail() {
        List<Recipient> recipients = SignatureRecipientParser.parse("Alice Smith <Alice@Example.com>");
        assertThat(recipients).containsExactly(new Recipient(null, "Alice Smith", "alice@example.com", false));
    }

    @Test
    void parsesBareEmailAndNameFollowedByEmail() {
        List<Recipient> recipients = SignatureRecipientParser.parse("bob@example.com; Carol Jones carol@example.com");
        assertThat(recipients).containsExactly(
                new Recipient(null, null, "bob@example.com", false),
                new Recipient(null, "Carol Jones", "carol@example.com", false));
    }

    @Test
    void parsesRolePrefixesWithColonOrEquals() {
        List<Recipient> recipients = SignatureRecipientParser.parse(
                "Tenant: Alice <alice@example.com>, Landlord=bob@example.com");
        assertThat(recipients).containsExactly(
                new Recipient("Tenant", "Alice", "alice@example.com", false),
                new Recipient("Landlord", null, "bob@example.com", false));
    }

    @Test
    void ccPrefixMarksACopyRecipient() {
        List<Recipient> recipients = SignatureRecipientParser.parse("cc: Dave <dave@example.com>");
        assertThat(recipients).containsExactly(new Recipient(null, "Dave", "dave@example.com", true));
    }

    @Test
    void parsesJsonArray() {
        List<Recipient> recipients = SignatureRecipientParser.parse("""
                [{"email":"a@example.com","name":"A","role":"Signer"},{"email":"b@example.com","cc":true},"c@example.com"]""");
        assertThat(recipients).containsExactly(
                new Recipient("Signer", "A", "a@example.com", false),
                new Recipient(null, null, "b@example.com", true),
                new Recipient(null, null, "c@example.com", false));
    }

    @Test
    void blankInputGivesNoRecipients() {
        assertThat(SignatureRecipientParser.parse(null)).isEmpty();
        assertThat(SignatureRecipientParser.parse("  ")).isEmpty();
    }

    @Test
    void rejectsEntriesWithoutAValidEmail() {
        assertThatThrownBy(() -> SignatureRecipientParser.parse("Alice Smith"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no email");
        assertThatThrownBy(() -> SignatureRecipientParser.parse("Alice <not-an-email>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid email");
    }
}
