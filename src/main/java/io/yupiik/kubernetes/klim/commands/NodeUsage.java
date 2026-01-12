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

@Command(name = "node-usage", description = "Show number of pod and resource usage per node, ideal to identify overallocated Karpenter nodes.")
public class NodeUsage implements Runnable {
    private final Configuration configuration;
    private final KubernetesFriend kubernetesFriend;
    private final UnitFormatter unitFormatter;

    public NodeUsage(final Configuration configuration, final KubernetesFriend kubernetesFriend, final UnitFormatter unitFormatter) {
        this.configuration = configuration;
        this.kubernetesFriend = kubernetesFriend;
        this.unitFormatter = unitFormatter;
    }

    @Override
    public void run() {
        try (final var k8s = configuration.k8s().client()) {
            final var nodes = kubernetesFriend.findNodes(k8s).toCompletableFuture();
            final var pods = kubernetesFriend.findNamespaces(k8s)
                    .thenCompose(namespaces -> findAllPodData(k8s, namespaces))
                    .toCompletableFuture();
            allOf(nodes, pods).get();

            final var podPerNode = pods.get().stream()
                    .filter(it -> it.spec().nodeName() != null)
                    .collect(groupingBy(it -> it.spec().nodeName()));
            final var aggregated = nodes.get().stream()
                    .map(it -> {
                        final var nodePods = podPerNode.getOrDefault(it.metadata().name(), List.of());
                        final var maxMem = unitFormatter.normalizeMemoryInBytes(it.status().allocatable().memory());
                        final var maxCpu = unitFormatter.normalizeCpuInCore(it.status().allocatable().cpu());
                        final var usedMem = nodePods.stream()
                                .filter(p -> p.status() != null && p.status().containerStatuses() != null)
                                .mapToLong(p -> p.status().containerStatuses().stream()
                                        .filter(s -> s.allocatedResources() != null)
                                        .mapToLong(s -> unitFormatter.normalizeMemoryInBytes(s.allocatedResources().memory()))
                                        .sum())
                                .sum();
                        final var usedCpu = nodePods.stream()
                                .filter(p -> p.status() != null && p.status().containerStatuses() != null)
                                .mapToDouble(p -> p.status().containerStatuses().stream()
                                        .filter(s -> s.allocatedResources() != null)
                                        .mapToDouble(s -> unitFormatter.normalizeCpuInCore(s.allocatedResources().cpu()))
                                        .sum())
                                .sum();
                        return new AggregatedNode(it, nodePods, maxCpu == 0 ? 0 : (usedCpu / maxCpu), maxMem == 0 ? 0 : (usedMem * 1. / maxMem));
                    })
                    .sorted(comparing(it -> -Math.max(it.cpuUsage(), it.memoryUsage())))
                    .toList();
            System.out.println(new TableFormatter(formatAsLines(aggregated)));
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<List<String>> formatAsLines(final List<AggregatedNode> aggregated) {
        return Stream.concat(
                        Stream.of(List.of("node", "#pods", "cpu usage", "memory usage", "metadata")),
                        aggregated.stream()
                                .map(it -> Stream.of(
                                                it.node().metadata().name(),
                                                it.pods().size(),
                                                String.format(US, "%.2f%%", it.cpuUsage() * 100),
                                                String.format(US, "%.2f%%", it.memoryUsage() * 100),
                                                metadata(it))
                                        .map(String::valueOf)
                                        .toList()))
                .toList();
    }

    private String metadata(final AggregatedNode node) {
        final var labels = node.node().metadata().labels();
        if (labels == null) {
            return "";
        }

        var instanceType = labels.get("node.kubernetes.io/instance-type");
        if (instanceType == null) {
            final var type = labels.get("karpenter.k8s.aws/instance-family");
            final var size = labels.get("karpenter.k8s.aws/instance-size");
            if (type != null || size != null) {
                instanceType = type + '.' + size;
            }
        }
        final var capacityType = labels.get("karpenter.sh/capacity-type");
        final var nodePool = labels.get("karpenter.sh/nodepool");

        if (instanceType == null && capacityType == null && nodePool == null) {
            return "";
        }

        final var result = new StringBuilder();
        if (instanceType != null) {
            result.append("instance=").append(instanceType);
        }
        if (capacityType != null) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append("type=").append(capacityType);
        }
        if (nodePool != null) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append("pool=").append(nodePool);
        }
        return result.toString();
    }

    private CompletableFuture<List<Pod>> findAllPodData(final KubernetesClient k8s, final List<String> namespaces) {
        final var tops = new ArrayList<Pod>();
        return allOf(namespaces.stream()
                .map(namespace -> findPods(k8s, namespace)
                        .thenApply(res -> {
                            synchronized (tops) {
                                tops.addAll(res.items());
                            }
                            return res;
                        }))
                .toArray(CompletableFuture<?>[]::new))
                .thenApply(ignored -> tops);
    }

    private CompletionStage<Pods> findPods(final KubernetesClient k8s, final String namespace) {
        return kubernetesFriend.fetch( // todo: move to pagination
                k8s, "/api/v1/namespaces/" + namespace + "/pods?" +
                        "limit=1000&" +
                        "fieldSelector=status.phase%3DRunning",
                "Invalid pods response: ", Pods.class);
    }

    @RootConfiguration("-")
    public record Configuration(
            @Property(documentation = "How to connect to Kubernetes cluster.") CliKubernetesConfiguration k8s) {
    }

    private record AggregatedNode(Node node, List<Pod> pods, double cpuUsage, double memoryUsage) {
    }
}
