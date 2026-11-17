package com.LegacyhealthInsurance.insurance.drive;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to Google Drive with a service account (read-only).
 * Share your Drive folder with the service account's email address.
 */
@Component
public class GoogleDriveClient {

    public static final String GOOGLE_DOC = "application/vnd.google-apps.document";

    private final Path credentialsPath;
    private final String folderId;
    private Drive drive;   // created on first use

    public GoogleDriveClient(@Value("${insurance.drive.credentials-path}") String credentialsPath,
                             @Value("${insurance.drive.folder-id}") String folderId) {
        this.credentialsPath = Path.of(credentialsPath);
        this.folderId = folderId;
    }

    /** Explains what's missing, or returns null when Drive is ready to use. */
    public String configurationProblem() {
        if (folderId == null || folderId.isBlank() || folderId.startsWith("PASTE_")) {
            return "Google Drive folder ID is not set (insurance.drive.folder-id in application.properties)";
        }
        if (!Files.exists(credentialsPath)) {
            return "Service-account key not found at " + credentialsPath.toAbsolutePath();
        }
        return null;
    }

    /** All files directly inside the configured folder (not trashed). */
    public List<File> listFiles() throws IOException {
        List<File> result = new ArrayList<>();
        String pageToken = null;
        do {
            FileList page = drive().files().list()
                    .setQ("'" + folderId + "' in parents and trashed = false")
                    .setFields("nextPageToken, files(id, name, mimeType, modifiedTime, size)")
                    .setPageSize(100)
                    .setPageToken(pageToken)
                    .execute();
            result.addAll(page.getFiles());
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
        return result;
    }

    /** Downloads a normal file (PDF, TXT...). */
    public byte[] download(String fileId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        drive().files().get(fileId).executeMediaAndDownloadTo(out);
        return out.toByteArray();
    }

    /** Google Docs have no file to download; export them as plain text instead. */
    public byte[] exportGoogleDocAsText(String fileId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        drive().files().export(fileId, "text/plain").executeMediaAndDownloadTo(out);
        return out.toByteArray();
    }

    private synchronized Drive drive() throws IOException {
        if (drive == null) {
            try (InputStream in = Files.newInputStream(credentialsPath)) {
                GoogleCredentials credentials = ServiceAccountCredentials.fromStream(in)
                        .createScoped(List.of(DriveScopes.DRIVE_READONLY));
                drive = new Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(),
                        GsonFactory.getDefaultInstance(),
                        new HttpCredentialsAdapter(credentials))
                        .setApplicationName("LegacyHealthInsurance")
                        .build();
            } catch (GeneralSecurityException e) {
                throw new IOException("Could not create Google HTTP transport", e);
            }
        }
        return drive;
    }
}
