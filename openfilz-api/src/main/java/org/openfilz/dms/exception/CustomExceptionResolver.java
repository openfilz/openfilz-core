package org.openfilz.dms.exception;

import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebExchangeBindException;

@Component
@Slf4j
public class CustomExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        log.warn("Exception caught", ex);
        return GraphqlErrorBuilder.newError()
          .errorType(getErrorType(ex))
          .message(ex.getMessage())
          .path(env.getExecutionStepInfo().getPath())
          .location(env.getField().getSourceLocation())
          .build();
    }

    private ErrorClassification getErrorType(Throwable ex) {
        if(ex instanceof IllegalArgumentException || ex instanceof WebExchangeBindException || ex instanceof AuditException) {
            return ErrorType.BAD_REQUEST;
        }
        if(ex instanceof DocumentNotFoundException) {
            return ErrorType.NOT_FOUND;
        }
        if(ex instanceof OperationForbiddenException || ex instanceof AccessDeniedException) {
            return ErrorType.FORBIDDEN;
        }
        return ErrorType.INTERNAL_ERROR;
    }

}