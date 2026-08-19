package org.openfilz.dms.config;

import tools.jackson.databind.json.JsonMapper;

/**
 * Extension hook applied to the application {@link JsonMapper} builder before it is built.
 * <p>
 * Jackson 3 mappers are immutable, so post-construction customization (e.g. the former
 * {@code ObjectMapper.registerSubtypes(...)}) is no longer possible. Editions or extensions
 * that need to contribute configuration (extra subtypes, modules, features) register a bean
 * implementing this interface; {@link BaseApiConfig} applies all of them at build time.
 */
@FunctionalInterface
public interface JsonMapperCustomizer {

    void customize(JsonMapper.Builder builder);
}
