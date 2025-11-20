export const environment = {
    apiURL: import.meta.env['NG_APP_API_URL'],
    graphQlURL: import.meta.env['NG_APP_GRAPHQL_URL'],
    authentication: {
        authority: import.meta.env['NG_APP_AUTHENTICATION_AUTHORITY'],
        clientId: import.meta.env['NG_APP_AUTHENTICATION_CLIENT_ID'],
        enabled: import.meta.env['NG_APP_AUTHENTICATION_ENABLED'] === 'true'
    }
};