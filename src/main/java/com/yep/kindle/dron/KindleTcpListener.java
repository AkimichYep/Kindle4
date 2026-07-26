package com.yep.kindle.dron;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;

public class KindleTcpListener {
    private static final int PORT = 5555;

    public static void main(String[] args) {
        System.out.println("=== Kindle TCP Drone Listener Active on port " + PORT + " ===");
        try {
            // Explicitly bind to 0.0.0.0 (all interfaces, including wlan0)
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress("0.0.0.0", PORT));

            while (true) {
                try (Socket clientSocket = serverSocket.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {

                    String line = reader.readLine();
                    if (line != null) {
                        String timestamp = LocalDateTime.now().toString();
                        System.out.println("[" + timestamp + "] RECEIVED: " + line);
                    }
                } catch (Exception e) {
                    System.err.println("Read error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Server exception: " + e.getMessage());
        }
    }
}