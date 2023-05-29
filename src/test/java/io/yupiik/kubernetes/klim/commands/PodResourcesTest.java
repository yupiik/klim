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
