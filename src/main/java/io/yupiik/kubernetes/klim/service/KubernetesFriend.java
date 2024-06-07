/*
 * Copyright (c) 2024 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.kubernetes.klim.service;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.kubernetes.klim.client.model.k8s.Metadata;
import io.yupiik.kubernetes.klim.client.model.k8s.Namespace;
import io.yupiik.kubernetes.klim.client.model.k8s.Namespaces;

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
