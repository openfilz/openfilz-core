package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.request.ListAiModelsRequest;
import org.openfilz.dms.dto.request.SaveAiSettingsRequest;
import org.openfilz.dms.dto.response.AiModelsResponse;
import org.openfilz.dms.entity.UserAiSettings;
import org.openfilz.dms.enums.AiProvider;
import org.openfilz.dms.repository.UserAiSettingsRepository;
import org.openfilz.dms.service.ai.AiBaseUrlPolicy;
import org.openfilz.dms.service.ai.AiModelDirectory;
import org.openfilz.dms.service.ai.UserChatClientResolver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The base URL of a user's own OpenAI-compatible server goes through {@link AiBaseUrlPolicy} on
 * every path that would call it: save, test connection, model listing, and the build of the user's
 * model from the stored row. A refused address is never called.
 */
class AiSettingsBaseUrlPolicyTest {

    private static final String USER = "me@example.com";
    private static final String REFUSED = "http://10.0.0.5:8080/v1";
    private static final String ALLOWED = "https://llm.example.com/v1";

    /** Refuses one address, trims the others. */
    private final AiBaseUrlPolicy policy = url -> {
        if (url.trim().equals(REFUSED)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "this address is not allowed");
        }
        return url.trim();
    };

    private final UserAiSettingsRepository repository = mock(UserAiSettingsRepository.class);
    private final AiSettingsCipher cipher = mock(AiSettingsCipher.class);
    private final UserChatClientResolver resolver = mock(UserChatClientResolver.class);
    private final AiModelDirectory directory = mock(AiModelDirectory.class);
    private AiSettingsServiceImpl service;

    @BeforeEach
    void setUp() {
        when(resolver.isUserSettingsEnabled()).thenReturn(true);
        when(repository.findById(USER)).thenReturn(Mono.empty());
        when(cipher.encrypt(any())).thenReturn("encrypted");
        when(cipher.decrypt(any())).thenReturn("sk-test-0123");
        service = new AiSettingsServiceImpl(repository, cipher, resolver, directory, new MockEnvironment(), policy);
    }

    private static SaveAiSettingsRequest request(AiProvider provider, String baseUrl) {
        return new SaveAiSettingsRequest(provider, "some-model", baseUrl, "sk-test-0123");
    }

    private static void isBadRequest(Throwable e) {
        assertThat(e).isInstanceOfSatisfying(ResponseStatusException.class,
                refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void aRefusedAddressIsNotSaved() {
        StepVerifier.create(service.saveSettings(USER, request(AiProvider.OPENAI_COMPATIBLE, REFUSED)))
                .expectErrorSatisfies(AiSettingsBaseUrlPolicyTest::isBadRequest).verify();
        verify(repository, never()).save(any());
    }

    @Test
    void anAllowedAddressIsSavedAsThePolicyReturnsIt() {
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        StepVerifier.create(service.saveSettings(USER, request(AiProvider.OPENAI_COMPATIBLE, " " + ALLOWED + " ")))
                .assertNext(saved -> assertThat(saved.baseUrl()).isEqualTo(ALLOWED)).verifyComplete();
    }

    @Test
    void aRefusedAddressIsNotTested() {
        StepVerifier.create(service.testConnection(USER, request(AiProvider.OPENAI_COMPATIBLE, REFUSED)))
                .expectErrorSatisfies(AiSettingsBaseUrlPolicyTest::isBadRequest).verify();
        verify(resolver, never()).buildChatModel(any(), any(), any(), any());
    }

    @Test
    void aRefusedAddressIsNotAskedForItsModels() {
        StepVerifier.create(service.listModels(USER,
                        new ListAiModelsRequest(AiProvider.OPENAI_COMPATIBLE, REFUSED, "sk-test-0123")))
                .assertNext(answer -> {
                    assertThat(answer.source()).isEqualTo(AiModelsResponse.Source.FALLBACK);
                    assertThat(answer.message()).isEqualTo("this address is not allowed");
                }).verifyComplete();
        verify(directory, never()).listChatModels(any(), any(), any());
    }

    @Test
    void aProviderWithAFixedAddressIsNeverAskedElsewhere() {
        when(directory.listChatModels(any(), any(), any())).thenReturn(Mono.just(List.of("gpt-test")));
        StepVerifier.create(service.listModels(USER, new ListAiModelsRequest(AiProvider.OPENAI, REFUSED, "sk-test-0123")))
                .assertNext(answer -> assertThat(answer.source()).isEqualTo(AiModelsResponse.Source.LIVE))
                .verifyComplete();
        verify(directory).listChatModels(eq(AiProvider.OPENAI), eq("sk-test-0123"), isNull());
    }

    @Test
    void aStoredAddressIsCheckedAgainWhenTheModelIsBuilt() {
        when(repository.findById(USER)).thenReturn(Mono.just(UserAiSettings.builder()
                .userEmail(USER).provider(AiProvider.OPENAI_COMPATIBLE.name()).model("some-model")
                .baseUrl(REFUSED).apiKeyEncrypted("encrypted").build()));
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> noModel = mock(ObjectProvider.class);
        UserChatClientResolver real = new UserChatClientResolver(noModel,
                mock(ToolCallingManager.class), repository, cipher, new MockEnvironment(), true, null, policy);

        StepVerifier.create(real.resolve(USER))
                .expectErrorSatisfies(AiSettingsBaseUrlPolicyTest::isBadRequest).verify();
    }
}
