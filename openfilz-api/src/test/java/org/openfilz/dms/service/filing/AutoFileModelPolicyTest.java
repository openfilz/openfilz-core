package org.openfilz.dms.service.filing;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.ai.DocumentTextHandoff;
import org.openfilz.dms.service.insight.InsightsPolicy;
import org.openfilz.dms.service.insight.InsightsPolicy.Verdict;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model stage of smart filing obeys the same {@link InsightsPolicy} verdict as the enrichment
 * worker: a document whose text no model may read, or whose kind is kept away from the model, is
 * never sent to it to choose a folder.
 */
class AutoFileModelPolicyTest {

    private final Document document = Document.builder().id(UUID.randomUUID()).name("scan.pdf").build();

    @Test
    void noPolicyLetsTheModelDecide() {
        assertThat(service(null).modelBarredReason(document, "invoice")).isNull();
    }

    @Test
    void aPermissivePolicyLetsTheModelDecide() {
        assertThat(service(policy(Verdict.permitAll())).modelBarredReason(document, "invoice")).isNull();
    }

    @Test
    void aModelBanKeepsTheTextAwayFromTheModel() {
        Verdict noModel = new Verdict(true, false, Set.of(), "local classifiers only");
        assertThat(service(policy(noModel)).modelBarredReason(document, "invoice")).isNotNull();
    }

    @Test
    void aBlockedKindKeepsTheTextAwayFromTheModel() {
        Verdict blocked = new Verdict(true, true, Set.of("ID Document"), null);
        DefaultAutoFileService service = service(policy(blocked));
        assertThat(service.modelBarredReason(document, "id-document")).contains("id-document");
        assertThat(service.modelBarredReason(document, "invoice")).isNull();
    }

    @Test
    void anUnknownKindIsNotSentWhileSomeKindsAreBlocked() {
        Verdict blocked = new Verdict(true, true, Set.of("id-document"), null);
        assertThat(service(policy(blocked)).modelBarredReason(document, null)).isNotNull();
        assertThat(service(policy(Verdict.permitAll())).modelBarredReason(document, null)).isNull();
    }

    @Test
    void aFailingLookupFailsClosed() {
        InsightsPolicy failing = new InsightsPolicy() {
            @Override
            public Mono<Verdict> forDocument(Document document) {
                return Mono.error(new IllegalStateException("database down"));
            }
        };
        assertThat(service(failing).modelBarredReason(document, "invoice")).isNotNull();
    }

    private static InsightsPolicy policy(Verdict verdict) {
        return new InsightsPolicy() {
            @Override
            public Mono<Verdict> forDocument(Document document) {
                return Mono.just(verdict);
            }
        };
    }

    private static DefaultAutoFileService service(InsightsPolicy policy) {
        AiProperties properties = new AiProperties();
        DefaultAutoFileService service = new DefaultAutoFileService(properties, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, new DocumentTextHandoff(properties), null, null);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (policy != null) {
            beans.addBean("insightsPolicy", policy);
        }
        ObjectProvider<InsightsPolicy> provider = beans.getBeanProvider(InsightsPolicy.class);
        service.setInsightsPolicyProvider(provider);
        return service;
    }
}
