package org.openfilz.dms.service;

import reactor.core.publisher.Flux;

import java.io.InputStream;

public interface TikaService {

    Flux<String> extractTextAsFlux(InputStream inputStream);

}
