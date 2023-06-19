package io.yupiik.kubernetes.klim.client.model.k8s;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.time.OffsetDateTime;
import java.util.Map;

@JsonModel
public record Metadata(String name, String namespace, Map<String, String> labels, Map<String, String> annotations,
                       OffsetDateTime creationTimestamp, String resourceVersion, String uid) {
    public static final Metadata EMTPY = new Metadata("", "", Map.of(), Map.of(), null, null, null);
}
