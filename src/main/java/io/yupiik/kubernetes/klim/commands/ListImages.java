package io.yupiik.kubernetes.klim.commands;

import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.kubernetes.klim.client.model.k8s.Cronjob;
import io.yupiik.kubernetes.klim.client.model.k8s.Cronjobs;
import io.yupiik.kubernetes.klim.client.model.k8s.Pod;
import io.yupiik.kubernetes.klim.client.model.k8s.Pods;
import io.yupiik.kubernetes.klim.client.model.k8s.Template;
import io.yupiik.kubernetes.klim.client.model.k8s.Templates;
import io.yupiik.kubernetes.klim.client.model.registry.Repositories;
import io.yupiik.kubernetes.klim.client.model.registry.Tags;
import io.yupiik.kubernetes.klim.configuration.CliKubernetesConfiguration;
import io.yupiik.kubernetes.klim.service.KubernetesFriend;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.completedStage;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;

@Command(name = "list-images", description = "Lists images and libraries found in running pods.")
public class ListImages implements Runnable {
    private final Configuration configuration;
    private final KubernetesFriend kubernetesFriend;
    private final JsonMapper jsonMapper;

    public ListImages(final Configuration configuration,
                      final KubernetesFriend kubernetesFriend,
                      final JsonMapper jsonMapper) {
        this.configuration = configuration;
        this.kubernetesFriend = kubernetesFriend;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void run() {
        render(switch (configuration.source()) {
            case CLUSTER -> collectFromCluster();
            case REGISTRY -> collectFromRegistry();
        });
    }

    private Collected collectFromRegistry() {
        final var executor = Executors.newCachedThreadPool();
        final var http = newClient(executor);
        final var base = URI.create(configuration.registry().url());
        final var auth = configuration.registry().username() == null ?
                null :
                ("Basic " + Base64.getEncoder().encodeToString(
                        (configuration.registry().username() + ':' + configuration.registry().password())
                                .getBytes(StandardCharsets.UTF_8)));
        final Supplier<HttpRequest.Builder> req = auth == null ?
                HttpRequest::newBuilder :
                () -> HttpRequest.newBuilder().header("authorization", auth);
        try {
            final var tags = listRepositories(executor, http, base, req)
                    .thenComposeAsync(images -> listTags(http, executor, base, req, images), executor)
                    .toCompletableFuture()
                    .get();
            return new Collected(tags.stream()
                    .map(it -> base.getAuthority() + '/' + it)
                    .collect(toSet()));
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            stopClient(executor, http);
        }
    }

    private CompletionStage<List<Repositories>> listRepositories(final ExecutorService executor, final HttpClient http, final URI base, final Supplier<HttpRequest.Builder> req) {
        return fetchAndFollowNextLink(http, executor, base.resolve("_catalog"), req, Repositories.class);
    }

    private CompletionStage<List<String>> listTags(final HttpClient client, final ExecutorService executor,
                                                   final URI base, final Supplier<HttpRequest.Builder> req,
                                                   final List<Repositories> images) {
        final var fetches = images.stream()
                .map(Repositories::repositories)
                .flatMap(Collection::stream)
                .map(image -> fetchAndFollowNextLink(client, executor, base.resolve(image + "/tags/list"), req, Tags.class)
                        .thenApplyAsync(all -> all.stream()
                                .flatMap(it -> it.tags().stream().map(tag -> it.name() + ':' + tag))
                                .toList(), executor))
                .map(CompletionStage::toCompletableFuture)
                .toList();
        return allOf(fetches.toArray(new CompletableFuture<?>[0]))
                .thenApplyAsync(ready -> fetches.stream()
                        .map(it -> it.getNow(List.of()))
                        .flatMap(Collection::stream)
                        .toList(), executor);
    }

    private <T> CompletionStage<List<T>> fetchAndFollowNextLink(final HttpClient client, final Executor executor,
                                                                final URI from, final Supplier<HttpRequest.Builder> req,
                                                                final Class<T> type) {
        // scope it to this method to avoid to recompile on numerous pages
        // but ok to do on a few ones
        final var splitter = Pattern.compile(",");
        return client.sendAsync(
                        req.get()
                                .GET()
                                .uri(from)
                                .header("accept", "application/json")
                                .build(),
                        ofString())
                .thenComposeAsync(res -> {
                    if (res.statusCode() != 200) {
                        throw new IllegalStateException("HTTP error: " + res + "\n" + res.body());
                    }

                    final var current = jsonMapper.fromString(type, res.body());
                    return res.headers()
                            .firstValue("Link")
                            .flatMap(link -> Stream.of(splitter.split(link))
                                    .filter(it -> {
                                        final int end = it.indexOf(";");
                                        final var urlStart = it.indexOf('<');
                                        return end > 0 &&
                                                it.substring(end).contains("rel=\"next\"") &&
                                                // simple sanity check
                                                urlStart >= 0 && urlStart < end;
                                    })
                                    .findFirst())
                            .map(it -> it.substring(it.indexOf('<') + 1, it.indexOf('>')))
                            .map(URI::create)
                            .map(it -> it.getScheme() == null ? from.resolve(it) : it)
                            .map(next -> fetchAndFollowNextLink(client, executor, next, req, type)
                                    .thenApply(nextList -> Stream.concat(Stream.of(current), nextList.stream()).toList()))
                            .orElseGet(() -> completedStage(List.of(current)));
                }, executor);
    }

    private Collected collectFromCluster() {
        try (final var k8s = configuration.k8s().client()) {
            return (configuration.namespace() != null ?
                    completedFuture(List.of(configuration.namespace())) :
                    kubernetesFriend.findNamespaces(k8s))
                    .thenCompose(namespaces -> collect(k8s, namespaces))
                    .toCompletableFuture()
                    .get();

        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private void render(final Collected data) {
        final var output = switch (configuration.format()) {
            case HUMAN_LIST -> "Collected images:" + data.images().stream()
                    .sorted()
                    .map(it -> "* " + it)
                    .collect(joining("\n"));
            case JSON -> jsonMapper.toString(data);
        };
        System.out.println(output);
    }

    private CompletionStage<Collected> collect(final KubernetesClient k8s, final String namespace) {
        // what ran
        final var pods = kubernetesFriend.fetch(
                        k8s, "/api/v1/namespaces/" + namespace + "/pods?limit=1000",
                        "Invalid deployments response: ", Pods.class)
                .toCompletableFuture();
        // what can run - indeed there are overlap but enables to get more coverage
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
                pods, deployments, statefulsets, daemonsets, cronjobs)
                .thenApply(ready -> new Collected(
                        Stream.concat(
                                        pods.getNow(null).items().stream().map(Pod::spec).flatMap(this::findImages),
                                        Stream.of(deployments, statefulsets, daemonsets, cronjobs)
                                                .map(it -> it.getNow(null))
                                                .flatMap(this::findImages))
                                .collect(toSet())));
    }

    private Stream<String> findImages(final Templates templates) {
        return templates.items() == null ? Stream.empty() : templates.items().stream()
                .map(Template::spec)
                .map(Template.Spec::template)
                .map(Pod::spec)
                .flatMap(it -> findImages(it));
    }

    private Stream<String> findImages(final Pod.Spec it) {
        return Stream.of(it.initContainers(), it.containers())
                .filter(Objects::nonNull)
                .flatMap(c -> c.stream().map(Pod.Container::image));
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

    private void stopClient(final ExecutorService executor, final HttpClient http) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.MINUTES);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (http instanceof AutoCloseable a) { // will be in java 21 (https://bugs.openjdk.org/browse/JDK-8304165)
            try {
                a.close();
            } catch (final Exception e) {
                // no-op
            }
        }
    }

    private HttpClient newClient(final ExecutorService executor) {
        final var clientBuilder = HttpClient.newBuilder()
                .followRedirects(ALWAYS)
                .connectTimeout(Duration.ofMinutes(5))
                .executor(executor);
        if (configuration.registry().certificates() != null && !configuration.registry().certificates().isBlank()) {
            try {
                final var ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);

                final var certificateFactory = CertificateFactory.getInstance("X.509");
                try (final var caInput = new ByteArrayInputStream(configuration.registry().certificates().getBytes(StandardCharsets.UTF_8))) {
                    final var counter = new AtomicInteger();
                    final var certs = certificateFactory.generateCertificates(caInput);
                    certs.forEach(c -> {
                        try {
                            ks.setCertificateEntry("ca-" + counter.incrementAndGet(), c);
                        } catch (final KeyStoreException e) {
                            throw new IllegalArgumentException(e);
                        }
                    });
                } catch (final CertificateException | IOException e) {
                    throw new IllegalArgumentException(e);
                }

                final var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);
                final var trustManagers = tmf.getTrustManagers();

                final var context = SSLContext.getInstance("TLSv1.2");
                context.init(null, trustManagers, null);

                clientBuilder.sslContext(context);
            } catch (final Exception e) {
                throw new IllegalArgumentException(e);
            }
        }
        return clientBuilder.build();
    }

    @RootConfiguration("-")
    public record Configuration(
            @Property(documentation = "Output format.", defaultValue = "ListImages.Format.HUMAN_LIST") Format format,
            @Property(documentation = "Image listing source.", defaultValue = "ListImages.Source.CLUSTER") Source source,
            @Property(documentation = "Registry configuration when source=REGISTRY.", defaultValue = "new Registry()") RegistryConfiguration registry,
            @Property(documentation = "Namespace to query (if none all namespaces will be queried) - for source=CLUSTER case.") String namespace,
            @Property(documentation = "How to connect to Kubernetes cluster - for source=CLUSTER case.", defaultValue = "new CliKubernetesConfiguration()") CliKubernetesConfiguration k8s) {
    }

    public enum Source {
        CLUSTER, REGISTRY
    }

    public enum Format {
        HUMAN_LIST, JSON
    }

    public record RegistryConfiguration(
            @Property(documentation = "Registry base url (docker registry v2 API).") String url,
            @Property(documentation = "SSLContext certificates if needed - else JVM ones are used.") String certificates,
            @Property(documentation = "Username for basic authentication.") String username,
            @Property(documentation = "Password for basic authentication.") String password) {
    }

    @JsonModel
    public record Collected(Set<String> images) {
    }
}
