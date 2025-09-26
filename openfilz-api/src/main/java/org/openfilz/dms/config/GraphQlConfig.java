package org.openfilz.dms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.scalars.ExtendedScalars;
import graphql.schema.idl.TypeRuntimeWiring;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.repository.graphql.DocumentDataFetcher;
import org.openfilz.dms.repository.graphql.ListFolderCountDataFetcher;
import org.openfilz.dms.repository.graphql.ListFolderCriteria;
import org.openfilz.dms.repository.graphql.ListFolderDataFetcher;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.r2dbc.core.DatabaseClient;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.openfilz.dms.config.GraphQlQueryConfig.*;

@RequiredArgsConstructor
@Configuration
@Slf4j
@ConditionalOnProperty(name = "features.custom-access", matchIfMissing = true, havingValue = "false")
public class GraphQlConfig {

    protected final DatabaseClient databaseClient;

    protected final DocumentMapper mapper;

    protected final ObjectMapper  objectMapper;

    protected final SqlUtils sqlUtils;

    protected final ListFolderCriteria listFolderCriteria;


    @Bean
    public RuntimeWiringConfigurer runtimeWiringConfigurer() {
        return wiringBuilder -> wiringBuilder
                .scalar(ExtendedScalars.Json)
                .scalar(ExtendedScalars.UUID)
                .scalar(ExtendedScalars.GraphQLLong)
                .scalar(ExtendedScalars.DateTime)
                .type(QUERY, getDataFectchers());
    }

    protected UnaryOperator<TypeRuntimeWiring.Builder> getDataFectchers() {
        return builder -> builder.dataFetchers(Map.of(
                LIST_FOLDER, getListFolderDataFetcher(),
                LIST_FOLDER_COUNT, getListFolderCountDataFetcher(),
                DOCUMENT_BY_ID, getDocumentDataFetcher()));
    }

    protected ListFolderDataFetcher getListFolderDataFetcher() {
        return new ListFolderDataFetcher(databaseClient, mapper, objectMapper, sqlUtils, listFolderCriteria);
    }

    protected ListFolderCountDataFetcher getListFolderCountDataFetcher() {
        return new ListFolderCountDataFetcher(databaseClient, mapper, objectMapper, sqlUtils, listFolderCriteria);
    }

    protected DocumentDataFetcher getDocumentDataFetcher() {
        return new DocumentDataFetcher(databaseClient, mapper, objectMapper, sqlUtils);
    }


}