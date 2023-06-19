package io.yupiik.kubernetes.klim.client.model.k8s;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.util.List;

@JsonModel
public record Pods(List<Pod> items) {
}
