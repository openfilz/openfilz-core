package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.service.language.DocxLanguage;
import org.openfilz.dms.service.language.TestDocx;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractOnlyOfficeServiceLanguageTest {

    @Test
    void anEnglishDefaultWithoutTheNormalStyleIsRewritten() {
        // Set before the Normal style carried it: OnlyOffice would drop it again at the next save
        var inspection = new DocxLanguage.Inspection("en-US", null, true, Map.of("en-US", 1), "", true);

        assertThat(AbstractOnlyOfficeService.isAlreadySet(inspection, "en-US")).isFalse();
    }

    @Test
    void defaultAndNormalStyleInTheLanguageAreAlreadySet() {
        var inspection = new DocxLanguage.Inspection("en-US", "en-US", true, Map.of("en-US", 2, "fr-FR", 1), "", true);

        assertThat(AbstractOnlyOfficeService.isAlreadySet(inspection, "en-US")).isTrue();
    }

    @Test
    void withoutANormalStyleTheDefaultIsEnough() {
        var inspection = new DocxLanguage.Inspection("fr-FR", null, false, Map.of("fr-FR", 1), "", true);

        assertThat(AbstractOnlyOfficeService.isAlreadySet(inspection, "fr-FR")).isTrue();
    }

    @Test
    void theNormalStyleDeclaresTheLanguageWhenTheDefaultIsGone() {
        var inspection = new DocxLanguage.Inspection(null, "en-US", true, Map.of("en-US", 1), TestDocx.ENGLISH, true);

        assertThat(AbstractOnlyOfficeService.languageInfo(inspection, true).suggested()).isFalse();
    }
}
