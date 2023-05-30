package io.yupiik.kubernetes.klim.commands;

import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.kubernetes.klim.client.model.Cronjob;
import io.yupiik.kubernetes.klim.client.model.Cronjobs;
import io.yupiik.kubernetes.klim.client.model.Pod;
import io.yupiik.kubernetes.klim.client.model.Templates;
import io.yupiik.kubernetes.klim.configuration.CliKubernetesConfiguration;
import io.yupiik.kubernetes.klim.service.KubernetesFriend;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toSet;

@Command(name = "list-images", description = "Lists images and libraries found in running pods.")
public class ListImages implements Runnable {
    private final Configuration configuration;
    private final KubernetesFriend kubernetesFriend;

    public ListImages(final Configuration configuration, final KubernetesFriend kubernetesFriend) {
        this.configuration = configuration;
        this.kubernetesFriend = kubernetesFriend;
    }

    @Override
    public void run() {
        try (final var k8s = configuration.k8s().client()) {
            final var data = (configuration.namespace() != null ?
                    completedFuture(List.of(configuration.namespace())) :
                    kubernetesFriend.findNamespaces(k8s))
                    .thenCompose(namespaces -> collect(k8s, namespaces))
                    .toCompletableFuture()
                    .get();
            System.out.println("Collected images:");
            data.images().stream()
                    .sorted()
                    .map(it -> "* " + it)
                    .forEach(System.out::println);
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private CompletionStage<Collected> collect(final KubernetesClient k8s, final String namespace) {
        final var deployments = kubernetesFriend.fetch(
                        k8s, "/apis/apps/v1/namespaces/" + namespace + "/deployments?limit=1000",
                        "Invalid deployments response: ", Templates.class)
                .toCompletableFuture();
        final var statefulsets = kubernetesFriend.fetch(
                        k8s, "/apis/apps/v1/namespaces/" + namespace + "/statefulsets?limit=1000",
                        "Invalid statefulsets response: ", Templates.class)
                .toCompletableFuture();
        final var daemonsets = kubernetesFriend.fetch(
                        k8s, "/apis/apps/v1/namespaces/" + namespace + "/daemonsets?limit=1000",
                        "Invalid daemonsets response: ", Templates.class)
                .toCompletableFuture();
        final var cronjobs = kubernetesFriend.fetch(
                        k8s, "/apis/batch/v1/namespaces/" + namespace + "/cronjobs?limit=1000",
                        "Invalid cronjobs response: ", Cronjobs.class)
                .thenApply(jobs -> jobs.items() == null ?
                        new Templates(List.of()) :
                        new Templates(jobs.items().stream()
                                .map(Cronjob::spec)
                                .map(Cronjob.Spec::jobTemplate)
                                .toList()))
                .toCompletableFuture();
        return allOf( // find all runtime by type - for now we ignore pod assuming there is no "pod without owner", can be added later on
                deployments, statefulsets, daemonsets, cronjobs)
                .thenApply(ready -> new Collected(
                        Stream.of(deployments, statefulsets, daemonsets, cronjobs)
                                .map(it -> it.getNow(null))
                                .flatMap(this::findImages)
                                .collect(toSet())));
    }

    private Stream<String> findImages(final Templates templates) {
        return templates.items() == null ? Stream.empty() : templates.items().stream()
                .flatMap(it -> Stream.of(
                                it.spec().template().spec().initContainers(),
                                it.spec().template().spec().containers())
                        .filter(Objects::nonNull)
                        .flatMap(c -> c.stream().map(Pod.Container::image)));
    }

    private CompletableFuture<Collected> collect(final KubernetesClient k8s, final List<String> namespaces) {
        final var collected = new ArrayList<Collected>();
        return allOf(namespaces.stream()
                .map(namespace -> collect(k8s, namespace)
                        .thenApply(res -> {
                            synchronized (collected) {
                                collected.add(res);
                            }
                            return res;
                        }))
                .toArray(CompletableFuture<?>[]::new))
                .thenApply(ignored -> new Collected(collected.stream().flatMap(it -> it.images().stream()).collect(toSet())));
    }

    @RootConfiguration("-")
    public record Configuration(
            @Property(documentation = "Namespace to query (if none all namespaces will be queried).") String namespace,
            @Property(documentation = "How to connect to Kubernetes cluster.", defaultValue = "new CliKubernetesConfiguration()") CliKubernetesConfiguration k8s) {
    }

    private record Collected(
            Set<String> images) {
    }
}
