package io.yupiik.kubernetes.klim.client.model.k8s;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

@JsonModel
public record Template(Metadata metadata, Spec spec) {
    @JsonModel
    public record Spec(Selector selector, Pod template) {
    }
}
