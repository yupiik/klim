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
package io.yupiik.kubernetes.klim.commands;

import io.yupiik.fusion.testing.launcher.FusionCLITest;
import io.yupiik.fusion.testing.launcher.Stdout;
import io.yupiik.kubernetes.klim.test.K8sMock;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PodResourcesTest {
    @K8sMock
    @FusionCLITest(args = "pod-resources")
    void show(final Stdout stdout) {
        assertEquals("""
                --------------------------------------------------------------------------------------------------------------------------------------------------------------
                | namespace | pod  | container | cpu req. | cpu limit | cpu usage | mem. req. | mem limit | mem usage | comments                                             |
                --------------------------------------------------------------------------------------------------------------------------------------------------------------
                | test      | test | test      | 0.05     | 0.1       | 0.08      | 512Mi     | 1024Mi    | 854Mi     | warning: cpu over 80.0%. warning: memory over 80.0%. |
                --------------------------------------------------------------------------------------------------------------------------------------------------------------
                                
                """, stdout.content());
    }
}
