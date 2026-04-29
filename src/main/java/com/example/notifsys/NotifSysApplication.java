package com.example.notifsys;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NotifSysApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifSysApplication.class, args);
    }

}
