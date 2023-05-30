package io.yupiik.kubernetes.klim.service;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.kubernetes.klim.client.model.Metadata;
import io.yupiik.kubernetes.klim.client.model.Namespace;
import io.yupiik.kubernetes.klim.client.model.Namespaces;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import static java.net.http.HttpResponse.BodyHandlers.ofString;

@ApplicationScoped
public class KubernetesFriend {
    private final JsonMapper jsonMapper;

    public KubernetesFriend(final JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public CompletionStage<List<String>> findNamespaces(final KubernetesClient client) {
        return fetch(client, "/api/v1/namespaces?limit=1000", "Invalid namespace response: ", Namespaces.class)
                .thenApply(n -> n.items().stream()
                        .map(Namespace::metadata)
                        .filter(Objects::nonNull)
                        .map(Metadata::name)
                        .sorted()
                        .toList());
    }

    public <T> CompletionStage<T> fetch(final KubernetesClient client, final String path, final String error, final Class<T> expected) {
        return client.sendAsync(
                        HttpRequest.newBuilder()
                                .header("accept", "application/json")
                                .uri(URI.create("https://kubernetes.api" + path))
                                .timeout(Duration.ofMinutes(1))
                                .build(),
                        ofString())
                .thenApplyAsync(res -> {
                    validateResponse(res, error);
                    return jsonMapper.fromString(expected, res.body());
                });
    }

    private void validateResponse(final HttpResponse<String> res, final String x) {
        if (res.statusCode() != 200) {
            throw new IllegalStateException(x + res + "\n" + res.body());
        }
    }
}
