import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

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
                        // Print to console log
                        System.out.println("[E-INK UPDATE]:\n" + payload.toString());

                        // Optional: Trigger Kindle e-ink screen refresh command if drawing via system tools
                        // e.g., using eips command line utility built into Kindle
                        updateKindleScreen(payload.toString());
                    }
                } catch (Exception e) {
                    System.err.println("Display read error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Server exception: " + e.getMessage());
        }
    }

    private static void updateKindleScreen(String text) {
        try {
            // Kindle native command line tool 'eips' writes text directly to e-ink screen coordinates
            Process process = Runtime.getRuntime().exec(new String[]{"eips", "-c"}); // clear screen
            process.waitFor();

            // Print lines via eips (Kindle built-in e-ink print utility)
            String[] lines = text.split("\n");
            int y = 0;
            for (String l : lines) {
                if (y < 20 && !l.isEmpty()) {
                    Runtime.getRuntime().exec(new String[]{"eips", "0", String.valueOf(y), l});
                    y++;
                }
            }
        } catch (Exception e) {
            System.err.println("E-Ink draw error: " + e.getMessage());
        }
    }
}