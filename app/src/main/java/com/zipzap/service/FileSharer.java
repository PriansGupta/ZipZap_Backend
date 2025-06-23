package com.zipzap.service;

import com.zipzap.utils.UploadUtils; // Changed import

import org.springframework.scheduling.annotation.Async; // For asynchronous method
import org.springframework.stereotype.Service; // New annotation

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap; // Use concurrent map for thread safety

@Service // Mark as a Spring service
public class FileSharer {

    private final ConcurrentHashMap<Integer, String> availableFiles;
    private final UploadUtils uploadUtils; // Injected dependency

    public FileSharer(UploadUtils uploadUtils) { // Constructor for dependency injection
        this.availableFiles = new ConcurrentHashMap<>();
        this.uploadUtils = uploadUtils;
    }

    public int offerFile(String filePath) {
        int port;
        // In a real-world scenario, you might want to retry a few times or manage
        // a pool of available ports to prevent infinite loops if many ports are in use.
        // For simplicity, we assume generateCode will eventually find one.
        while (true) {
            port = uploadUtils.generateCode();
            if (!availableFiles.containsKey(port)) {
                availableFiles.put(port, filePath);
                return port;
            }
        }
    }

    @Async // Run this method in a separate thread managed by Spring's TaskExecutor
    public void startFileServer(int port) {
        String filePath = availableFiles.get(port);
        if (filePath == null) {
            System.err.println("No file associated with port: " + port);
            return;
        }

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Serving file '" + new File(filePath).getName() + "' on port " + port);
            // This serverSocket.accept() is blocking. For a production app,
            // you might want to manage a thread pool for these as well,
            // or use non-blocking I/O (NIO) if many concurrent P2P shares are expected.
            Socket clientSocket = serverSocket.accept();
            System.out.println("Client connected: " + clientSocket.getInetAddress());

            // Handle the file sending in a separate, dedicated thread.
            // Spring's @Async manages the thread for startFileServer, but the actual
            // file sending can still be blocking for that specific client.
            new Thread(new FileSenderHandler(clientSocket, filePath)).start();

        } catch (IOException e) {
            System.err.println("Error starting file server on port " + port + ": " + e.getMessage());
            // It's good to remove the port if the server failed to start on it
            availableFiles.remove(port);
        }
    }

    // FileSenderHandler remains largely the same
    private static class FileSenderHandler implements Runnable {
        private final Socket clientSocket;
        private final String filePath;

        public FileSenderHandler(Socket clientSocket, String filePath) {
            this.clientSocket = clientSocket;
            this.filePath = filePath;
        }

        @Override
        public void run() {
            try (FileInputStream fis = new FileInputStream(filePath);
                    OutputStream oss = clientSocket.getOutputStream()) {

                // Send the filename as a header
                String filename = new File(filePath).getName();
                String header = "Filename: " + filename + "\n"; // Newline is important!
                oss.write(header.getBytes());

                // Send the file content
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    oss.write(buffer, 0, bytesRead);
                }
                System.out.println("File '" + filename + "' sent to " + clientSocket.getInetAddress());
            } catch (IOException e) {
                System.err.println("Error sending file to client: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing client socket: " + e.getMessage());
                }
            }
        }
    }
}