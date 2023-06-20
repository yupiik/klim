package io.yupiik.kubernetes.klim.client.model.grype;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

import java.util.List;

@JsonModel
public record GrypeCves(
        List<Match> matches,
        Source source,
        Distro distro) {
    @JsonModel
    public record Source(
            String type,
            Target target) {
    }

    @JsonModel
    public record Target(
            String userInput,
            String imageID,
            String manifestDigest,
            String mediaType,
            List<String> tags,
            long imageSize,
            String manifest,
            String config,
            List<String> repoDigests,
            String architecture,
            String os) {
    }

    @JsonModel
    public record Match(
            Vulnerability vulnerability,
            List<Vulnerability> relatedVulnerabilities,
            List<CVSS> cvss,
            List<MatchDetail> matchDetails,
            Artifact artifact) {
    }

    @JsonModel
    public record Vulnerability(
            String id,
            String dataSource,
            String description,
            String namespace,
            String severity,
            List<String> urls,
            Fix fix
    ) {
    }

    @JsonModel
    public record Artifact(
            String id,
            String name,
            String version,
            String type,
            String language,
            List<Location> locations,
            List<String> license,
            List<String> cpes,
            List<String> purl,
            List<Name> upstream) {
    }

    @JsonModel
    public record Location(
            String path,
            String layerID) {
    }

    @JsonModel
    public record Name(String name) {
    }

    @JsonModel
    public record MatchDetail(
            String type,
            String matcher,
            SearchedBy searchedBy,
            String vector,
            Metrics metrics,
            Found found) {
    }

    @JsonModel
    public record Found(
            String versionConstraint,
            String vulnerabilityID) {
    }

    @JsonModel
    public record SearchedBy(
            Distro distro,
            String namespace,
            @JsonProperty("package") Distro packageName) {
    }

    @JsonModel
    public record Distro(
            String name,
            String type,
            String version) {
    }

    @JsonModel
    public record CVSS(
            String source,
            String type,
            String version,
            String vector,
            Metrics metrics) {
    }

    @JsonModel
    public record Metrics(
            Double baseScore,
            Double exploitabilityScore,
            Double impactScore) {
    }

    @JsonModel
    public record Fix(
            List<String> versions,
            String state) {
    }
}
