package io.yupiik.kubernetes.klim.commands;

import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.kubernetes.klim.client.model.Metadata;
import io.yupiik.kubernetes.klim.client.model.Pod;
import io.yupiik.kubernetes.klim.client.model.Pods;
import io.yupiik.kubernetes.klim.client.model.TopPod;
import io.yupiik.kubernetes.klim.client.model.TopPods;
import io.yupiik.kubernetes.klim.configuration.CliKubernetesConfiguration;
import io.yupiik.kubernetes.klim.service.KubernetesFriend;
import io.yupiik.kubernetes.klim.service.resource.ContainerResources;
import io.yupiik.kubernetes.klim.table.TableFormatter;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
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

    public PodResources(final Configuration configuration, final KubernetesFriend kubernetesFriend) {
        this.configuration = configuration;
        this.kubernetesFriend = kubernetesFriend;
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

            final var includeUnits = configuration.format() != Format.CSV;
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
                                                            formatCpu(it.cpuRequest(), cpuFormat),
                                                            formatCpu(it.cpuLimit(), cpuFormat),
                                                            formatCpu(it.cpuUsage(), cpuFormat),
                                                            formatMemory(it.memoryRequest(), includeUnits),
                                                            formatMemory(it.memoryLimit(), includeUnits),
                                                            formatMemory(it.memoryUsage(), includeUnits)),
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

    private String formatCpu(final double value, final NumberFormat format) {
        if (value < 0) {
            return "";
        }
        return format.format(value);
    }

    private String formatMemory(final long value, final boolean keepUnit) {
        if (value < 0) {
            return "";
        }
        return (long) (value * 1. / configuration.memoryUnit().factor) + (keepUnit ? configuration.memoryUnit().name() : "");
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

                    final var cpuRequest = pod.requests() == null ? -1 : normalizeCpuInCore(pod.requests().cpu());
                    final var cpuLimit = pod.limits() == null ? -1 : normalizeCpuInCore(pod.limits().cpu());
                    final var cpu = normalizeCpuInCore(top.cpu());

                    final var memoryRequest = pod.requests() == null ? -1 : normalizeMemoryInBytes(pod.requests().memory());
                    final var memoryLimit = pod.limits() == null ? -1 : normalizeMemoryInBytes(pod.limits().memory());
                    final var memory = normalizeMemoryInBytes(top.memory());

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

    private long normalizeMemoryInBytes(final String memory) {
        if (memory == null || memory.isBlank()) {
            return -1; // means not set
        }
        final var builder = readAlphaSuffix(memory);
        if (builder.isEmpty()) {
            return (long) Double.parseDouble(memory); // support 129e6 case for ex
        }
        return (long) (Double.parseDouble(memory.substring(0, memory.length() - builder.length())) *
                Unit.valueOf(builder.reverse().toString()).factor);
    }

    private double normalizeCpuInCore(final String cpu) {
        if (cpu == null || cpu.isBlank()) {
            return -1; // means not set
        }

        final var builder = readAlphaSuffix(cpu);
        if (builder.isEmpty() || builder.length() > 1 /* only DecimalSI case, not BinarySI one */) {
            return (long) Double.parseDouble(cpu);
        }

        final var unit = Unit.valueOf(builder.reverse().toString());
        final var value = Double.parseDouble(cpu.substring(0, cpu.length() - builder.length()));
        return value * unit.factor;
    }

    private StringBuilder readAlphaSuffix(final String memory) {
        final var builder = new StringBuilder();
        int idx = memory.length() - 1;
        while (Character.isAlphabetic(memory.charAt(idx))) {
            builder.append(memory.charAt(idx));
            idx--;
        }
        return builder;
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
            @Property(documentation = "Memory unit to use when rendering (respecting format).", defaultValue = "PodResources.Unit.Mi") Unit memoryUnit,
            @Property(documentation = "Should comments be shown.", defaultValue = "true") boolean showComments,
            @Property(documentation = "Output format.", defaultValue = "PodResources.Format.TABLE") Format format,
            @Property(value = "limit-bound", documentation = "Warning limit. If a resource is over this (ratio between 0 and 1) value compared to its limit a warning will be emitted. Negative values disable this check.", defaultValue = "-1.") double limitBound,
            @Property(value = "request-bound", documentation = "Warning limit. If a resource is over this (ratio between 0 and 1) value compared to its request a warning will be emitted. Negative values disable this check.", defaultValue = ".8") double requestBound,
            @Property(documentation = "Metrics API base endpoint (in case it becomes stable to avoid to have to update immediately the binary).", defaultValue = "\"apis/metrics.k8s.io/v1beta1\"") String metricsApi,
            @Property(documentation = "Namespace to query (if none all namespaces will be queried).") String namespace,
            @Property(documentation = "How to connect to Kubernetes cluster.", defaultValue = "new CliKubernetesConfiguration()") CliKubernetesConfiguration k8s) {
    }

    public enum Format {
        CSV, TABLE
    }

    public enum Unit { // https://pkg.go.dev/k8s.io/apimachinery/pkg/api/resource
        Ki(1024), Mi(1_048_576), Gi(1_073_741_824), Ti(1_099_511_627_776L), Pi(1_125_899_906_842_624L), Ei(1_152_921_504_606_846_976L),
        n(.000_000_001), m(.001), k(1_000), M(1_000_000), G(1_000_000_000), T(1_000_000_000_000L), P(1_000_000_000_000_000L), E(1_000_000_000_000_000_000L);

        private final double factor;

        Unit(double factor) {
            this.factor = factor;
        }
    }

    private record PodData(TopPod top, Pod pod) {
    }
}
