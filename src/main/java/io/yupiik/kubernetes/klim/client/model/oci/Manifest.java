package io.yupiik.kubernetes.klim.client.model.oci;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.util.List;

@JsonModel
public record Manifest(
        int schemaVersion, // 2
        String mediaType, // application/vnd.docker.distribution.manifest.v2+json
        Layer config,
        List<Layer> layers) {
    @JsonModel
    public record Layer(String mediaType, long size, String digest) {
    }
}
