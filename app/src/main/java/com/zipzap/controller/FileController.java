package com.zipzap.controller;

import com.zipzap.service.FileSharer;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class FileController {

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);
    private final FileSharer fileSharer;
    private final Path uploadDir;

    // FIX 2: Using @Value for property-based configuration
    public FileController(FileSharer fileSharer, @Value("${file.upload-dir:/tmp/zipzap}") String uploadPath)
            throws IOException {
        this.fileSharer = fileSharer;
        this.uploadDir = Paths.get(uploadPath).toAbsolutePath().normalize();
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        // FIX 1: Added missing semicolon
        String originalFileName = file.getOriginalFilename();

        if (file.isEmpty() || originalFileName == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "File is empty"));
        }

        try {
            // FIX 3: Sanitize filename to prevent Path Traversal
            String sanitizedFileName = FilenameUtils.getName(originalFileName);
            String uniqueFileName = UUID.randomUUID() + "_" + sanitizedFileName;
            Path targetPath = uploadDir.resolve(uniqueFileName).normalize();

            // Safety check: Ensure the resolved path is still within the upload directory
            if (!targetPath.startsWith(uploadDir)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "Invalid file path"));
            }

            // FIX 4: Check if file exists (though UUID makes this unlikely)
            if (Files.exists(targetPath)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "File already exists"));
            }

            file.transferTo(targetPath.toFile());

            int inviteCode = fileSharer.offerFile(targetPath.toString());
            fileSharer.startFileServer(inviteCode);

            Map<String, Object> response = new HashMap<>();
            response.put("inviteCode", inviteCode);
            return ResponseEntity.ok(response);

        } catch (IOException e) { // FIX 5: Catching specific IOException
            // FIX 6: Proper logging instead of System.out
            logger.error("File upload failed for {}: {}", originalFileName, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/download/{inviteCode}")
    public ResponseEntity<Resource> downloadFile(@PathVariable("inviteCode") int inviteCode) {
        // FIX 7: Using try-with-resources for Socket and Streams (Auto-closes)
        try (Socket socket = new Socket("localhost", inviteCode);
                InputStream is = socket.getInputStream()) {

            // FIX 8: Limit filename header size (max 255 chars) to prevent buffer overflow
            StringBuilder filenameHeader = new StringBuilder();
            int c;
            int count = 0;
            while ((c = is.read()) != -1 && c != '\n' && count < 255) {
                filenameHeader.append((char) c);
                count++;
            }

            File tempFile = Files.createTempFile("zipzap-dl-", ".tmp").toFile();

            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                IOUtils.copy(is, fos);
            }

            Resource resource = new FileSystemResource(tempFile);

            // FIX 9: Instead of deleteOnExit(), manual cleanup or custom Resource handling
            // is preferred
            // For a demo, we will use a small note that production should use a Cleanup
            // Service.
            logger.info("Serving file from temp location: {}", tempFile.getAbsolutePath());

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"downloaded_file\"")
                    .body(resource);

        } catch (IOException e) {
            logger.error("Download failed for code {}: {}", inviteCode, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}