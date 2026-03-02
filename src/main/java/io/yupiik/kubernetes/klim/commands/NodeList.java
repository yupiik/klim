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
package io.yupiik.kubernetes.klim.commands;

import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.kubernetes.klim.client.model.k8s.Node;
import io.yupiik.kubernetes.klim.client.model.k8s.Pod;
import io.yupiik.kubernetes.klim.client.model.k8s.Pods;
import io.yupiik.kubernetes.klim.configuration.CliKubernetesConfiguration;
import io.yupiik.kubernetes.klim.service.KubernetesFriend;
import io.yupiik.kubernetes.klim.service.NodeService;
import io.yupiik.kubernetes.klim.service.UnitFormatter;
import io.yupiik.kubernetes.klim.table.TableFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static java.util.Locale.US;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.stream.Collectors.groupingBy;

@Command(name = "node-list", description = "Simple listing of nodes with some well known label extraction.")
public class NodeList implements Runnable {
    private final Configuration configuration;
    private final KubernetesFriend kubernetesFriend;
    private final NodeService nodeService;

    public NodeList(final Configuration configuration, final KubernetesFriend kubernetesFriend, final NodeService nodeService) {
        this.configuration = configuration;
        this.kubernetesFriend = kubernetesFriend;
        this.nodeService = nodeService;
    }

    @Override
    public void run() {
        try (final var k8s = configuration.k8s().client()) {
            final var nodes = kubernetesFriend.findNodes(k8s).toCompletableFuture().get();
            System.out.println(new TableFormatter(formatAsLines(nodes)));
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<List<String>> formatAsLines(final List<Node> aggregated) {
        return Stream.concat(
                        Stream.of(List.of("node", "metadata")),
                        aggregated.stream()
                                .map(it -> Stream.of(it.metadata().name(), nodeService.metadata(it)).map(String::valueOf).toList()))
                .toList();
    }

    @RootConfiguration("-")
    public record Configuration(
            @Property(documentation = "How to connect to Kubernetes cluster.") CliKubernetesConfiguration k8s) {
    }

    private record AggregatedNode(Node node, List<Pod> pods, double cpuUsage, double memoryUsage) {
    }
}
