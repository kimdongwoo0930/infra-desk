package com.infradesk.ui.containers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainersPanelTest {

    @Test
    void compactsPortMappings() {
        assertEquals("80→80, 443→443", ContainersPanel.compactPorts("0.0.0.0:80->80/tcp, :::80->80/tcp, 0.0.0.0:443->443/tcp, :::443->443/tcp"));
        assertEquals("6379", ContainersPanel.compactPorts("6379/tcp"));
    }
}
