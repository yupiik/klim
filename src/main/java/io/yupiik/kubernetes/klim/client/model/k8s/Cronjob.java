package io.yupiik.kubernetes.klim.client.model.k8s;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

@JsonModel
public record Cronjob(Spec spec) {
    @JsonModel
    public record Spec(Template jobTemplate) {
    }
}
