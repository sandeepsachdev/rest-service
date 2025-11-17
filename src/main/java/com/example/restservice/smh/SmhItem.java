package com.example.restservice.smh;


import com.apptasticsoftware.rssreader.Item;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class SmhItem {

    public SmhItem(Item item) {
        this.title = item.getTitle().get();
        this.description = item.getDescription().isPresent() ? item.getDescription().get() : "";
        this.link = item.getLink().get();
        this.pubDateZonedDateTime = item.getPubDateZonedDateTime().get();
        this.pubDate = pubDateZonedDateTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        this.guid = item.getGuid().get();


    }
    private String title;
    private String description;
    private String link;
    private String author;
    private String category;
    private String guid;
    private String pubDate;
    private ZonedDateTime pubDateZonedDateTime;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getGuid() {
        return guid;
    }

    public void setGuid(String guid) {
        this.guid = guid;
    }

    public String getPubDate() {
        return pubDate;
    }

    public void setPubDate(String pubDate) {
        this.pubDate = pubDate;
    }

    public ZonedDateTime getPubDateZonedDateTime() {
        return this.pubDateZonedDateTime;
    }
}

