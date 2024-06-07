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
package io.yupiik.kubernetes.klim.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.fusion.kubernetes.client.KubernetesClientConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MINUTES;

public record  CliKubernetesConfiguration(
        @Property(documentation = "Kubernetes API base.") String api,
        @Property(documentation = "If authenticated by token and not using a `kubeconfig`, the token to use.") String token,
        @Property(documentation = "SSL certificates for communication (not authentication).") String certificates,
        @Property(documentation = "If authenticated by a X509 client certificate, the private key.") String privateKey,
        @Property(documentation = "If authenticated by a X509 client certificate, the certificate.") String privateKeyCertificate,
        @Property(documentation = "Should SSL error be ignored for communication.") boolean skipTls,
        @Property(documentation = "A `kubeconfig` path.") String kubeconfig) {
    public Path kubeconfigPath() {
        if ((token != null && !token.isBlank()) || (privateKey != null && !privateKey.isBlank())) {
            return null;
        }

        if (kubeconfig != null && !kubeconfig.isBlank()) {
            return Path.of(kubeconfig);
        }

        final var defaultValue = Path.of(System.getProperty("klim.home", System.getProperty("user.home", "."))).resolve(".kube/config");
        if (Files.exists(defaultValue)) {
            return defaultValue;
        }

        return null;
    }

    public KubernetesClient client() {
        final var executor = Executors.newCachedThreadPool();
        return new KubernetesClient(new KubernetesClientConfiguration()
                .setKubeconfig(kubeconfigPath())
                .setToken(ofNullable(token()).orElse("ignore_token_file_if_missing_" + Instant.now().toEpochMilli()))
                .setPrivateKey(privateKey())
                .setPrivateKeyCertificate(privateKeyCertificate())
                .setCertificates(certificates())
                .setMaster(api())) {
            @Override
            public void close() {
                try {
                    super.close();
                } finally {
                    executor.shutdownNow();
                    try { // not critical if it does not end there since it is a CLI but give it a chance
                        executor.awaitTermination(1, MINUTES);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
    }
}
