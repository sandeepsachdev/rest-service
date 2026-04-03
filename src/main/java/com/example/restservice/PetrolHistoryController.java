package com.example.restservice;

import com.apptasticsoftware.rssreader.Item;
import com.apptasticsoftware.rssreader.RssReader;
import com.example.restservice.service.EmailSender;
import com.example.restservice.smh.SmhItem;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.System.exit;

@Controller
public class PetrolHistoryController {

    private static final Map<String, SmhItem> articlesMap = new HashMap<String, SmhItem>();
    private static final Map<String, SmhItem> excludedMap = new HashMap<String, SmhItem>();
    private static final Logger log = LoggerFactory.getLogger(PetrolHistoryController.class);

    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");

    List<String> excludedKeywordsCache = new ArrayList<>();

    Integer excludedArticles = 0;

    @Autowired
    PriceHistoryRepository priceHistoryRepository;

    @Autowired
    ExcludedKeywordRepository excludedKeywordRepository;

    @Autowired
    EmailSender emailSender;

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String homePage() {

        //SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("hh:mm a E dd/MM/yyyy");

        StringBuilder output = new StringBuilder("<html>\n" + "<header><title>Price History</title></header>\n" +
                "<style> table, th, td {\n" +
                "  border: 1px solid black;\n" +
                "  border-collapse: collapse;\n" +
                " padding: 5px;} </style> <body>\n");

        output.append("<table><tr><th>Date Time</th><th>Forcast</th></tr>");
        List<PriceHistory> history = priceHistoryRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        for (PriceHistory priceHistory : history) {
            output.append("<tr><td>").
                    append(formatter.format(priceHistory.getDatestamp())).
                    append("</td><td>").
                    append(priceHistory.getHistory()).
                    append("</td></tr>");
        }

        output.append("</table></body>\n" + "</html>");
        return output.toString();
    }

    @GetMapping("/history")
    public List<PriceHistory> showHistory() {
        return priceHistoryRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    @Scheduled(fixedRate = 1000 * 60 * 60 * 4)
    public void emailIfPetrolPriceChanged() {

        updateExcludedKeywordCache();

        PriceHistory priceHistory = new PriceHistory();
        String currentStatus = getSydneyPetrolForcast();
        priceHistory.setHistory(currentStatus);

        ZoneId myZone = ZoneId.of("Australia/Sydney");
        LocalDateTime ldt = LocalDateTime.now(myZone);
        priceHistory.setDatestamp(ldt);

        try {
            priceHistoryRepository.findMostRecent();

        } catch (Exception e) {
            emailSender.sendEmail("exception 1", "Exception was " + e.getMessage() + " " + e.getCause());
            try {
                // Expect the first one to fail. Wait some time before retrying
                Thread.sleep(1000 * 30);
            } catch (Exception ex) {
                System.out.println("Couldn't sleep");
            }

            log.warn("First attempt to read database failed", e);
        }

        try {
            //List<PriceHistory> mostRecent = priceHistoryRepository.findMostRecent();
            String mostRecent = priceHistoryRepository.findMostRecent();
            sendEmailIfAndSaveIfRequired(mostRecent, currentStatus, priceHistory);
        } catch (Exception e) {
            log.warn("2nd attempt to read database failed", e);
            emailSender.sendEmail("exception 2", "Exception was " + e.getMessage() + " " + e.getCause());
            //List<PriceHistory> mostRecent = priceHistoryRepository.findMostRecent();
            String mostRecent = priceHistoryRepository.findMostRecent();
            sendEmailIfAndSaveIfRequired(mostRecent, currentStatus, priceHistory);
        }
    }

    private void updateExcludedKeywordCache() {
        excludedKeywordsCache = excludedKeywordRepository.findAll().stream().map(ExcludedKeyword::getWord).collect(Collectors.toList());
    }

    //	@Scheduled(fixedRate = 1000*60*60)
    public void wakeupToKeepDBlive() {
        try {
            String forecast = getSydneyPetrolForcast();
            String mostRecent = priceHistoryRepository.findMostRecent();
            String message = "Woke up at " + getDateTime();
            log.info(message + " Forecast has changed:" + !mostRecent.equals(forecast));
            emailSender.sendEmail(message, message);
        } catch (Exception e) {
            log.warn("Exception when waking up", e);
            emailSender.sendEmail("exception waking up", "Exception was " + e.getMessage() + " " + e.getCause());
            throw e;
        }
    }


    private void sendEmailIfAndSaveIfRequired(String oldStatus, String newStatus, PriceHistory priceHistory) {

        if (oldStatus == null || !oldStatus.equals(newStatus)) {
            emailSender.sendEmail(newStatus, newStatus, "sandeepsachdev17@gmail.com");
            try {
                priceHistoryRepository.save(priceHistory);
            } catch (Exception ex) {
                ex.printStackTrace();
                priceHistoryRepository.save(priceHistory);
            }
        }
    }

    private String getSydneyPetrolForcast() {
        Document doc = null;
        try {
            doc = Jsoup.connect("https://www.accc.gov.au/consumers/petrol-and-fuel/petrol-price-cycles-in-the-5-largest-cities").get();
        } catch (IOException e) {
            e.printStackTrace();
        }
        Elements parents = doc.getElementById("petrol-prices-in-sydney").parent().nextElementSiblings();
        return parents.get(1).text();
    }

    @Scheduled(fixedRate = 1000 * 60, initialDelay = 1000 * 60)
    private void keepAlive() {

        log.info("Keep alive ran. The time is now {}", getDateTime());
        Document doc = null;
        try {
            doc = Jsoup.connect("https://rest-service-1750069696570.azurewebsites.net/petrol").get();
        } catch (IOException e) {
            e.printStackTrace();
        }


    }

    @Scheduled(fixedRate = 1000 * 60 * 60, initialDelay = 1000 * 60)
    private void refreshSmhFeed() {

        RssReader reader = new RssReader();
        List<Item> articles = new ArrayList<>();

        {
            try {
                articles.addAll(reader.read("https://www.smh.com.au/rss/feed.xml").collect(Collectors.toList()));
                articles.addAll(reader.read("https://www.smh.com.au/rss/politics/federal.xml").collect(Collectors.toList()));
                articles.addAll(reader.read("https://www.smh.com.au/rss/national/nsw.xml").collect(Collectors.toList()));
                articles.addAll(reader.read("https://www.smh.com.au/rss/world.xml").collect(Collectors.toList()));
                articles.addAll(reader.read("https://www.smh.com.au/rss/national.xml").collect(Collectors.toList()));
                articles.addAll(reader.read("https://www.smh.com.au/rss/business.xml").collect(Collectors.toList()));
//				articles.addAll(reader.read("https://www.smh.com.au/rss/culture.xml").collect(Collectors.toList()));
//				articles.addAll(reader.read("https://www.smh.com.au/rss/lifestyle.xml").collect(Collectors.toList()));
            } catch (IOException e) {
                e.printStackTrace();
                exit(0);
            }
        }

        List<Item> updates = new ArrayList();
        List<Item> excludes = new ArrayList();

        for (Item item : articles) {
            if (allfieldPresent(item) &&
                    !articlesMap.containsKey(item.getGuid().get()) &&
                    !excludedMap.containsKey(item.getGuid().get())) {

                String text = allText(item);

                if (!item.getLink().get().contains("sport")  && !item.getLink().get().contains("lifestyle")) {

                    log.info(item.getGuid().get() + " " + item.getLink().get());

                    if (excludedKeywordsCache.stream().noneMatch(text::contains)) {
                        articlesMap.put(item.getGuid().get(), new SmhItem(item));
                        updates.add(item);
                    } else {
                        excludedMap.put(item.getGuid().get(), new SmhItem(item));
                        excludes.add(item);
                        excludedArticles++;
                        log.warn("excluded count:" + excludedArticles + " " + text);
                    }
                }
            }
        }

        if (!updates.isEmpty()) {
            if (updates.size() == 1) {
                Item item = updates.get(0);
                String title = getShortDateTime() + " " + updates.size() + " " + item.getTitle().get();
                emailSender.sendEmail(title,
                        item.getDescription().get() + "~" +
                                item.getLink().get());
            } else {
                String title = getShortDateTime() + " " + updates.size() + " Updates";
                StringBuilder body = new StringBuilder();
                for (Item item : updates) {
                    body
                            .append(item.getTitle().get()).append("~")
                            .append(item.getDescription().get()).append("~")
                            .append(item.getLink().get());
                    body.append("~~");
                }
                emailSender.sendEmail(title, body.toString());
            }
        }

        // Excludes
        StringBuilder body = new StringBuilder();
        for (Item item : excludes) {
            body.append(item.getTitle().get()).append("~").append(item.getDescription().get()).append("~").append(item.getLink().get());
            body.append("~~");
        }

        String title = getShortDateTime() + " Excluded: " + excludes.size() + " Total: " + excludedArticles;

        if (updates.isEmpty()) {
            emailSender.sendEmail(title, body.toString());
        }

        log.info("The time is now {}", getDateTime());
    }

    private static String allText(Item item) {
        return item.getLink().get().toLowerCase(Locale.ROOT) +
                item.getDescription().get().toLowerCase() +
                item.getTitle().get().toLowerCase();
    }

    private static boolean allfieldPresent(Item item) {
        return item.getGuid().isPresent() &&
                item.getLink().isPresent() &&
                item.getDescription().isPresent() &&
                item.getTitle().isPresent();
    }

    public String getDateTime() {
        return getDateTime(true);
    }

    public String getDateTime(boolean withHost) {
        String hostName = "";

        if (withHost) {
            hostName = getHostName();
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

    public String getShortDateTime() {

        Instant nowUtc = Instant.now();
        ZoneId myZone = ZoneId.of("Australia/Sydney");
        ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(nowUtc, myZone);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(" HH:mm dd/MM");

        return getHostName().substring(0, 2) + formatter.format(zonedDateTime);
    }

    @GetMapping(value = "/smh", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String smhPage() {
        List<SmhItem> items = new ArrayList<>(articlesMap.values());
        items.sort(Comparator.comparing(SmhItem::getPubDateZonedDateTime).reversed());

        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath");
        ve.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
        ve.init();

        VelocityContext ctx = new VelocityContext();
        ctx.put("items", items);
        ctx.put("itemCount", items.size());

        Template template = ve.getTemplate("templates/smh.vm");
        StringWriter writer = new StringWriter();
        template.merge(ctx, writer);
        return writer.toString();
    }

    @GetMapping(value = "/excludes", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String smhExcludesPage() {
        List<SmhItem> items = new ArrayList<>(excludedMap.values());
        items.sort(Comparator.comparing(SmhItem::getPubDateZonedDateTime).reversed());

        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath");
        ve.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
        ve.init();

        VelocityContext ctx = new VelocityContext();
        ctx.put("items", items);
        ctx.put("itemCount", items.size());

        Template template = ve.getTemplate("templates/smh.vm");
        StringWriter writer = new StringWriter();
        template.merge(ctx, writer);
        return writer.toString();
    }

    @RequestMapping(value = "/getSmhRss", produces = "application/json")
    @ResponseBody
    public List<SmhItem> getSmhRss() {
        Collection<SmhItem> items = articlesMap.values();

        List<SmhItem> itemList = new ArrayList<>(items);
        itemList.sort(Comparator.comparing(SmhItem::getPubDateZonedDateTime).reversed());
        return itemList;
    }

}
