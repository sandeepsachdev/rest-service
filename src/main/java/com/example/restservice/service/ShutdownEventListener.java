package com.example.restservice.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;


@Component
public class ShutdownEventListener implements ApplicationListener<ContextClosedEvent> {

    @Autowired
    EmailSender emailSender;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        System.out.println("ContextClosedEvent received. Performing additional shutdown tasks...");
        // Additional shutdown tasks

        emailSender.sendEmail(getHostName() + " App was shutdown ",
                getDateTime(true),
                "sandeepsachdev17@gmail.com");

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
