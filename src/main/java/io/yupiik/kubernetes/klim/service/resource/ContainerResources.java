package io.yupiik.kubernetes.klim.service.resource;

import java.util.List;

public record ContainerResources(
        String namespace, String pod, String container,
        double cpuRequest, long memoryRequest,
        double cpuLimit, long memoryLimit,
        double cpuUsage, long memoryUsage,
        List<Note> notes) {
    public enum NoteLevel {
        INFO, WARNING, ERROR
    }

    public enum ResourceType {
        CPU, MEMORY
    }

    public record Note(NoteLevel level, ResourceType resource, String message) {
    }
}