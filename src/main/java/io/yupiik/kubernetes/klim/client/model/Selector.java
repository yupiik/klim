package io.yupiik.kubernetes.klim.client.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.util.Map;

@JsonModel
public record Selector(Map<String, String> matchLabels) {
}