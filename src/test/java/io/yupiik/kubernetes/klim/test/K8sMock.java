package io.yupiik.kubernetes.klim.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.fail;

@Retention(RUNTIME)
@Target(METHOD)
@ExtendWith(K8sMock.Extension.class)
public @interface K8sMock {
    class Extension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
        private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(Extension.class);

        @Override
        public void beforeEach(final ExtensionContext context) {
            context.getStore(NAMESPACE).getOrComputeIfAbsent(Server.class, k -> {
                try {
                    final var server = HttpServer.create(new InetSocketAddress(0), 16);
                    server.createContext("/").setHandler(ex -> {
                        try (ex) {
                            if (!"GET".equals(ex.getRequestMethod())) {
                                ex.sendResponseHeaders(501, 0);
                                return;
                            }
                            switch (ex.getRequestURI().getPath()) {
                                case "/api/v1/namespaces" -> send200(ex, """
                                        {
                                          "items":[
                                            {"metadata":{"name":"test"}}
                                          ]
                                        }""");
                                case "/apis/metrics.k8s.io/v1beta1/namespaces/test/pods" -> send200(ex, """
                                        {
                                          "items":[
                                            {
                                              "metadata":{"namespace":"test","name":"test"},
                                              "containers":[
                                                {
                                                  "name": "test",
                                                  "usage":{
                                                    "cpu":"80m",
                                                    "memory": "854Mi"
                                                  }
                                                }
                                              ]
                                            }
                                          ]
                                        }""");
                                case "/api/v1/namespaces/test/pods" -> send200(ex, """
                                        {
                                          "items":[
                                            {
                                                "metadata":{"namespace":"test","name":"test"},
                                                "spec":{
                                                  "containers":[
                                                    {
                                                      "name": "test",
                                                      "resources":{
                                                        "limits":{
                                                           "cpu":"100m",
                                                           "memory": "1Gi"
                                                        },
                                                        "requests":{
                                                          "cpu":"50m",
                                                          "memory": "512Mi"
                                                        }
                                                      }
                                                    }
                                                  ]
                                                }
                                            }
                                          ]
                                        }""");
                                default -> ex.sendResponseHeaders(502, 0);
                            }
                        }
                    });
                    server.start();

                    final var kubeconfigBase = Files.createTempDirectory("kli");
                    Files.writeString(
                            Files.createDirectories(kubeconfigBase.resolve(".kube")).resolve("config"),
                            "apiVersion: v1\n" +
                                    "clusters:\n" +
                                    "- cluster:\n" +
                                    "    certificate-authority-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUNmakNDQWVlZ0F3SUJBZ0lCQURBTkJna3Foa2lHOXcwQkFRMEZBREJiTVFzd0NRWURWUVFHRXdKbWNqRU8KTUF3R0ExVUVDQXdGVkc5MWNuTXhEekFOQmdOVkJBb01CbGwxY0dscGF6RU5NQXNHQTFVRUF3d0VkR1Z6ZERFTwpNQXdHQTFVRUJ3d0ZWRzkxY25NeEREQUtCZ05WQkFzTUEwOVRVekFnRncweU16QTFNamt3T0RJd01EVmFHQTh6Ck1ESXlNRGt5T1RBNE1qQXdOVm93V3pFTE1Ba0dBMVVFQmhNQ1puSXhEakFNQmdOVkJBZ01CVlJ2ZFhKek1ROHcKRFFZRFZRUUtEQVpaZFhCcGFXc3hEVEFMQmdOVkJBTU1CSFJsYzNReERqQU1CZ05WQkFjTUJWUnZkWEp6TVF3dwpDZ1lEVlFRTERBTlBVMU13Z1o4d0RRWUpLb1pJaHZjTkFRRUJCUUFEZ1kwQU1JR0pBb0dCQUtLZXJibHFNY2lVCk45TFU5N2U4ZmZLK2RPUUpWSTNPcGdZSDd0VVpYdU9FVEcxVnROVDFlVDltbGQ1Q0FySGU2L0pyQmdnYlNOZ2YKbU10UEZzemxrVEZTRDFETmZkd1Zvc2luK05PTENVZ1NzWWFRNGhEcmZaQTZnR0FtdWRvVEZyYy9KcHFKVDlTOQpFRzM2UXlqMk9NUjA0UGtFMFMrV2ZucVB2Qi9hc2ZNM0FnTUJBQUdqVURCT01CMEdBMVVkRGdRV0JCUmNLZmthCkF2VHlnVS83ZHFUS3FwZ0hrSmFrSGpBZkJnTlZIU01FR0RBV2dCUmNLZmthQXZUeWdVLzdkcVRLcXBnSGtKYWsKSGpBTUJnTlZIUk1FQlRBREFRSC9NQTBHQ1NxR1NJYjNEUUVCRFFVQUE0R0JBSDg4bHhvVi9EeVAwKzhWVjF1OQpGMzd3bVM0dVN3c1d5ak1vNnZoSi9LWDdhZlVNNk12QzhQVVFuS1dJUWpKRFdtMXRSVDBPV2FJenMwUFQ2ZC8vCnJuTTJJNGovUXQwQld4dEd6OG5PMmtTYkxxMUwxSFN3c2p1ZVlGOEFvTDNpcTRSOXI2eTRlQXpPc3NKS0ZReTQKV2FWVEozQUFGTGRkUjZvR2tGdWVlVEovCi0tLS0tRU5EIENFUlRJRklDQVRFLS0tLS0=\n" +
                                    "    server: http://localhost:" + server.getAddress().getPort() + "\n" +
                                    "  name: test\n" +
                                    "contexts:\n" +
                                    "- context:\n" +
                                    "    cluster: test\n" +
                                    "    namespace: test\n" +
                                    "    user: user\n" +
                                    "  name: test\n" +
                                    "users:\n" +
                                    "- user:\n" +
                                    "    token: test\n" +
                                    "  name: test\n" +
                                    "current-context: \"test\"\n" +
                                    "kind: Config\n");
                    System.setProperty("klim.home", kubeconfigBase.toString());

                    return new Server(server, kubeconfigBase);
                } catch (final IOException ioe) {
                    return fail(ioe);
                }
            });
        }

        @Override
        public void afterEach(final ExtensionContext context) throws Exception {
            context.getStore(NAMESPACE).get(Server.class, Server.class).close();
        }

        @Override
        public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) throws ParameterResolutionException {
            return InetSocketAddress.class == parameterContext.getParameter().getType();
        }

        @Override
        public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) throws ParameterResolutionException {
            return extensionContext.getStore(NAMESPACE).get(Server.class, Server.class).server().getAddress();
        }

        private record Server(HttpServer server, Path kubeconfig) implements AutoCloseable {
            @Override
            public void close() throws Exception {
                System.clearProperty("klim.home");
                server.stop(0);
                Files.walkFileTree(kubeconfig, new SimpleFileVisitor<>() {
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
            }
        }

        private void send200(final HttpExchange ex, final String payload) throws IOException {
            final var bytes = payload.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
        }
    }
}
