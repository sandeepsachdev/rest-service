package com.example.restservice.service;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.communication.email.models.EmailSendStatus;
import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.example.restservice.greeting.GreetingController.POLLER_WAIT_TIME;

@Component
public class EmailSender {

    @Value("${email.connection.string}")
    private String connectionString;


    public void sendEmail(String subject, String body) {

        sendEmail(subject, body, "testemailzxcv1234@gmail.com");
    }

    public void sendEmail(String subject, String body, String recipientAddress) {

        String senderAddress = "DoNotReply@72e19a70-d913-4bc2-8599-1aea3a3c297c.azurecomm.net";

        EmailClient client = new EmailClientBuilder()
                .connectionString(connectionString)
                .buildClient();

        EmailMessage message;
        message = new EmailMessage()
                .setSenderAddress(senderAddress)
                .setToRecipients(recipientAddress)
                .setSubject(subject)
                .setBodyPlainText(body)
                .setBodyHtml("<html><h1></h1><body>" + body.replaceAll("~", "<br/>") + "</body></html>");

        try
        {
            SyncPoller<EmailSendResult, EmailSendResult> poller = client.beginSend(message, null);

            PollResponse<EmailSendResult> pollResponse = null;

            Duration timeElapsed = Duration.ofSeconds(0);

            while (pollResponse == null
                    || pollResponse.getStatus() == LongRunningOperationStatus.NOT_STARTED
                    || pollResponse.getStatus() == LongRunningOperationStatus.IN_PROGRESS)
            {
                pollResponse = poller.poll();
                System.out.println("Email send poller status: " + pollResponse.getStatus());

                Thread.sleep(POLLER_WAIT_TIME.toMillis());
                timeElapsed = timeElapsed.plus(POLLER_WAIT_TIME);

                if (timeElapsed.compareTo(POLLER_WAIT_TIME.multipliedBy(18)) >= 0)
                {
                    throw new RuntimeException("Polling timed out.");
                }
            }

            if (poller.getFinalResult().getStatus() == EmailSendStatus.SUCCEEDED)
            {
                System.out.printf("Successfully sent the email (operation id: %s)", poller.getFinalResult().getId());
            }
            else
            {
                throw new RuntimeException(poller.getFinalResult().getError().getMessage());
            }
        }
        catch (Exception exception)
        {
            System.out.println(exception.getMessage());
        }

    }

}
