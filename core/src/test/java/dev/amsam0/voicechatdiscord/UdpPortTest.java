package dev.amsam0.voicechatdiscord;

import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class UdpPortTest {
    @Test
    void aPortSomethingElseHoldsIsReported() throws Exception {
        // like the server's query listener on the server port
        try (DatagramSocket query = new DatagramSocket(new InetSocketAddress(0))) {
            String problem = DiscordBot.udpPortProblem(query.getLocalPort());
            assertNotNull(problem);
            assertTrue(problem.contains("udp_port"), "tells the admin what to change");
        }
    }

    @Test
    void aFreePortIsFine() throws Exception {
        int port;
        try (DatagramSocket probe = new DatagramSocket(new InetSocketAddress(0))) {
            port = probe.getLocalPort();
        }
        assertNull(DiscordBot.udpPortProblem(port));
    }

    @Test
    void randomPortIsNeverAProblem() {
        assertNull(DiscordBot.udpPortProblem(0));
    }
}
