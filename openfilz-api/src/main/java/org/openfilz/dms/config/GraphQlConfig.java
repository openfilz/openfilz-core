package org.openfilz.dms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.scalars.ExtendedScalars;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.repository.graphql.DocumentDataFetcher;
import org.openfilz.dms.repository.graphql.ListFolderCountDataFetcher;
import org.openfilz.dms.repository.graphql.ListFolderCriteria;
import org.openfilz.dms.repository.graphql.ListFolderDataFetcher;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.r2dbc.core.DatabaseClient;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import static org.openfilz.dms.config.GraphQlQueryConfig.*;

@RequiredArgsConstructor
@Configuration
@Slf4j
public class GraphQlConfig {

    @Value("${features.graphql.data-fetcher.list:#{null}}")
    private String customListFolderDataFetcherClass;

    @Value("${features.graphql.data-fetcher.count:#{null}}")
    private String customListFolderCountDataFetcherClass;

    @Value("${features.graphql.data-fetcher.document:#{null}}")
    private String customDocumentDataFetcherClass;

    private final DatabaseClient databaseClient;

    private final DocumentMapper mapper;

    private final ObjectMapper  objectMapper;

    private final SqlUtils sqlUtils;

    private final ListFolderCriteria listFolderCriteria;


    @Bean
    public RuntimeWiringConfigurer runtimeWiringConfigurer() {
        return wiringBuilder -> wiringBuilder
                .scalar(ExtendedScalars.Json)
                .scalar(ExtendedScalars.UUID)
                .scalar(ExtendedScalars.GraphQLLong)
                .scalar(ExtendedScalars.DateTime)
                .type(QUERY, builder -> builder.dataFetchers(Map.of(
                        LIST_FOLDER, getListFolderDataFetcher(),
                        LIST_FOLDER_COUNT, getListFolderCountDataFetcher(),
                        DOCUMENT_BY_ID, getDocumentDataFetcher())));
    }

    private ListFolderDataFetcher getListFolderDataFetcher() {
        try {
            return customListFolderDataFetcherClass == null ? new ListFolderDataFetcher(databaseClient, mapper, objectMapper, sqlUtils, listFolderCriteria)
                    : (ListFolderDataFetcher) getConstructor(customListFolderDataFetcherClass)
                    .newInstance(databaseClient, mapper, objectMapper, sqlUtils, listFolderCriteria);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private Constructor<?> getConstructor(String className) throws ClassNotFoundException {
        log.info("Loading custom class '{}'", className);
        return Class.forName(className).getConstructors()[0];
    }

    private ListFolderCountDataFetcher getListFolderCountDataFetcher() {
        try {
            return customListFolderCountDataFetcherClass == null ? new ListFolderCountDataFetcher(databaseClient, mapper, objectMapper, sqlUtils, listFolderCriteria)
                    : (ListFolderCountDataFetcher) getConstructor(customListFolderCountDataFetcherClass)
                    .newInstance(databaseClient, mapper, objectMapper, sqlUtils, listFolderCriteria);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private DocumentDataFetcher getDocumentDataFetcher() {
        try {
            return customDocumentDataFetcherClass == null ? new DocumentDataFetcher(databaseClient, mapper, objectMapper, sqlUtils)
                    : (DocumentDataFetcher) getConstructor(customDocumentDataFetcherClass)
                    .newInstance(databaseClient, mapper, objectMapper, sqlUtils);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }


}