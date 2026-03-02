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
package io.yupiik.kubernetes.klim.service;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.text.NumberFormat;

@ApplicationScoped
public class UnitFormatter {
    public long normalizeMemoryInBytes(final String memory) {
        if (memory == null || memory.isBlank()) {
            return -1; // means not set
        }
        final var builder = readAlphaSuffix(memory);
        if (builder.isEmpty()) {
            return (long) Double.parseDouble(memory); // support 129e6 case for ex
        }
        return (long) (Double.parseDouble(memory.substring(0, memory.length() - builder.length())) *
                Unit.valueOf(builder.reverse().toString()).factor);
    }

    public double normalizeCpuInCore(final String cpu) {
        if (cpu == null || cpu.isBlank()) {
            return -1; // means not set
        }

        final var builder = readAlphaSuffix(cpu);
        if (builder.isEmpty() || builder.length() > 1 /* only DecimalSI case, not BinarySI one */) {
            return (long) Double.parseDouble(cpu);
        }

        final var unit = Unit.valueOf(builder.reverse().toString());
        final var value = Double.parseDouble(cpu.substring(0, cpu.length() - builder.length()));
        return value * unit.factor;
    }

    public String formatCpu(final double value, final NumberFormat format) {
        if (value < 0) {
            return "";
        }
        return format.format(value);
    }

    public String formatMemory(final long value, final boolean keepUnit, Unit unit) {
        if (value < 0) {
            return "";
        }
        return (long) (value * 1. / unit.factor) + (keepUnit ? unit.name() : "");
    }

    private StringBuilder readAlphaSuffix(final String memory) {
        final var builder = new StringBuilder();
        int idx = memory.length() - 1;
        while (Character.isAlphabetic(memory.charAt(idx))) {
            builder.append(memory.charAt(idx));
            idx--;
        }
        return builder;
    }

    public enum Format {
        CSV, TABLE
    }

    public enum Unit { // https://pkg.go.dev/k8s.io/apimachinery/pkg/api/resource
        Ki(1024), Mi(1_048_576), Gi(1_073_741_824), Ti(1_099_511_627_776L), Pi(1_125_899_906_842_624L), Ei(1_152_921_504_606_846_976L),
        n(.000_000_001), m(.001), k(1_000), M(1_000_000), G(1_000_000_000), T(1_000_000_000_000L), P(1_000_000_000_000_000L), E(1_000_000_000_000_000_000L);

        private final double factor;

        Unit(double factor) {
            this.factor = factor;
        }
    }
}
