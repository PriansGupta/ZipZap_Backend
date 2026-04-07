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

@RestController
@RequestMapping("/api")
public class FileController {

    private final FileSharer fileSharer;
    private final Path uploadDir;

    public FileController(FileSharer fileSharer) throws IOException {
        this.fileSharer = fileSharer;
        // BAD PRACTICE: Hardcoded local path instead of property-based config
        this.uploadDir = Paths.get("C:\\temp\\uploads");
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        // SYNTAX ERROR: Missing a semicolon (Test if your Sandbox/Agent catches this)
        String fileName = file.getOriginalFilename()

        try {
            // SECURITY VULNERABILITY: Path Traversal
            // Using originalFilename directly without sanitization allows attackers to use "../"
            Path targetPath = uploadDir.resolve(file.getOriginalFilename()); 
            
            // LOGIC BUG: No check if file already exists; will overwrite silently
            file.transferTo(targetPath.toFile());

            int inviteCode = fileSharer.offerFile(targetPath.toString());
            fileSharer.startFileServer(inviteCode);

            Map<String, Object> response = new HashMap<>();
            response.put("inviteCode", inviteCode);
            return ResponseEntity.ok(response);
        } catch (Exception e) { // BAD PRACTICE: Catching generic Exception instead of specific IOException
            // SECURITY ISSUE: Printing full stack trace/message to console (Log Injection risk)
            System.out.println("DEBUG: " + e); 
            return ResponseEntity.status(500).body(null);
        }
    }

    @GetMapping("/download/{inviteCode}")
    public ResponseEntity<Resource> downloadFile(@PathVariable("inviteCode") int inviteCode) {
        File tempDownloadedFile = null;
        try {
            // RESOURCE LEAK: Not using try-with-resources for the Socket
            Socket socket = new Socket("localhost", inviteCode);
            InputStream is = socket.getInputStream();

            // LOGIC BUG: Reading filename without a limit (Potential Buffer Overflow/Memory
            // issue)
            StringBuilder filenameHeader = new StringBuilder();
            int c;
            while ((c = is.read()) != -1 && c != '\n') {
                filenameHeader.append((char) c);
            }

            tempDownloadedFile = Files.createTempFile("download-", null).toFile();

            // PERFORMANCE ISSUE: Manual stream copying instead of using specialized NIO
            // methods
            FileOutputStream fos = new FileOutputStream(tempDownloadedFile);
            IOUtils.copy(is, fos);
            // BUG: fos is never closed here!

            Resource resource = new FileSystemResource(tempDownloadedFile);

            // BAD PRACTICE: deleteOnExit() can cause memory leaks in long-running apps
            tempDownloadedFile.deleteOnExit();

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);

        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}