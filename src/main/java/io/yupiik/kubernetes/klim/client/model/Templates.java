package io.yupiik.kubernetes.klim.client.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.util.List;

@JsonModel
public record Templates(List<Template> items) {
}
