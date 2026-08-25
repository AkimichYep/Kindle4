package com.yep.kindle.dron.tool;

import java.io.OutputStream;
import java.net.Socket;

import com.yep.kindle.dron.util.AppLog;

public class TestClient {
    public static void main(String[] args) {
        AppLog.init(System.getProperty("kindle.log.file", "test-client.log"), 128 * 1024L);
        try (Socket socket = new Socket("127.0.0.1", 5555);
             OutputStream out = socket.getOutputStream()) {
            out.write("Drone-Serial: KINDLE-SELF-TEST\n".getBytes());
            out.flush();
            AppLog.info("Test packet sent successfully");
        } catch (Exception e) {
            AppLog.exception("Failed to send test packet", e);
        }
    }
}