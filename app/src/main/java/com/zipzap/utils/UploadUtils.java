package com.zipzap.utils;

import org.springframework.stereotype.Component; // New annotation
import java.util.Random;
import java.net.ServerSocket; // To check if port is available

@Component // Mark as a Spring component for auto-detection
public class UploadUtils {

    // Dynamic/Private Port Range
    private static final int DYNAMIC_STARTING_PORT = 49152;
    private static final int DYNAMIC_ENDING_PORT = 65535;
    private static final Random random = new Random();

    public Integer generateCode() {
        while (true) {
            int port = random.nextInt(DYNAMIC_ENDING_PORT - DYNAMIC_STARTING_PORT + 1) + DYNAMIC_STARTING_PORT;
            if (isPortAvailable(port)) {
                return port;
            }
        }
    }

    // Helper to check if a port is actually available
    private boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true); // Allows the port to be reused quickly after closing
            return true;
        } catch (java.net.BindException e) {
            // Port is already in use
            return false;
        } catch (Exception e) {
            // Other IO error, perhaps permissions issue or network problem
            System.err.println("Error checking port " + port + ": " + e.getMessage());
            return false;
        }
    }
}