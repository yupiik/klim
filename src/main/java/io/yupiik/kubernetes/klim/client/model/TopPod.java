package io.yupiik.kubernetes.klim.client.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.time.OffsetDateTime;
import java.util.List;

@JsonModel
public record TopPod(Metadata metadata, List<Container> containers, OffsetDateTime timestamp, String window) {
    @JsonModel
    public record Container(String name, Usage usage) {
    }

    @JsonModel
    public record Usage(String cpu, String memory) { // todo: disk
        public static final Usage EMPTY = new Usage("", "");
    }
}
