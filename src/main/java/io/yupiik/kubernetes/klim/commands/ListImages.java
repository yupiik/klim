package io.yupiik.kubernetes.klim.commands;

import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.kubernetes.klim.client.model.grype.GrypeCves;
import io.yupiik.kubernetes.klim.client.model.k8s.Cronjob;
import io.yupiik.kubernetes.klim.client.model.k8s.Cronjobs;
import io.yupiik.kubernetes.klim.client.model.k8s.Pod;
import io.yupiik.kubernetes.klim.client.model.k8s.Pods;
import io.yupiik.kubernetes.klim.client.model.k8s.Template;
import io.yupiik.kubernetes.klim.client.model.k8s.Templates;
import io.yupiik.kubernetes.klim.client.model.oci.Manifest;
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
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.net.http.HttpResponse.BodyHandlers.ofByteArrayConsumer;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.completedStage;
import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;

@Command(name = "list-images", description = "Lists images and libraries found in running pods.")
public class ListImages implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());

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
        var data = switch (configuration.source()) {
            case CLUSTER -> collectFromCluster();
            case REGISTRY -> collectFromRegistry();
        };
        render(data);
    }

    private Collection<Image> runGrype(final HttpClient http, final ExecutorService executor, final URI base, final Supplier<HttpRequest.Builder> req, final Set<Image> images) {
        try {
            // first ensure database is up to date
            final var dbUpdateProcess = new ProcessBuilder(configuration.grype().binary(), "db", "update").start();
            final var dbUpdate = dbUpdateProcess.waitFor();
            if (dbUpdate != 0) {
                throw new IllegalStateException("Invalid exit status for grype db update command: " + dbUpdate);
            }

            final var workPath = Path.of(configuration.grype().workdir());
            final boolean workExists = Files.exists(workPath);
            final var work = Files.createDirectories(workPath);
            final var cache = Files.createDirectories(work.resolve("_cache"));

            final var throttler = new Semaphore(Math.min(configuration.grype().maxProcesses(), 2 * Runtime.getRuntime().availableProcessors()));
            final var downloadThrottler = new Semaphore(throttler.availablePermits() * 2 /*allow to have some advance but not too much to not fill the disk*/);
            final var progress = new Progress(images.size(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder());
            progress.log(logger);
            try {
                final var tasks = images.stream()
                        .map(image -> {
                            // todo: check if we can download all layers in the same dir and just switch the manifest?
                            //  throttler.count dirs to be // maybe?

                            final var tmp = work.resolve(image.name()
                                    .replace(':', '_')
                                    .replace('/', '_')
                                    .replace('@', '_'));
                            try {
                                downloadThrottler.acquire();
                            } catch (final InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                            final CompletionStage<?> download;
                            try {
                                download = downloadImage(http, executor, base, req, tmp, image.name(), cache, progress)
                                        .whenComplete((ok, ko) -> downloadThrottler.release());
                            } catch (final RuntimeException re) {
                                downloadThrottler.release();
                                throw re;
                            }
                            return download
                                    .thenApplyAsync(ok -> {
                                        try {
                                            return runGrype(throttler, image, tmp);
                                        } finally {
                                            progress.scanned().increment();
                                            progress.log(logger);
                                        }
                                    }, executor)
                                    .toCompletableFuture();
                        })
                        .toList();
                allOf(tasks.toArray(new CompletableFuture<?>[0]))
                        .toCompletableFuture()
                        .get();

                final var indexed = tasks.stream()
                        .map(it -> it.getNow(null))
                        .collect(groupingBy(it -> it.source().target().userInput()));
                return images.stream()
                        .map(it -> new Image(it.name(), indexed.getOrDefault(it.name(), List.of()).stream()
                                .map(GrypeCves::matches)
                                .filter(Objects::nonNull)
                                .flatMap(Collection::stream)
                                .map(cve -> new CVE(cve.vulnerability().id(), cve.vulnerability().description(), cve.vulnerability().urls()))
                                .toList()))
                        .toList();
            } finally {
                deleteDir(cache);
                if (!workExists) {
                    deleteDir(work);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private GrypeCves runGrype(final Semaphore throttler, final Image image, final Path tmp) {
        try {
            throttler.acquire();

            final var process = new ProcessBuilder(
                    configuration.grype().binary(), "dir:" + tmp, "-o=json").start();
            logger.finest(() -> "Running grype on '" + tmp + "'");
            final var status = process.waitFor();
            logger.finest(() -> "Ran grype on '" + tmp + "': " + status);

            final var errorStream = process.getErrorStream();
            final String error;
            try (final var out = errorStream) {
                error = new String(out.readAllBytes(), UTF_8);
            }

            logger.finest(() -> "Ran grype on '" + tmp + "': " + status + "\n" + error);
            if (status != 0) {
                throw new IllegalStateException("Invalid exit status for image='" + image.name() + "': " + status + "\n" + error);
            }
            return jsonMapper.fromString(GrypeCves.class, error.isBlank() ? "{}" : "");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (final IOException | RuntimeException | Error e) {
            throw new IllegalStateException(e);
        } finally {
            throttler.release();
            deleteDir(tmp);
        }
    }

    private CompletionStage<?> downloadImage(final HttpClient http, final ExecutorService executor,
                                             final URI base, final Supplier<HttpRequest.Builder> req,
                                             final Path target, final String name, final Path cacheBase,
                                             final Progress progress) {
        final var tagIdx = name.lastIndexOf(':');
        final var imageName = name.substring(name.indexOf('/') + 1, tagIdx);
        final var tag = name.substring(tagIdx + 1);

        logger.finest(() -> "Downloading manifest for '" + name + "'");
        progress.pendingDownloads().increment();
        progress.logMaybe(logger);
        return http.sendAsync(
                        req.get()
                                .GET()
                                .uri(base.resolve(imageName + "/manifests/" + tag))
                                .build(),
                        ofString())
                .whenComplete((ok, ko) -> {
                    progress.pendingDownloads().decrement();
                    progress.logMaybe(logger);
                })
                .thenComposeAsync(res -> {
                    if (res.statusCode() != 200) {
                        throw new IllegalStateException("Invalid manifest download: " + res + "\n" + res.body());
                    }
                    logger.finest(() -> "Downloaded manifest for '" + name + "'");
                    try {
                        Files.createDirectories(target);
                        Files.writeString(target.resolve("version"), "Directory Transport Version: 1.1");
                        Files.writeString(target.resolve("manifest.json"), res.body());
                        final var manifest = jsonMapper.fromString(Manifest.class, res.body());
                        final var downloads = Stream.concat(
                                        Stream.ofNullable(manifest.config()),
                                        Stream.ofNullable(manifest.layers())
                                                .flatMap(Collection::stream))
                                .distinct()
                                .map(l -> {
                                    final var digest = l.digest();
                                    logger.finest(() -> "Downloading digest (for '" + name + "'): '" + digest + "' (" + l.size() + ")");
                                    final var filename = digest.substring(digest.indexOf(':') + 1/*drop "sha256:" prefix*/);
                                    final var cachedFile = cacheBase.resolve(filename);
                                    try {
                                        if (Files.exists(cachedFile)) {
                                            long lastSize = Files.size(cachedFile);
                                            int max = 500;
                                            while (max-- > 0 && lastSize != Files.size(cachedFile)) { // give the copy some time
                                                try {
                                                    Thread.sleep(500);
                                                } catch (final InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(e);
                                                }
                                                lastSize = Files.size(cachedFile);
                                            }
                                            Files.copy(cachedFile, target.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                                            logger.finest(() -> "Copied digest (for '" + name + "'): '" + digest + "'");
                                            return completedFuture(true);
                                        }
                                    } catch (final IOException e) {
                                        throw new IllegalStateException(e);
                                    }

                                    final var size = l.size();
                                    if (size > 0) {
                                        progress.pendingDownloadSize().add(size);
                                    }
                                    progress.pendingDownloads().increment();
                                    progress.logMaybe(logger);
                                    return http.sendAsync(
                                                    req.get()
                                                            .GET()
                                                            .uri(base.resolve(imageName + "/blobs/" + digest))
                                                            .build(),
                                                    responseInfo -> { // handle progress
                                                        final var list = new ArrayList<byte[]>();
                                                        final var res1 = ofByteArrayConsumer(bytes -> bytes.ifPresent(data -> {
                                                            if (size > 0) {
                                                                progress.currentDownloadSize().add(data.length);
                                                                progress.logMaybe(logger);
                                                            }
                                                            synchronized (list) {
                                                                list.add(data);
                                                            }
                                                        })).apply(responseInfo);
                                                        return HttpResponse.BodySubscribers.mapping(res1, ignored -> {
                                                            final int outSize = list.stream().mapToInt(i -> i.length).sum();
                                                            final var out = new byte[outSize];
                                                            int start = 0;
                                                            for (final var b : list) {
                                                                System.arraycopy(b, 0, out, start, b.length);
                                                                start += b.length;
                                                            }
                                                            return out;
                                                        });
                                                    })
                                            .whenComplete((ok, ko) -> {
                                                progress.pendingDownloads().decrement();
                                                if (size > 0) {
                                                    progress.pendingDownloadSize().add(-size);
                                                }
                                                progress.logMaybe(logger);
                                            })
                                            .thenAcceptAsync(blobRes -> {
                                                if (blobRes.statusCode() != 200) {
                                                    throw new IllegalStateException("Invalid blob '" + digest + "' download: " + blobRes + "\n" + new String(blobRes.body()));
                                                }

                                                logger.finest(() -> "Downloaded digest (for '" + name + "'): '" + digest + "'");
                                                try {
                                                    final var tmpCache = cacheBase.resolve(filename + ".tmp");
                                                    if (Files.exists(tmpCache)) { // skip, already being cached
                                                        Files.write(cachedFile, blobRes.body());
                                                        return;
                                                    }

                                                    if (Files.exists(cachedFile)) { // was cached in between, skip, we have the data to write anyway
                                                        Files.write(cachedFile, blobRes.body());
                                                        return;
                                                    }

                                                    Files.write(tmpCache, blobRes.body());
                                                    Files.move(tmpCache, cachedFile);
                                                    Files.copy(cachedFile, target.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                                                } catch (final IOException e) {
                                                    throw new IllegalStateException(e);
                                                }
                                            }, executor);
                                })
                                .toArray(CompletableFuture<?>[]::new);
                        if (downloads.length == 0) {
                            return completedFuture(null);
                        }
                        return allOf(downloads);
                    } catch (final IOException ioe) {
                        throw new IllegalStateException(ioe);
                    }
                }, executor);
    }

    private Collected collectFromRegistry() {
        final var executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>()) {
            @Override
            public void execute(final Runnable command) {
                try {
                    super.execute(command);
                } catch (final RuntimeException re) {
                    logger.log(SEVERE, re, re::getMessage);
                    throw re;
                }
            }
        };
        executor.setRejectedExecutionHandler((r, e) -> {
            logger.warning(() -> "Rejected task: " + r);
            throw new RejectedExecutionException("Task was rejected: " + r);
        });
        final var http = newClient(executor, configuration.registry().timeout());
        final var base = URI.create(configuration.registry().url());
        final var auth = configuration.registry().username() == null ?
                null :
                ("Basic " + Base64.getEncoder().encodeToString(
                        (configuration.registry().username() + ':' + configuration.registry().password())
                                .getBytes(StandardCharsets.UTF_8)));
        final var timeout = Duration.ofMillis(configuration.registry().timeout());
        final Supplier<HttpRequest.Builder> req = auth == null ?
                () -> HttpRequest.newBuilder().timeout(timeout) :
                () -> HttpRequest.newBuilder().timeout(timeout).header("authorization", auth);
        try {
            final var tags = listRepositories(executor, http, base, req)
                    .thenComposeAsync(images -> listTags(http, executor, base, req, images), executor)
                    .toCompletableFuture()
                    .get();
            final var images = tags.stream()
                    .map(it -> base.getAuthority() + '/' + it)
                    .map(it -> new Image(it, List.of()))
                    .collect(toSet());


            if (configuration.grype().enable()) {
                return new Collected(runGrype(http, executor, base, req, images));
            }
            return new Collected(images);
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
            final var collected = (configuration.namespace() != null ?
                    completedFuture(List.of(configuration.namespace())) :
                    kubernetesFriend.findNamespaces(k8s))
                    .thenCompose(namespaces -> collect(k8s, namespaces))
                    .toCompletableFuture()
                    .get();
            if (configuration.grype().enable()) {
                throw new IllegalArgumentException("You can't scan with grype a running cluster as of today");
            }
            return collected;
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
                    .sorted(comparing(Image::name))
                    .map(it -> "* " + (configuration.grype().enable() ? it.toString() : it.name()))
                    .collect(joining("\n", "\n", ""));
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
                                .map(it -> new Image(it, List.of()))
                                .collect(toSet())));
    }

    private Stream<String> findImages(final Templates templates) {
        return templates.items() == null ? Stream.empty() : templates.items().stream()
                .map(Template::spec)
                .map(Template.Spec::template)
                .map(Pod::spec)
                .flatMap(this::findImages);
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
                        })
                        .toCompletableFuture())
                .toArray(CompletableFuture<?>[]::new))
                .thenApply(ignored -> new Collected(collected.stream().flatMap(it -> it.images().stream()).collect(toSet())));
    }

    private void stopClient(final ExecutorService executor, final HttpClient http) {
        stopExecutor(executor);
        if (http instanceof AutoCloseable a) { // will be in java 21 (https://bugs.openjdk.org/browse/JDK-8304165)
            try {
                a.close();
            } catch (final Exception e) {
                // no-op
            }
        }
    }

    private void stopExecutor(final ExecutorService executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.MINUTES);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpClient newClient(final ExecutorService executor, final long timeout) {
        final var clientBuilder = HttpClient.newBuilder()
                .followRedirects(ALWAYS)
                .connectTimeout(Duration.ofMillis(timeout))
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

    private void deleteDir(final Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return super.visitFile(file, attrs);
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                    Files.delete(dir);
                    return super.postVisitDirectory(dir, exc);
                }
            });
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @RootConfiguration("-")
    public record Configuration( // todo: add a state management
                                 @Property(documentation = "Output format.", defaultValue = "ListImages.Format.HUMAN_LIST") Format format,
                                 @Property(documentation = "Image listing source.", defaultValue = "ListImages.Source.CLUSTER") Source source,
                                 @Property(documentation = "Chain with grype scanning, only works for source=REGISTRY.") GrypeConfiguration grype,
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

    public record GrypeConfiguration(
            @Property(documentation = "If `true` and `grype` is available, it will report the known CVE for your images. Note that it will likely only work from a registry as of today due to credentials.", defaultValue = "false") boolean enable,
            @Property(value = "max-concurrency", documentation = "Max number of concurrent grype processes.", defaultValue = "16") int maxProcesses,
            @Property(documentation = "Max number of concurrent grype processes.", defaultValue = "\"grype\"") String binary,
            @Property(documentation = "Where to store exploded images for analyzis.", defaultValue = "\"/tmp/klim_grypes_work\"") String workdir) {
    }

    public record RegistryConfiguration(
            @Property(documentation = "Registry base url (docker registry v2 API).") String url,
            @Property(documentation = "Timeout for registry requests, note that grype usage will also rely on that to download layers.", defaultValue = "120_000L") long timeout,
            @Property(documentation = "SSLContext certificates if needed - else JVM ones are used.") String certificates,
            @Property(documentation = "Username for basic authentication.") String username,
            @Property(documentation = "Password for basic authentication.") String password) {
    }

    @JsonModel
    public record Collected(Collection<Image> images) {
    }

    @JsonModel
    public record CVE(String id, String description, List<String> urls) {
    }

    @JsonModel
    public record Image(String name, List<CVE> cves) {
    }

    private record Progress(int all, LongAdder scanned, LongAdder pendingDownloads, LongAdder pendingDownloadSize,
                            LongAdder currentDownloadSize) {
        private void logMaybe(final Logger logger) {
            if (!logger.isLoggable(FINEST)) {
                log(logger);
            }
        }

        private void log(final Logger logger) {
            final Supplier<String> message = () -> "" +
                    "Remaining images scanned: " + scanned().sum() + "/" + all + ", " +
                    "pending downloads: " + pendingDownloads().sum() + ", " +
                    "current download progress: " + String.format("%.2f%%", 100. * currentDownloadSize().sum() / pendingDownloadSize().sum());
            if (logger.isLoggable(FINEST)) {
                logger.info(message);
            } else { // refresh friendly
                System.out.print(message.get() + "\r");
            }
        }
    }
}
