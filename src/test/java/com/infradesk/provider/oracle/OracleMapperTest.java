package com.infradesk.provider.oracle;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.InstanceShapeConfig;
import com.oracle.bmc.model.BmcException;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleMapperTest {

    @Test
    void mapsLifecycleStates() {
        assertEquals(ServerStatus.RUNNING, OracleMapper.status(Instance.LifecycleState.Running));
        assertEquals(ServerStatus.STOPPED, OracleMapper.status(Instance.LifecycleState.Stopped));
        assertEquals(ServerStatus.STARTING, OracleMapper.status(Instance.LifecycleState.Starting));
        assertEquals(ServerStatus.STOPPING, OracleMapper.status(Instance.LifecycleState.Stopping));
        assertEquals(ServerStatus.RUNNING, OracleMapper.status(Instance.LifecycleState.CreatingImage));
        assertEquals(ServerStatus.UNKNOWN, OracleMapper.status(Instance.LifecycleState.UnknownEnumValue));
        assertEquals(ServerStatus.UNKNOWN, OracleMapper.status(null));
    }

    @Test
    void mapsInstance() {
        Instance i = Instance.builder()
                .id("ocid1.instance.oc1..fake")
                .displayName("bot")
                .lifecycleState(Instance.LifecycleState.Running)
                .region("ap-chuncheon-1")
                .shape("VM.Standard.A1.Flex")
                .shapeConfig(InstanceShapeConfig.builder().ocpus(4f).memoryInGBs(24f).build())
                .timeCreated(new Date(0))
                .build();
        Server s = OracleMapper.server(i, "acc", new OracleMapper.Ips("203.0.113.1", "10.0.0.2"));
        assertEquals("bot", s.name());
        assertEquals(4, s.cpuCount());
        assertEquals(24, s.memoryGb());
        assertEquals("203.0.113.1", s.publicIp());
        assertEquals(ServerStatus.RUNNING, s.status());
    }

    @Test
    void authErrorBecomesFriendlyMessage() {
        var e = new BmcException(401, "NotAuthenticated", "The required information to complete authentication was not provided", "req-1");
        assertTrue(OracleMapper.error("서버 목록 조회", e).getMessage().contains("인증에 실패"));
    }
}
