package com.example.restservice.greeting;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import com.azure.communication.email.models.*;
import com.azure.communication.email.*;
import com.azure.core.util.polling.*;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GreetingController {

	private static final String template = "Hello, %s!";
	private final AtomicLong counter = new AtomicLong();

	public static final Duration POLLER_WAIT_TIME = Duration.ofSeconds(10);

	@GetMapping("/greeting")
	public Greeting greeting(@RequestParam(value = "name", defaultValue = "World") String name) {

		return new Greeting(counter.incrementAndGet(), String.format(template, name));
	}

	@GetMapping("/petrol")
	public String greeting2(@RequestParam(value = "name", defaultValue = "World") String name) {
		Document doc = null;
		try {
			doc = Jsoup.connect("https://www.accc.gov.au/consumers/petrol-and-fuel/petrol-price-cycles-in-the-5-largest-cities").get();
		} catch (IOException e) {
			e.printStackTrace();
		}
		Elements parents = doc.getElementById("petrol-prices-in-sydney").parent().nextElementSiblings();
		return  "<div style='font-size: x-large;'>" + "Called " + counter.incrementAndGet() + " Times </div>" +
		        "<br/><div style='font-size: x-large;width:300px;'>"+ parents.get(1).text() + "</div>";
	}
}
