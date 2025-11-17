package com.example.restservice.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
public class StartupEventListener implements  ApplicationListener<ContextRefreshedEvent> {

    @Autowired
    EmailSender emailSender;

    @Value("${account.key}")
    private String accountKey;


    @Override public void onApplicationEvent(ContextRefreshedEvent event) {
        String subject = getHostName() + " App was started ";
        String dateTime = getDateTime(true);
        emailSender.sendEmail(subject,
                dateTime,
                "sandeepsachdev17@gmail.com");

        String accountName = "sandeepstorage2";

        /*
         * Use your Storage account's name and key to create a credential object; this is used to access your account.
         */
        StorageSharedKeyCredential credential = new StorageSharedKeyCredential(accountName, accountKey);

        /*
         * From the Azure portal, get your Storage account blob service URL endpoint.
         * The URL typically looks like this:
         */
        String endpoint = String.format(Locale.ROOT, "https://%s.blob.core.windows.net", accountName);

        /*
         * Create a BlobServiceClient object that wraps the service endpoint, credential and a request pipeline.
         */
        BlobServiceClient storageClient = new BlobServiceClientBuilder().endpoint(endpoint).credential(credential).buildClient();

        BlobContainerClient blobContainerClient = storageClient.getBlobContainerClient("container1");

        BlobClient blobClient = blobContainerClient.getBlobClient("startuplog.txt");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        blobClient.download(outputStream);
        String blobContent = outputStream.toString(StandardCharsets.UTF_8); // Assuming text content
        String modifiedContent = blobContent + "\n" + subject + getDateTime(false);

        InputStream inputStream = new ByteArrayInputStream(modifiedContent.getBytes(StandardCharsets.UTF_8));
        blobClient.upload(inputStream, modifiedContent.length(), true);

    }

    public String getDateTime(boolean withHost ) {
        String hostName = "";

        if (withHost) {
            try {
                // Get the InetAddress object representing the local host
                InetAddress localHost = InetAddress.getLocalHost();
                // Get the host name from the InetAddress object
                hostName = localHost.getHostName();
            } catch (UnknownHostException e) {
                System.err.println("Could not determine localhost name: " + e.getMessage());
            }
        }

        Instant nowUtc = Instant.now();
        ZoneId myZone = ZoneId.of("Australia/Sydney");
        ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(nowUtc, myZone);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("hh:mm a E dd/MM/yyyy");

        return formatter.format(zonedDateTime) + " " + hostName;
    }

    private String getHostName() {
        try {
            // Get the InetAddress object representing the local host
            InetAddress localHost = InetAddress.getLocalHost();
            // Get the host name from the InetAddress object
            return localHost.getHostName();
        } catch (UnknownHostException e) {
            System.err.println("Could not determine localhost name: " + e.getMessage());
        }
        return "Unknown";
    }
}

