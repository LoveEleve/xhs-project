package com.myxhs.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 客户端配置
 * <p>
 * 使用 ES 8.x 官方 Java Client（co.elastic.clients），
 * 而非已废弃的 RestHighLevelClient。
 * </p>
 */
@Configuration
public class ElasticsearchConfig {

    @Value("${elasticsearch.uris:http://localhost:19200}")
    private String uris;

    @Value("${elasticsearch.username:elastic}")
    private String username;

    @Value("${elasticsearch.password:}")
    private String password;

    @Value("${elasticsearch.connect-timeout:5000}")
    private int connectTimeout;

    @Value("${elasticsearch.socket-timeout:30000}")
    private int socketTimeout;

    @Value("${elasticsearch.max-conn-total:100}")
    private int maxConnTotal;

    @Value("${elasticsearch.max-conn-per-route:50}")
    private int maxConnPerRoute;

    @Value("${elasticsearch.io-thread-count:4}")
    private int ioThreadCount;

    @Bean
    public RestClient restClient() {
        // 解析多个 URI（支持集群）
        String[] uriArray = uris.split(",");
        HttpHost[] hosts = new HttpHost[uriArray.length];
        for (int i = 0; i < uriArray.length; i++) {
            String uri = uriArray[i].trim();
            hosts[i] = HttpHost.create(uri);
        }

        // Basic Auth 凭据
        final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(username, password));

        RestClientBuilder builder = RestClient.builder(hosts)
                .setRequestConfigCallback(config -> config
                        .setConnectTimeout(connectTimeout)
                        .setSocketTimeout(socketTimeout))
                .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                        .setDefaultCredentialsProvider(credentialsProvider)
                        .setMaxConnTotal(maxConnTotal)
                        .setMaxConnPerRoute(maxConnPerRoute)
                        .setDefaultIOReactorConfig(IOReactorConfig.custom()
                                .setIoThreadCount(ioThreadCount)
                                .build()));

        return builder.build();
    }

    @Bean
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        // T-131 修复（运行态复核）：默认 JacksonJsonpMapper 未注册 JavaTimeModule，
        // 文档含 LocalDateTime（如 createdAt）时序列化抛 InvalidDefinitionException，
        // 导致商品增量/重建索引任务持续失败（实测 成功=0/3）。
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new RestClientTransport(restClient, new JacksonJsonpMapper(mapper));
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
