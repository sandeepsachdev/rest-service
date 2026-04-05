package com.example.restservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Controller
public class WatchmodeController {

    private static final Logger log = LoggerFactory.getLogger(WatchmodeController.class);
    private static final String API_KEY = "pLpfDe0oNjhVtgjoSIaZKxdj7mEDOG0igYM63zoB";
    private static final String BASE = "https://api.watchmode.com/v1";
    private static final String TMDB_BASE = "https://api.themoviedb.org/3";
    private static final Set<Integer> TARGET_SOURCES = Set.of(203, 372, 26, 387, 425);
    private static final long CACHE_TTL = 60 * 60 * 1000L;

    @Value("${tmdb.api.key:}")
    private String tmdbApiKey;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String cachedJson = null;
    private volatile long cacheTime = 0L;

    @GetMapping(value = "/watchmode", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String watchmodePage() {
        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath");
        ve.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
        ve.init();
        Template tpl = ve.getTemplate("templates/watchmode.vm");
        StringWriter w = new StringWriter();
        tpl.merge(new VelocityContext(), w);
        return w.toString();
    }

    @GetMapping(value = "/api/watchmode/releases", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public String getReleases() throws Exception {
        if (cachedJson != null && System.currentTimeMillis() - cacheTime < CACHE_TTL) {
            return cachedJson;
        }

        // 1. Fetch 6 months in two parallel 3-month chunks (API caps at ~249/request, returns oldest first)
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate today = LocalDate.now();
        String end1   = today.format(fmt);
        String start1 = today.minusMonths(1).format(fmt);

        CompletableFuture<String> fetch1 = CompletableFuture.supplyAsync(() -> {
            try { return httpGet(BASE + "/releases/?apiKey=" + API_KEY + "&start_date=" + start1 + "&end_date=" + end1); }
            catch (Exception e) { return "{\"releases\":[]}"; }
        });

        CompletableFuture.allOf(fetch1).join();

        List<JsonNode> allReleases = new ArrayList<>();
        for (JsonNode rel : mapper.readTree(fetch1.get()).get("releases")) allReleases.add(rel);

        // 2. Filter to target sources; deduplicate by title ID; track sources per title
        Map<Integer, List<String>> sourcesById = new LinkedHashMap<>();
        Map<Integer, JsonNode> releaseById = new LinkedHashMap<>();

        for (JsonNode rel : allReleases) {
            int sourceId = rel.get("source_id").asInt();
            if (!TARGET_SOURCES.contains(sourceId)) continue;

            int titleId = rel.get("id").asInt();
            sourcesById.computeIfAbsent(titleId, k -> new ArrayList<>()).add(rel.get("source_name").asText());
            // Keep the most recent source_release_date for each title (same title can appear
            // multiple times across chunks for different seasons/re-releases)
            releaseById.merge(titleId, rel, (existing, incoming) ->
                    nodeText(incoming, "source_release_date").compareTo(nodeText(existing, "source_release_date")) > 0
                            ? incoming : existing);
        }

        // 3. Fetch title details in parallel to get user_rating, year, plot
        List<Integer> ids = new ArrayList<>(releaseById.keySet());
        List<CompletableFuture<JsonNode>> futures = ids.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> fetchDetails(id)))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 4. Merge release + details into enriched items
        List<ObjectNode> items = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i);
            JsonNode rel = releaseById.get(id);
            JsonNode det = futures.get(i).get();

            ObjectNode item = mapper.createObjectNode();
            item.put("id", id);
            item.put("title", rel.get("title").asText());
            item.put("type", rel.get("type").asText());
            item.put("imdb_id", nodeText(rel, "imdb_id"));
            item.put("source_release_date", nodeText(rel, "source_release_date"));

            // Poster: prefer release (TMDB CDN), fall back to details
            String poster = nodeText(rel, "poster_url");
            if (poster.isEmpty()) poster = nodeText(det, "poster");
            if (poster.isEmpty()) poster = nodeText(det, "posterMedium");
            item.put("poster_url", poster);

            // Rating & year from Watchmode details
            boolean hasRating = det.has("user_rating") && !det.get("user_rating").isNull();
            if (hasRating)
                item.put("user_rating", det.get("user_rating").asDouble());
            if (det.has("critic_score") && !det.get("critic_score").isNull())
                item.put("critic_score", det.get("critic_score").asInt());
            if (det.has("year") && !det.get("year").isNull())
                item.put("year", det.get("year").asInt());
            if (det.has("plot_overview") && !det.get("plot_overview").isNull())
                item.put("plot_overview", det.get("plot_overview").asText());

            // TMDB rating fallback when Watchmode has no user_rating
            if (!hasRating && !tmdbApiKey.isBlank()) {
                JsonNode release = releaseById.get(id);
                String tmdbType = nodeText(release, "tmdb_type");
                String tmdbId   = nodeText(release, "tmdb_id");
                if (!tmdbType.isBlank() && !tmdbId.isBlank()) {
                    double tmdbRating = fetchTmdbRating(tmdbType, tmdbId);
                    if (tmdbRating > 0)
                        item.put("user_rating", tmdbRating);
                }
            }

            // Sources (deduplicated)
            ArrayNode sources = mapper.createArrayNode();
            sourcesById.get(id).stream().distinct().forEach(sources::add);
            item.set("sources", sources);

            items.add(item);
        }

        // Sort by release date descending
        items.sort((a, b) -> b.get("source_release_date").asText()
                .compareTo(a.get("source_release_date").asText()));

        ArrayNode arr = mapper.createArrayNode();
        items.forEach(arr::add);

        String json = mapper.writeValueAsString(Map.of("titles", arr));
        cachedJson = json;
        cacheTime = System.currentTimeMillis();
        return json;
    }

    @GetMapping(value = "/watchmode/title/{id}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String titleDetailPage(@PathVariable int id) throws Exception {
        JsonNode det = fetchDetails(id);

        List<String[]> rows = new ArrayList<>();
        det.fields().forEachRemaining(e -> {
            String value = e.getValue().isNull() ? "" : e.getValue().toString().replaceAll("^\"|\"$", "");
            rows.add(new String[]{e.getKey(), value});
        });

        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath");
        ve.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
        ve.init();

        VelocityContext ctx = new VelocityContext();
        ctx.put("rows", rows);
        ctx.put("title", det.has("title") ? det.get("title").asText() : "Title #" + id);

        Template tpl = ve.getTemplate("templates/watchmode-title.vm");
        StringWriter w = new StringWriter();
        tpl.merge(ctx, w);
        return w.toString();
    }

    private double fetchTmdbRating(String type, String tmdbId) {
        try {
            String endpoint = "movie".equals(type) ? "/movie/" : "/tv/";
            String url = TMDB_BASE + endpoint + tmdbId + "?api_key=" + tmdbApiKey;
            JsonNode node = mapper.readTree(httpGet(url));
            if (node.has("vote_average") && !node.get("vote_average").isNull()) {
                double rating = node.get("vote_average").asDouble();
                if (rating > 0) return Math.round(rating * 10.0) / 10.0;
            }
        } catch (Exception e) {
            log.warn("TMDB rating fetch failed for {} {}: {}", type, tmdbId, e.getMessage());
        }
        return 0;
    }

    private JsonNode fetchDetails(int id) {
        try {
            String body = httpGet(BASE + "/title/" + id + "/details/?apiKey=" + API_KEY);
            return mapper.readTree(body);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private String httpGet(String url) throws Exception {
        log.info("Watchmode GET {}", url);
        long start = System.currentTimeMillis();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.info("Watchmode {} {} ({}ms)", response.statusCode(), url, System.currentTimeMillis() - start);
        return response.body();
    }

    private String nodeText(JsonNode node, String field) {
        return (node.has(field) && !node.get(field).isNull()) ? node.get(field).asText() : "";
    }
}