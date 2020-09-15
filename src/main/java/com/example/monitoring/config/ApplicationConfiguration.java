package com.example.monitoring.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@ComponentScan(value = "com.example.monitoring")
@PropertySource("classpath:application.properties")
public class ApplicationConfiguration {

}


