package com.infradesk.ui.containers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainerFormatTest {

    @Test
    void 멈춘_컨테이너의_상태를_한국어로_바꾼다() {
        assertEquals("멈춤 (0) · 3일 전", ContainerFormat.status("Exited (0) 3 days ago"));
        assertEquals("멈춤 (137) · 2시간 전", ContainerFormat.status("Exited (137) 2 hours ago"));
        assertEquals("멈춤 (1) · 1분 전", ContainerFormat.status("Exited (1) 1 minute ago"));
        assertEquals("멈춤 (0) · 20초 전", ContainerFormat.status("Exited (0) 20 seconds ago"));
        assertEquals("멈춤 (0) · 2주 전", ContainerFormat.status("Exited (0) 2 weeks ago"));
        assertEquals("멈춤 (0) · 3개월 전", ContainerFormat.status("Exited (0) 3 months ago"));
    }

    @Test
    void 약_또는_방금_같은_표현도_바꾼다() {
        assertEquals("멈춤 (0) · 약 1시간 전", ContainerFormat.status("Exited (0) About an hour ago"));
        assertEquals("멈춤 (0) · 약 1분 전", ContainerFormat.status("Exited (0) About a minute ago"));
        assertEquals("멈춤 (0) · 방금 전", ContainerFormat.status("Exited (0) Less than a second ago"));
    }

    @Test
    void 실행_중이거나_해석하지_못하는_문구는_그대로_둔다() {
        assertEquals("Up 14 days", ContainerFormat.status("Up 14 days"));
        assertEquals("Up 2 hours (healthy)", ContainerFormat.status("Up 2 hours (healthy)"));
        assertEquals("Created", ContainerFormat.status("Created"));
        // 시간 표현을 못 읽으면 틀리게 바꾸지 않고 영어 그대로 둔다
        assertEquals("Exited (0) a long while ago", ContainerFormat.status("Exited (0) a long while ago"));
    }

    @Test
    void 메모리는_쓰는_양만_숫자와_단위_사이를_띄워_보인다() {
        assertEquals("46 MiB", ContainerFormat.memory("46MiB / 23.4GiB"));
        assertEquals("1.2 GiB", ContainerFormat.memory("1.2GiB / 23.4GiB"));
        assertEquals("—", ContainerFormat.memory(""));
        assertEquals("—", ContainerFormat.memory(null));
    }

    @Test
    void 네트워크는_받은_것과_보낸_것을_단위를_띄워_보인다() {
        assertEquals("1.2 MB / 3.4 MB", ContainerFormat.network("1.2MB / 3.4MB"));
        assertEquals("220 kB / 180 kB", ContainerFormat.network("220kB / 180kB"));
        assertEquals("—", ContainerFormat.network(""));
    }

    @Test
    void CPU는_소수_한_자리_퍼센트이고_모르면_줄표다() {
        assertEquals("12.8%", ContainerFormat.cpu(12.8));
        assertEquals("0.0%", ContainerFormat.cpu(0));
        assertEquals("—", ContainerFormat.cpu(-1));
    }
}
