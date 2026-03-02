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
import io.yupiik.kubernetes.klim.client.model.k8s.Metadata;
import io.yupiik.kubernetes.klim.client.model.k8s.Pod;
import io.yupiik.kubernetes.klim.client.model.k8s.Pods;
import io.yupiik.kubernetes.klim.client.model.k8s.TopPod;
import io.yupiik.kubernetes.klim.client.model.k8s.TopPods;
import io.yupiik.kubernetes.klim.configuration.CliKubernetesConfiguration;
import io.yupiik.kubernetes.klim.service.KubernetesFriend;
import io.yupiik.kubernetes.klim.service.UnitFormatter;
import io.yupiik.kubernetes.klim.service.resource.ContainerResources;
import io.yupiik.kubernetes.klim.table.TableFormatter;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.yupiik.kubernetes.klim.service.resource.ContainerResources.NoteLevel.WARNING;
import static io.yupiik.kubernetes.klim.service.resource.ContainerResources.ResourceType.CPU;
import static io.yupiik.kubernetes.klim.service.resource.ContainerResources.ResourceType.MEMORY;
import static java.util.Comparator.comparing;
import static java.util.Locale.ROOT;
import static java.util.Locale.US;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

@Command(name = "pod-resources", description = "Lists and show resource usages compared to requests/limits set in descriptors.")
public class PodResources implements Runnable {
    private final Configuration configuration;
    private final KubernetesFriend kubernetesFriend;
    private final UnitFormatter unitFormatter;

    public PodResources(final Configuration configuration, final KubernetesFriend kubernetesFriend, final UnitFormatter unitFormatter) {
        this.configuration = configuration;
        this.kubernetesFriend = kubernetesFriend;
        this.unitFormatter = unitFormatter;
    }

    @Override
    public void run() {
        try (final var k8s = configuration.k8s().client()) {
            final var data = kubernetesFriend.findNamespaces(k8s)
                    .thenCompose(namespaces -> findAllPodData(k8s, namespaces))
                    .toCompletableFuture()
                    .get();
            final var lines = data.stream()
                    .flatMap(this::toData)
                    .sorted(comparing(ContainerResources::namespace)
                            .thenComparing(ContainerResources::pod))
                    .toList();

            final var includeUnits = configuration.format() != UnitFormatter.Format.CSV;
            final var cpuFormat = new DecimalFormat("##.###", DecimalFormatSymbols.getInstance(US));
            final var outputLines = Stream.concat(
                            Stream.of(Stream.concat(
                                            Stream.of("namespace", "pod", "container", "cpu req.", "cpu limit", "cpu usage", "mem. req.", "mem limit", "mem usage"),
                                            configuration.showComments() ? Stream.of("comments") : Stream.empty())
                                    .toList()),
                            lines.stream()
                                    .map(it -> Stream.concat(
                                                    Stream.of(
                                                            it.namespace(), it.pod(), it.container(),
                                                            unitFormatter.formatCpu(it.cpuRequest(), cpuFormat),
                                                            unitFormatter.formatCpu(it.cpuLimit(), cpuFormat),
                                                            unitFormatter.formatCpu(it.cpuUsage(), cpuFormat),
                                                            unitFormatter.formatMemory(it.memoryRequest(), includeUnits, configuration.memoryUnit()),
                                                            unitFormatter.formatMemory(it.memoryLimit(), includeUnits, configuration.memoryUnit()),
                                                            unitFormatter.formatMemory(it.memoryUsage(), includeUnits, configuration.memoryUnit())),
                                                    configuration.showComments() ?
                                                            Stream.of(it.notes().stream().map(this::format).collect(joining(" "))) :
                                                            Stream.empty())
                                            .map(String::valueOf)
                                            .toList()))
                    .toList();
            System.out.println(switch (configuration.format()) {
                case CSV -> outputLines.stream()
                        .map(l -> String.join(";", l))
                        .collect(joining("\n", "", "\n"));
                case TABLE -> new TableFormatter(outputLines);
            });
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String format(final ContainerResources.Note note) {
        return note.level().name().toLowerCase(ROOT) + ": " + note.resource().name().toLowerCase(ROOT) + " " + note.message();
    }

    private Stream<ContainerResources> toData(final PodData podData) {
        final var tops = ofNullable(podData.top())
                .map(TopPod::containers)
                .map(Collection::stream)
                .map(s -> {
                    final var ct = new AtomicInteger();
                    return s.collect(toMap(
                            c -> ofNullable(c.name()).orElseGet(() -> "container-" + ct.incrementAndGet()),
                            identity()));
                })
                .orElse(Map.of());
        final var pods = ofNullable(podData.pod())
                .map(Pod::spec)
                .map(Pod.Spec::containers)
                .map(Collection::stream)
                .map(s -> {
                    final var ct = new AtomicInteger();
                    return s.collect(toMap(
                            c -> ofNullable(c.name()).orElseGet(() -> "container-" + ct.incrementAndGet()),
                            identity()));
                })
                .orElse(Map.of());
        final var meta = ofNullable(podData.pod())
                .map(Pod::metadata)
                .or(() -> ofNullable(podData.top())
                        .map(TopPod::metadata)
                        .map(m -> new Metadata(
                                m.name(), m.namespace(),
                                Metadata.EMTPY.labels(), Metadata.EMTPY.annotations(), Metadata.EMTPY.creationTimestamp(), Metadata.EMTPY.resourceVersion(), Metadata.EMTPY.uid())))
                .orElse(Metadata.EMTPY);
        return Stream.concat(
                        tops.keySet().stream(),
                        pods.keySet().stream())
                .distinct()
                .map(container -> {
                    final var top = ofNullable(tops.get(container))
                            .map(TopPod.Container::usage)
                            .orElse(TopPod.Usage.EMPTY);
                    final var pod = ofNullable(pods.get(container))
                            .map(Pod.Container::resources)
                            .orElse(Pod.Resources.EMPTY);

                    final var cpuRequest = pod.requests() == null ? -1 : unitFormatter.normalizeCpuInCore(pod.requests().cpu());
                    final var cpuLimit = pod.limits() == null ? -1 : unitFormatter.normalizeCpuInCore(pod.limits().cpu());
                    final var cpu = unitFormatter.normalizeCpuInCore(top.cpu());

                    final var memoryRequest = pod.requests() == null ? -1 : unitFormatter.normalizeMemoryInBytes(pod.requests().memory());
                    final var memoryLimit = pod.limits() == null ? -1 : unitFormatter.normalizeMemoryInBytes(pod.limits().memory());
                    final var memory = unitFormatter.normalizeMemoryInBytes(top.memory());

                    return new ContainerResources(
                            meta.namespace(), meta.name(), container,
                            cpuRequest, memoryRequest,
                            cpuLimit, memoryLimit,
                            cpu, memory,
                            computeHints(cpuRequest, cpuLimit, cpu, memoryRequest, memoryLimit, memory));
                });
    }

    private List<ContainerResources.Note> computeHints(
            final double cpuRequest, final double cpuLimit, final double cpu,
            final long memoryRequest, final long memoryLimit, final long memory) {
        final var notes = new ArrayList<ContainerResources.Note>();

        if (configuration.limitBound() >= 0) {
            if (cpu > 0 && cpuLimit > 0 && cpu * configuration.limitBound() >= cpuLimit) {
                notes.add(new ContainerResources.Note(WARNING, CPU, "over " + (configuration.limitBound() * 100) + "%."));
            }
            if (memory > 0 && memoryLimit > 0 && memory * configuration.limitBound() >= memoryLimit) {
                notes.add(new ContainerResources.Note(WARNING, MEMORY, "over " + (configuration.limitBound() * 100) + "%."));
            }
        }
        if (configuration.requestBound() >= 0) {
            if (cpu > 0 && cpuRequest > 0 && cpu * configuration.requestBound() >= cpuRequest) {
                notes.add(new ContainerResources.Note(WARNING, CPU, "over " + (configuration.requestBound() * 100) + "%."));
            }
            if (memory > 0 && memoryRequest > 0 && memory * configuration.requestBound() >= memoryRequest) {
                notes.add(new ContainerResources.Note(WARNING, MEMORY, "over " + (configuration.requestBound() * 100) + "%."));
            }
        }

        return notes;
    }

    private CompletionStage<List<PodData>> findPodData(final KubernetesClient k8s, final String namespace) {
        return findTopPerNamespace(k8s, namespace)
                .thenCompose(namespaceTops -> namespaceTops.items().isEmpty() ?
                        completedFuture(List.of()) :
                        findPods(k8s, namespace)
                                .thenApply(pods -> {
                                    final var indexedPods = pods.items().stream().collect(toMap(i -> i.metadata().name(), identity()));
                                    return namespaceTops.items().stream()
                                            .map(it -> new PodData(it, indexedPods.get(it.metadata().name())))
                                            .filter(it -> it.pod() != null)
                                            .toList();
                                }));
    }

    private CompletableFuture<List<PodData>> findAllPodData(final KubernetesClient k8s, final List<String> namespaces) {
        final var tops = new ArrayList<PodData>();
        return allOf(namespaces.stream()
                .map(namespace -> findPodData(k8s, namespace)
                        .thenApply(res -> {
                            synchronized (tops) {
                                tops.addAll(res);
                            }
                            return res;
                        }))
                .toArray(CompletableFuture<?>[]::new))
                .thenApply(ignored -> tops);
    }

    private CompletionStage<Pods> findPods(final KubernetesClient k8s, final String namespace) {
        return kubernetesFriend.fetch(
                k8s, "/api/v1/namespaces/" + namespace + "/pods?" +
                        "limit=1000&" +
                        "fieldSelector=status.phase%3DRunning",
                "Invalid pods response: ", Pods.class);
    }

    private CompletionStage<TopPods> findTopPerNamespace(final KubernetesClient k8s, final String namespace) {
        return kubernetesFriend.fetch(
                k8s, "/" + configuration.metricsApi() + "/namespaces/" + namespace + "/pods",
                "Invalid top response: ", TopPods.class);
    }

    @RootConfiguration("-")
    public record Configuration(
            @Property(documentation = "Memory unit to use when rendering (respecting format).", defaultValue = "io.yupiik.kubernetes.klim.service.UnitFormatter.Unit.Mi") UnitFormatter.Unit memoryUnit,
            @Property(documentation = "Should comments be shown.", defaultValue = "true") boolean showComments,
            @Property(documentation = "Output format.", defaultValue = "io.yupiik.kubernetes.klim.service.UnitFormatter.Format.TABLE") UnitFormatter.Format format,
            @Property(value = "limit-bound", documentation = "Warning limit. If a resource is over this (ratio between 0 and 1) value compared to its limit a warning will be emitted. Negative values disable this check.", defaultValue = "-1.") double limitBound,
            @Property(value = "request-bound", documentation = "Warning limit. If a resource is over this (ratio between 0 and 1) value compared to its request a warning will be emitted. Negative values disable this check.", defaultValue = ".8") double requestBound,
            @Property(documentation = "Metrics API base endpoint (in case it becomes stable to avoid to have to update immediately the binary).", defaultValue = "\"apis/metrics.k8s.io/v1beta1\"") String metricsApi,
            @Property(documentation = "Namespace to query (if none all namespaces will be queried).") String namespace,
            @Property(documentation = "How to connect to Kubernetes cluster.") CliKubernetesConfiguration k8s) {
    }

    private record PodData(TopPod top, Pod pod) {
    }
}
