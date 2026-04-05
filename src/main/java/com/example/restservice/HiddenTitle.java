package com.example.restservice;

import jakarta.persistence.*;

@Entity
@Table(name = "HiddenTitle")
public class HiddenTitle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title_id", unique = true, nullable = false)
    private Integer titleId;

    public HiddenTitle() {}

    public HiddenTitle(Integer titleId) {
        this.titleId = titleId;
    }

    public Long getId() { return id; }
    public Integer getTitleId() { return titleId; }
    public void setTitleId(Integer titleId) { this.titleId = titleId; }
}
