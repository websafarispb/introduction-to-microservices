package com.epam.learn.microservices.resourceservice.client;

import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class LoadBalancedRestClientConfig {

    /**
     * Replaces Boot's auto-configured builder with one that has the same Boot configuration (message
     * converters, customizers) plus Spring Cloud LoadBalancer, which resolves service ids such as
     * {@code song-service} to registered instances.
     */
    @Bean
    @LoadBalanced
    @Scope("prototype")
    RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }
}
