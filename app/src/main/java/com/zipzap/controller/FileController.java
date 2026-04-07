package com.zipzap.controller;

import com.zipzap.service.FileSharer;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController // Marks this class as a REST controller
@RequestMapping("/api") // Base path for all endpoints in this controller
public class FileController {

    private final FileSharer fileSharer;
    private final Path uploadDir; // Using Path for better file system handling

    public FileController(FileSharer fileSharer) throws IOException { // Spring injects FileSharer
        this.fileSharer = fileSharer;
        this.uploadDir = Paths.get(System.getProperty("java.io.tmpdir"), "zipzap-uploads");
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir); // Use Files.createDirectories for safety
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", -1, "message", "No file selected"));
        }
        try {
            // Generate a unique filename and save the file
            String originalFilename = file.getOriginalFilename();
            String uniqueFilename = UUID.randomUUID() + "_"
                    + (originalFilename != null ? originalFilename : "untitled");
            Path targetPath = uploadDir.resolve(uniqueFilename);
            file.transferTo(targetPath.toFile()); // Spring's easy way to save multipart files

            // Offer the file for sharing and get the invite code (port)
            int inviteCode = fileSharer.offerFile(targetPath.toString());

            // Start the file server for the uploaded file asynchronously
            fileSharer.startFileServer(inviteCode);

            Map<String, Object> response = new HashMap<>();
            response.put("inviteCode", inviteCode);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            System.err.println("Error uploading file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", -1, "message", "Failed to upload file: " + e.getMessage()));
        }
    }

    @GetMapping("/download/{inviteCode}")
    public ResponseEntity<Resource> downloadFile(@PathVariable("inviteCode") int inviteCode) {
        if (inviteCode < 0 || inviteCode > 65535) { // Basic port validation
            return ResponseEntity.badRequest().body(null); // Or return a specific error response
        }

        File tempDownloadedFile = null;
        try {
            // Connect to the peer's file server using the invite code (port)
            // This assumes the peer is running on localhost, or reachable via public IP
            // In a real P2P setup, you'd need the peer's actual IP address here too.
            // For this demo, we assume the backend also acts as the "downloader" of its own
            // shared files.
            // Or, more realistically, the client (frontend) would directly connect to the
            // peer's IP:Port.
            // If the client downloads *through* this backend, then this logic is fine.
            try (Socket socket = new Socket("localhost", inviteCode);
                    InputStream is = socket.getInputStream()) {

                // Read the custom filename header first
                StringBuilder filenameHeader = new StringBuilder();
                int c;
                while ((c = is.read()) != -1 && c != '\n') { // Read until newline
                    filenameHeader.append((char) c);
                }
                String headerLine = filenameHeader.toString();
                String downloadedFilename = "downloaded_file"; // Default if header not found
                if (headerLine.startsWith("Filename: ")) {
                    downloadedFilename = headerLine.substring("Filename: ".length()).trim();
                } else {
                    System.err.println("Warning: Filename header not found or malformed. Using default name.");
                }

                // Create a temporary file to save the downloaded content
                tempDownloadedFile = Files.createTempFile("peerlink-download-", null).toFile();
                try (FileOutputStream fos = new FileOutputStream(tempDownloadedFile)) {
                    IOUtils.copy(is, fos); // Use commons-io to copy streams
                }

                Resource resource = new FileSystemResource(tempDownloadedFile);

                HttpHeaders headers = new HttpHeaders();
                headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadedFilename + "\"");
                headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE);
                // Spring will automatically set Content-Length from the Resource

                // Clean up the temporary file after it's served
                // Note: Spring handles stream closing. tempDownloadedFile.delete() will only
                // work after response is sent.
                // A better approach for production might be a scheduled cleanup task or using
                // StreamingResponseBody.
                tempDownloadedFile.deleteOnExit(); // Schedule for deletion when JVM exits

                return ResponseEntity.ok()
                        .headers(headers)
                        .contentLength(tempDownloadedFile.length())
                        .body(resource);
            }
        } catch (IOException e) {
            System.err.println("Error in download endpoint for inviteCode " + inviteCode + ": " + e.getMessage());
            // Clean up temp file if download failed mid-process
            if (tempDownloadedFile != null && tempDownloadedFile.exists()) {
                tempDownloadedFile.delete();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null); // Or return a specific error response
        }
    }
}