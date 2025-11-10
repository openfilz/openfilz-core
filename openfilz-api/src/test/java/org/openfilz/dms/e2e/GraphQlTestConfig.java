package org.openfilz.dms.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.codec.json.Jackson2JsonEncoder;

@TestConfiguration
public class GraphQlTestConfig {

    /**
     * Creates a custom ObjectMapper to serialize OffsetDateTime as ISO-8601 strings.
     */
    @Bean
    public ObjectMapper customGraphQlObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // The key setting: disable writing dates as epoch timestamps
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * Creates a custom encoder using the configured ObjectMapper.
     */
    @Bean
    public Jackson2JsonEncoder customJackson2JsonEncoder(ObjectMapper customGraphQlObjectMapper) {
        // Pass the custom ObjectMapper to the encoder
        return new Jackson2JsonEncoder(customGraphQlObjectMapper);
    }
}