package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.request.ListAiModelsRequest;
import org.openfilz.dms.dto.request.SaveAiSettingsRequest;
import org.openfilz.dms.dto.response.AiModelsResponse;
import org.openfilz.dms.entity.UserAiSettings;
import org.openfilz.dms.enums.AiProvider;
import org.openfilz.dms.exception.AiKeyUnreadableException;
import org.openfilz.dms.repository.UserAiSettingsRepository;
import org.openfilz.dms.service.ai.AiModelDirectory;
import org.openfilz.dms.service.ai.UserChatClientResolver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A stored personal key that no configured encryption key opens (the key was replaced without listing the
 * old one as a previous key): the settings still load and say so, the chat and the test answer the typed
 * error (409 AI_KEY_UNREADABLE), saving without a new key is refused, saving with one repairs it.
 */
class AiSettingsKeyUnreadableTest {

    private static final String USER = "me@example.com";

    private final UserAiSettingsRepository repository = mock(UserAiSettingsRepository.class);
    private final AiSettingsCipher cipher = mock(AiSettingsCipher.class);
    private final UserChatClientResolver resolver = mock(UserChatClientResolver.class);
    private final AiModelDirectory directory = mock(AiModelDirectory.class);
    private AiSettingsServiceImpl service;

    private static UserAiSettings stored() {
        return UserAiSettings.builder().userEmail(USER).provider(AiProvider.OPENAI.name()).model("gpt-test")
                .apiKeyEncrypted("v2:deadbeef:AAAA").build();
    }

    @BeforeEach
    void setUp() {
        when(resolver.isUserSettingsEnabled()).thenReturn(true);
        when(repository.findById(USER)).thenReturn(Mono.just(stored()));
        when(cipher.decrypt(any())).thenThrow(new AiKeyUnreadableException("no key opens it"));
        when(cipher.encrypt(any())).thenReturn("v2:0a1b2c3d:BBBB");
        service = new AiSettingsServiceImpl(repository, cipher, resolver, directory, new MockEnvironment(), url -> url);
    }

    @Test
    void settingsStillLoad_andSayTheKeyIsUnreadable() {
        StepVerifier.create(service.getSettings(USER))
                .assertNext(settings -> {
                    assertThat(settings.hasApiKey()).isTrue();
                    assertThat(settings.keyUnreadable()).isTrue();
                    assertThat(settings.keySuffix()).isEmpty();
                    assertThat(settings.provider()).isEqualTo("OPENAI");
                }).verifyComplete();
    }

    @Test
    void testWithTheStoredKey_isTheTypedError() {
        StepVerifier.create(service.testConnection(USER, new SaveAiSettingsRequest(AiProvider.OPENAI, "gpt-test", null, null)))
                .expectError(AiKeyUnreadableException.class).verify();
        verify(resolver, never()).buildChatModel(any(), any(), any(), any());
    }

    @Test
    void savingWithoutANewKey_isRefused() {
        StepVerifier.create(service.saveSettings(USER, new SaveAiSettingsRequest(AiProvider.OPENAI, "gpt-other", null, null)))
                .expectError(AiKeyUnreadableException.class).verify();
        verify(repository, never()).save(any());
    }

    @Test
    void savingWithANewKey_repairsIt() {
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        org.mockito.Mockito.doReturn("sk-new-key-1234").when(cipher).decrypt("v2:0a1b2c3d:BBBB");

        StepVerifier.create(service.saveSettings(USER, new SaveAiSettingsRequest(AiProvider.OPENAI, "gpt-test", null, "sk-new-key-1234")))
                .assertNext(saved -> {
                    assertThat(saved.keyUnreadable()).isFalse();
                    assertThat(saved.keySuffix()).isEqualTo("1234");
                }).verifyComplete();
    }

    @Test
    void modelListing_treatsTheUnreadableKeyAsNone() {
        StepVerifier.create(service.listModels(USER, new ListAiModelsRequest(AiProvider.OPENAI, null, null)))
                .assertNext(answer -> assertThat(answer.source()).isEqualTo(AiModelsResponse.Source.FALLBACK))
                .verifyComplete();
        verify(directory, never()).listChatModels(any(), any(), any());
    }

    @Test
    void theChatResolution_failsWithTheTypedError() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> noModel = mock(ObjectProvider.class);
        UserChatClientResolver real = new UserChatClientResolver(noModel,
                mock(ToolCallingManager.class), repository, cipher, new MockEnvironment(), true, null, url -> url);

        StepVerifier.create(real.resolve(USER)).expectError(AiKeyUnreadableException.class).verify();
    }
}
