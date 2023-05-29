/*
 * Copyright (c) 2023 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.kubernetes.klim.table;

import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.joining;

public class TableFormatter {
    private final List<List<String>> rows;

    public TableFormatter(final List<List<String>> rows) {
        this.rows = rows;
    }

    @Override
    public String toString() {
        // standard tables (but poorly readable)
        final var maxWidthPerColumn = maxWidths(rows);
        final var lineSeparator = "-".repeat(
                (int) maxWidthPerColumn.stream().mapToLong(i -> i).sum() +
                        /*now add separators*/ 4 + (maxWidthPerColumn.size() - 1) * 3);
        return Stream.concat(Stream.concat(
                                Stream.of(lineSeparator, formatLine(rows.get(0), maxWidthPerColumn), lineSeparator),
                                rows.stream().skip(1).map(row -> formatLine(row, maxWidthPerColumn))),
                        Stream.of(lineSeparator + '\n'))
                .collect(joining("\n"));
    }

    private List<Integer> maxWidths(final List<List<String>> rows) {
        return rows.stream()
                .map(it -> it.stream().map(String::length).toList())
                .reduce(null, (a, b) -> {
                    if (a == null) {
                        return b;
                    }
                    final var it1 = a.iterator();
                    final var it2 = b.iterator();
                    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(new Iterator<Integer>() {
                        @Override
                        public boolean hasNext() {
                            return it1.hasNext();
                        }

                        @Override
                        public Integer next() {
                            return Math.max(it1.next(), it2.next());
                        }
                    }, Spliterator.IMMUTABLE), false).toList();
                });
    }

    private String formatLine(final List<String> data, final List<Integer> maxWidthPerColumn) {
        final var widthIt = maxWidthPerColumn.iterator();
        return data.stream()
                .map(it -> {
                    final int spaces = widthIt.next() - it.length();
                    return it + (spaces > 0 ? " ".repeat(spaces) : "");
                })
                .collect(joining(" | ", "| ", " |"));
    }
}
