package com.example.restservice.greeting;

import com.microsoft.sqlserver.jdbc.SQLServerDriver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    @Value("${jdbc.url}")
    private String jdbcUrl;

    @Lazy
    @Bean
    public DataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass( SQLServerDriver.class); // Replace with your driver
        dataSource.setUrl(jdbcUrl);
//        dataSource.setUrl("jdbc:sqlserver://sandeepserver2.database.windows.net:1433;database=database2;user=CloudSAe6b742c7@sandeepserver2;password=LvDpgN@aJcBrX3u;encrypt=true;trustServerCertificate=false;hostNameInCertificate=*.database.windows.net;loginTimeout=30;"); // Replace with your URL
        //
        return dataSource;
    }
}