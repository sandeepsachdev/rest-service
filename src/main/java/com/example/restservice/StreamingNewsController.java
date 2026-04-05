package com.example.restservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Controller
public class StreamingNewsController {

    private static final Logger log = LoggerFactory.getLogger(StreamingNewsController.class);

    private static final List<String[]> FEEDS = List.of(
            new String[]{"BBC News",      "https://feeds.bbci.co.uk/news/rss.xml"},
            new String[]{"The Guardian",  "https://www.theguardian.com/world/rss"},
            new String[]{"NPR News",      "https://feeds.npr.org/1001/rss.xml"},
            new String[]{"Al Jazeera",    "https://www.aljazeera.com/xml/rss/all.xml"},
            new String[]{"ABC News",      "https://feeds.abcnews.com/abcnews/topstories"},
            new String[]{"Sky News",      "https://feeds.skynews.com/feeds/rss/world.xml"},
            new String[]{"CBC News",      "https://www.cbc.ca/cmlink/rss-topstories"},
            new String[]{"Hacker News",   "https://hnrss.org/frontpage"}
    );

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Set<String> seenLinks = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 2000;
        }
    });
    private final Deque<Map<String, String>> recentArticles = new ArrayDeque<>();
    private static final int MAX_RECENT = 100;

    @GetMapping(value = "/streaming-news", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String page() {
        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath");
        ve.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
        ve.init();
        Template tpl = ve.getTemplate("templates/streaming-news.vm");
        StringWriter w = new StringWriter();
        tpl.merge(new VelocityContext(), w);
        return w.toString();
    }

    @GetMapping(value = "/api/streaming-news/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        // recentArticles is newest-first; reverse to oldest-first so the client
        // can prepend each one and end up with newest at the top.
        List<Map<String, String>> snapshot;
        synchronized (recentArticles) {
            snapshot = new ArrayList<>(recentArticles);
        }
        Collections.reverse(snapshot);
        try {
            for (Map<String, String> article : snapshot) {
                emitter.send(SseEmitter.event()
                        .name("article")
                        .data(mapper.writeValueAsString(article)));
            }
        } catch (Exception e) {
            emitters.remove(emitter);
        }

        return emitter;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 2_000)
    public void pollFeeds() {
        List<Map<String, String>> newArticles = new ArrayList<>();

        for (String[] feed : FEEDS) {
            String source = feed[0];
            String url    = feed[1];
            try {
                String xml = fetchXml(url);
                List<Map<String, String>> items = parseRss(xml, source);
                for (Map<String, String> item : items) {
                    String link = item.get("link");
                    if (link != null && seenLinks.add(link)) {
                        newArticles.add(item);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to fetch feed {} - {}", source, e.getMessage());
            }
        }

        if (newArticles.isEmpty()) return;

        // Sort oldest-first by parsed pubDate so that when the client prepends each
        // article the newest one ends up at the top.
        newArticles.sort(Comparator.comparingLong(a -> Long.parseLong(a.getOrDefault("pubDateMs", "0"))));

        synchronized (recentArticles) {
            // addFirst for each oldest-to-newest article → newest ends up at front of deque
            for (Map<String, String> a : newArticles) {
                recentArticles.addFirst(a);
            }
            while (recentArticles.size() > MAX_RECENT) {
                recentArticles.removeLast();
            }
        }

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                for (Map<String, String> article : newArticles) {
                    emitter.send(SseEmitter.event()
                            .name("article")
                            .data(mapper.writeValueAsString(article)));
                }
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);

        log.info("Pushed {} new articles to {} clients", newArticles.size(), emitters.size() - dead.size());
    }

    private String fetchXml(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0 (compatible; NewsReader/1.0)")
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private List<Map<String, String>> parseRss(String xml, String source) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        List<Map<String, String>> items = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("item");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            String title   = text(item, "title");
            String link    = extractLink(item);
            String desc    = text(item, "description");
            String pubDate = text(item, "pubDate");

            if (title.isBlank() || link.isBlank()) continue;

            desc = desc.replaceAll("<[^>]*>", "").trim();
            if (desc.length() > 200) desc = desc.substring(0, 200) + "…";

            long pubDateMs = parsePubDateMs(pubDate);

            Map<String, String> map = new LinkedHashMap<>();
            map.put("title",     title);
            map.put("link",      link);
            map.put("desc",      desc);
            map.put("pubDate",   pubDate);
            map.put("pubDateMs", String.valueOf(pubDateMs));
            map.put("source",    source);
            items.add(map);
        }
        return items;
    }

    /**
     * Extract the article URL from an RSS item. Tries, in order:
     * 1. Text content of &lt;link&gt;
     * 2. Text content of &lt;guid&gt; when it looks like a URL
     * 3. href attribute of &lt;atom:link&gt; (used by some Atom-hybrid feeds)
     */
    private String extractLink(Element item) {
        String link = text(item, "link");
        if (!link.isBlank()) return link;

        String guid = text(item, "guid");
        if (!guid.isBlank() && (guid.startsWith("http://") || guid.startsWith("https://"))) return guid;

        // atom:link (non-namespace-aware parser sees it as "atom:link")
        NodeList atomLinks = item.getElementsByTagName("atom:link");
        for (int i = 0; i < atomLinks.getLength(); i++) {
            Element al = (Element) atomLinks.item(i);
            String rel  = al.getAttribute("rel");
            String href = al.getAttribute("href");
            if (!href.isBlank() && (rel.isEmpty() || "alternate".equals(rel))) return href;
        }
        return "";
    }

    /** Parse an RFC 822 pubDate string to epoch milliseconds; returns now() on failure. */
    private long parsePubDateMs(String pubDate) {
        if (pubDate.isBlank()) return System.currentTimeMillis();
        String[] formats = {
            "EEE, dd MMM yyyy HH:mm:ss z",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "dd MMM yyyy HH:mm:ss z"
        };
        for (String fmt : formats) {
            try {
                return new SimpleDateFormat(fmt, Locale.ENGLISH).parse(pubDate).getTime();
            } catch (Exception ignored) {}
        }
        log.debug("Could not parse pubDate: {}", pubDate);
        return System.currentTimeMillis();
    }

    private String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        return nl.item(0).getTextContent().trim();
    }
}