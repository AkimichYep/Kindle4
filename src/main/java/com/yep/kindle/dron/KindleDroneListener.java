package com.yep.kindle.dron;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.time.LocalDateTime;

/**
 * Lightweight UDP Remote ID / Drone Packet Listener for Kindle 4 (Java 8)
 */
public class KindleDroneListener {

    private static final int LISTEN_PORT = 5555; // Port where tracking data is sent
    private static final int BUFFER_SIZE = 1024;

    public static void main(String[] args) {
        System.out.println("=== Kindle 4 Lightweight Drone Listener Started ===");
        System.out.println("Listening for UDP data packets on port " + LISTEN_PORT + "...");

        try (DatagramSocket socket = new DatagramSocket(LISTEN_PORT)) {
            byte[] buffer = new byte[BUFFER_SIZE];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet); // Blocks until a packet arrives

                String payload = new String(packet.getData(), 0, packet.getLength());
                processDroneData(packet.getAddress().getHostAddress(), payload);
            }

        } catch (IOException e) {
            System.err.println("Network error on Kindle listener: " + e.getMessage());
        }
    }

    private static void processDroneData(String sourceIp, String rawData) {
        // Simple string parsing logic to look for drone identifiers or Remote ID parameters
        String timestamp = LocalDateTime.now().toString();

        System.out.println("[" + timestamp + "] Packet from " + sourceIp + ": " + rawData);

        if (rawData.contains("RemoteID") || rawData.contains("Drone-Serial")) {
            System.out.println(">>> ALERT! Potential Drone signature detected: " + rawData);
            // On Kindle, you could write this data directly to a local text log file
            // or trigger an update to frame buffer if you want visual output on the E-Ink screen.
        }
    }
}