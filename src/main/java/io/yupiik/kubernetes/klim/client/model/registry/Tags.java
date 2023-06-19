package io.yupiik.kubernetes.klim.client.model.registry;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.util.List;

@JsonModel
public record Tags(String name, List<String> tags) {
}
