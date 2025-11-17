package com.example.restservice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    @Query(value = "select top 1 history from dbo.price_history order by id desc", nativeQuery = true )
    String findMostRecent();

}