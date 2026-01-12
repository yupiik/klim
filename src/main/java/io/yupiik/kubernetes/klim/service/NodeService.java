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
import io.yupiik.kubernetes.klim.client.model.k8s.Node;

@ApplicationScoped
public class NodeService {
    public String metadata(final Node node) {
        final var labels = node.metadata().labels();
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
}
