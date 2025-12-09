package org.openfilz.dms.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class CustomJacksonPartArgumentResolver implements HandlerMethodArgumentResolver {

    private final Jackson2JsonDecoder customDecoder;
    
    public CustomJacksonPartArgumentResolver(ObjectMapper objectMapper) {
        this.customDecoder = new Jackson2JsonDecoder(objectMapper);
    }
    
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CustomJsonPart.class);
    }
    
    @Override
    public @NotNull Mono<Object> resolveArgument(
            MethodParameter parameter,
            @NotNull BindingContext bindingContext,
            ServerWebExchange exchange) {
        
        CustomJsonPart annotation = parameter.getParameterAnnotation(CustomJsonPart.class);
        String partName = annotation.value().isEmpty() 
                ? parameter.getParameterName() 
                : annotation.value();
        
        ResolvableType type = ResolvableType.forMethodParameter(parameter);
        
        return exchange.getMultipartData()
                .flatMap(multipartData -> {
                    List<Part> parts = multipartData.get(partName);
                    
                    if (parts == null || parts.isEmpty()) {
                        return Mono.empty();
                    }
                    
                    Part part = parts.get(0);
                    
                    // Get the content from the part
                    return DataBufferUtils.join(part.content())
                            .flatMap(dataBuffer -> 
                                customDecoder.decodeToMono(
                                        Mono.just(dataBuffer),
                                        type,
                                        MediaType.APPLICATION_JSON,
                                        null
                                )
                            );
                });
    }
}