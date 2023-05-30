package io.yupiik.kubernetes.klim.client.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.util.List;

@JsonModel
public record Pod(Metadata metadata, Spec spec) {
    @JsonModel
    public record Spec(List<Container> initContainers, List<Container> containers) {
    }

    @JsonModel
    public record Container(String name, String image, Resources resources) {
    }

    @JsonModel
    public record Resources(Bounds limits, Bounds requests) {
        public static final Resources EMPTY = new Resources(Bounds.EMPTY, Bounds.EMPTY);
    }

    @JsonModel
    public record Bounds(String cpu, String memory) {
        public static final Bounds EMPTY = new Bounds("", "");
    }
}
