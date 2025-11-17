package com.example.restservice;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "ExcludedKeyword")
public class ExcludedKeyword {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String word;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }
}