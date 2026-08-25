package com.yep.kindle.dron.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import com.yep.kindle.dron.util.AppLog;
import com.yep.kindle.dron.util.KindleUtils;

public class KindleDisplay {
    private static final int PORT = 5555;
    private static volatile boolean running = true;

    public static void main(String[] args) {
        AppLog.init(System.getProperty("kindle.log.file", "kindle-display.log"), 256 * 1024L);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            AppLog.info("Shutdown requested for KindleDisplay");
        }, "shutdown-kindle-display"));
        AppLog.info("=== Kindle E-Ink Wi-Fi HUD Active ===");
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress("0.0.0.0", PORT));
            serverSocket.setSoTimeout(1_000);

            while (running) {
                try (Socket clientSocket = serverSocket.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {

                    String line;
                    StringBuilder payload = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        payload.append(line).append("\n");
                    }

                    if (payload.length() > 0) {
                        AppLog.info("[E-INK UPDATE]:\n" + payload.toString());
                        KindleUtils.renderToEInk(payload.toString());
                    }
                } catch (Exception e) {
                    if (e instanceof java.net.SocketTimeoutException) continue;
                    AppLog.exception("Display read error", e);
                }
            }
        } catch (Exception e) {
            AppLog.exception("Display server exception", e);
        }
    }

}