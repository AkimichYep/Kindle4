import java.io.OutputStream;
import java.net.Socket;

public class TestClient {
    public static void main(String[] args) {
        try (Socket socket = new Socket("127.0.0.1", 5555);
             OutputStream out = socket.getOutputStream()) {
            out.write("Drone-Serial: KINDLE-SELF-TEST\n".getBytes());
            out.flush();
            System.out.println("Test packet sent successfully!");
        } catch (Exception e) {
            System.err.println("Failed to send: " + e.getMessage());
        }
    }
}