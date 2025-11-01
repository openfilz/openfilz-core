package org.openfilz.dms.config;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true")
public class OpenSearchConfig {

    @Value("${openfilz.full-text.opensearch.host}")
    private String host;

    @Value("${openfilz.full-text.opensearch.port}")
    private int port;

    @Value("${openfilz.full-text.opensearch.scheme}")
    private String scheme;

    @Value("${openfilz.full-text.opensearch.username:#{null}}")
    private String username;

    @Value("${openfilz.full-text.opensearch.password:#{null}}")
    private String password;

    @Bean
    public OpenSearchAsyncClient openSearchAsyncClient() {
        HttpHost httpHost = new HttpHost(scheme, host, port);
        final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        if (username != null && password != null) {
            credentialsProvider.setCredentials(new AuthScope(httpHost), new UsernamePasswordCredentials(username, password.toCharArray()));
        }

        // Utilisation de ApacheHttpClient5TransportBuilder pour un client HTTP asynchrone
        OpenSearchTransport transport = ApacheHttpClient5TransportBuilder
                .builder(httpHost)
                .setHttpClientConfigCallback(httpClientBuilder -> {
                    if (username != null && password != null) {
                        httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    }
                    // Vous pouvez configurer d'autres aspects du client HTTP ici
                    return httpClientBuilder;
                })
                .build();

        // Le OpenSearchClient (synchrone) est nécessaire pour obtenir le client asynchrone
        return new OpenSearchAsyncClient(transport);
    }


}