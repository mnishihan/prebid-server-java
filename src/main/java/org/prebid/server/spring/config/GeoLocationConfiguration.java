package org.prebid.server.spring.config;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import org.prebid.server.execution.RemoteFileSyncer;
import org.prebid.server.geolocation.CircuitBreakerSecuredGeoLocationService;
import org.prebid.server.geolocation.GeoLocationService;
import org.prebid.server.geolocation.MaxMindGeoLocationService;
import org.prebid.server.metric.Metrics;
import org.prebid.server.rubicon.geolocation.CircuitBreakerSecuredNetAcuityGeoLocationService;
import org.prebid.server.rubicon.geolocation.NetAcuityGeoLocationService;
import org.prebid.server.rubicon.geolocation.NetAcuityServerAddressProvider;
import org.prebid.server.spring.config.model.CircuitBreakerProperties;
import org.prebid.server.spring.config.model.HttpClientProperties;
import org.prebid.server.spring.config.model.RemoteFileSyncerProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GeoLocationConfiguration {

    @Configuration
    @ConditionalOnExpression("${geolocation.enabled} == true and '${geolocation.type}' == 'maxmind'")
    static class MaxMindGeoLocationConfiguration {

        @Bean
        @ConditionalOnProperty(prefix = "geolocation.circuit-breaker", name = "enabled", havingValue = "true")
        @ConfigurationProperties(prefix = "geolocation.circuit-breaker")
        CircuitBreakerProperties maxMindCircuitBreakerProperties() {
            return new CircuitBreakerProperties();
        }

        @Bean
        @ConfigurationProperties(prefix = "geolocation.maxmind.remote-file-syncer")
        RemoteFileSyncerProperties maxMindRemoteFileSyncerProperties() {
            return new RemoteFileSyncerProperties();
        }

        @Bean
        @ConditionalOnProperty(prefix = "geolocation.circuit-breaker", name = "enabled", havingValue = "false",
                matchIfMissing = true)
        GeoLocationService basicGeoLocationService(RemoteFileSyncerProperties fileSyncerProperties,
                                                   Vertx vertx) {

            return createGeoLocationService(fileSyncerProperties, vertx);
        }

        @Bean
        @ConditionalOnProperty(prefix = "geolocation.circuit-breaker", name = "enabled", havingValue = "true")
        CircuitBreakerSecuredGeoLocationService circuitBreakerSecuredGeoLocationService(
                Vertx vertx,
                Metrics metrics,
                RemoteFileSyncerProperties fileSyncerProperties,
                @Qualifier("maxMindCircuitBreakerProperties") CircuitBreakerProperties circuitBreakerProperties,
                Clock clock) {

            return new CircuitBreakerSecuredGeoLocationService(vertx,
                    createGeoLocationService(fileSyncerProperties, vertx), metrics,
                    circuitBreakerProperties.getOpeningThreshold(), circuitBreakerProperties.getOpeningIntervalMs(),
                    circuitBreakerProperties.getClosingIntervalMs(), clock);
        }

        private GeoLocationService createGeoLocationService(RemoteFileSyncerProperties fileSyncerProperties,
                                                            Vertx vertx) {

            final HttpClientProperties httpClientProperties = fileSyncerProperties.getHttpClient();
            final HttpClientOptions httpClientOptions = new HttpClientOptions()
                    .setConnectTimeout(httpClientProperties.getConnectTimeoutMs())
                    .setMaxRedirects(httpClientProperties.getMaxRedirects());

            final RemoteFileSyncer remoteFileSyncer = RemoteFileSyncer.create(fileSyncerProperties.getDownloadUrl(),
                    fileSyncerProperties.getSaveFilepath(), fileSyncerProperties.getTmpFilepath(),
                    fileSyncerProperties.getRetryCount(), fileSyncerProperties.getRetryIntervalMs(),
                    fileSyncerProperties.getTimeoutMs(), fileSyncerProperties.getUpdateIntervalMs(),
                    vertx.createHttpClient(httpClientOptions), vertx, vertx.fileSystem());
            final MaxMindGeoLocationService maxMindGeoLocationService = new MaxMindGeoLocationService();

            remoteFileSyncer.syncForFilepath(maxMindGeoLocationService);
            return maxMindGeoLocationService;
        }
    }

    @Configuration
    @ConditionalOnExpression("${geolocation.enabled} == true and '${geolocation.type}' == 'netacuity'")
    static class NetAcuityGeoLocationConfiguration {

        @Bean
        @ConditionalOnProperty(prefix = "geolocation.circuit-breaker", name = "enabled", havingValue = "true")
        @ConfigurationProperties(prefix = "geolocation.circuit-breaker")
        CircuitBreakerProperties netAcuityCircuitBreakerProperties() {
            return new CircuitBreakerProperties();
        }

        @Bean
        NetAcuityServerAddressProvider netAcuityAddressProvider(
                Vertx vertx, @Value("${geolocation.netacuity.server}") String server) {
            return NetAcuityServerAddressProvider.create(vertx, parseServerNames(server));
        }

        @Bean
        @ConditionalOnProperty(prefix = "geolocation.circuit-breaker", name = "enabled", havingValue = "false",
                matchIfMissing = true)
        NetAcuityGeoLocationService netAcuityGeoLocationService(
                Vertx vertx,
                NetAcuityServerAddressProvider addressProvider,
                Clock clock,
                Metrics metrics) {

            return createNetAcuityGeoLocationService(vertx, addressProvider, clock, metrics);
        }

        @Bean
        @ConditionalOnProperty(prefix = "geolocation.circuit-breaker", name = "enabled", havingValue = "true")
        CircuitBreakerSecuredNetAcuityGeoLocationService circuitBreakerSecuredNetAcuityGeoLocationService(
                Vertx vertx,
                NetAcuityServerAddressProvider netAcuityServerAddressProvider,
                @Qualifier("netAcuityCircuitBreakerProperties") CircuitBreakerProperties circuitBreakerProperties,
                Clock clock,
                Metrics metrics) {

            return new CircuitBreakerSecuredNetAcuityGeoLocationService(
                    createNetAcuityGeoLocationService(vertx, netAcuityServerAddressProvider, clock, metrics),
                    netAcuityServerAddressProvider, vertx, circuitBreakerProperties.getOpeningThreshold(),
                    circuitBreakerProperties.getOpeningIntervalMs(), circuitBreakerProperties.getClosingIntervalMs(),
                    clock);
        }

        private static NetAcuityGeoLocationService createNetAcuityGeoLocationService(
                Vertx vertx, NetAcuityServerAddressProvider addressProvider, Clock clock, Metrics metrics) {
            return new NetAcuityGeoLocationService(vertx, addressProvider::getServerAddress, clock, metrics);
        }

        private static Set<String> parseServerNames(String serversString) {
            Objects.requireNonNull(serversString);
            return Stream.of(serversString.split(",")).map(String::trim).collect(Collectors.toSet());
        }
    }
}
