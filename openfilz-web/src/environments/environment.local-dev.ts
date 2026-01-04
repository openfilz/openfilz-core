export const environment = {
    apiURL: import.meta.env['NG_APP_API_URL'] ?? 'http://localhost:8081/api/v1',
    graphQlURL: import.meta.env['NG_APP_GRAPHQL_URL'] ?? 'http://localhost:8081/graphql/v1',
    authentication: {
        authority: import.meta.env['NG_APP_AUTHENTICATION_AUTHORITY'] ?? 'http://localhost:8080/realms/your-realm',
        clientId: import.meta.env['NG_APP_AUTHENTICATION_CLIENT_ID'] ?? 'openfilz-web',
        enabled: false
    },
    onlyOffice: {
        enabled: true
    }
};
