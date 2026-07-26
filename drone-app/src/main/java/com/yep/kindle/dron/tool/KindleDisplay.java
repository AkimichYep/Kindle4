package com.yep.kindle.dron.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import com.yep.kindle.dron.util.KindleUtils;

public class KindleDisplay {
    private static final int PORT = 5555;

    public static void main(String[] args) {
        System.out.println("=== Kindle E-Ink Wi-Fi HUD Active ===");
        try {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress("0.0.0.0", PORT));

            while (true) {
                try (Socket clientSocket = serverSocket.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {

                    String line;
                    StringBuilder payload = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        payload.append(line).append("\n");
                    }

                    if (payload.length() > 0) {
                        System.out.println("[E-INK UPDATE]:\n" + payload.toString());
                        KindleUtils.renderToEInk(payload.toString());
                    }
                } catch (Exception e) {
                    System.err.println("Display read error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Server exception: " + e.getMessage());
        }
    }

}