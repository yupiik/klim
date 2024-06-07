/*
 * Copyright (c) 2024 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.kubernetes.klim.client.model.k8s;

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
