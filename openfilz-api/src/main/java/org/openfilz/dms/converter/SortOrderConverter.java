package org.openfilz.dms.converter;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.enums.SortOrder;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

@Configuration
@RequiredArgsConstructor
@ConfigurationPropertiesBinding
public class SortOrderConverter implements Converter<String, SortOrder> {
    @Override
    public SortOrder convert(String source) {
        try {
            return SortOrder.valueOf(source.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        }
    }
}