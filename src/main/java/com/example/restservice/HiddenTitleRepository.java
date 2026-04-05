package com.example.restservice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Set;

public interface HiddenTitleRepository extends JpaRepository<HiddenTitle, Long> {
    boolean existsByTitleId(Integer titleId);

    @Query("SELECT h.titleId FROM HiddenTitle h")
    Set<Integer> findAllTitleIds();
}
