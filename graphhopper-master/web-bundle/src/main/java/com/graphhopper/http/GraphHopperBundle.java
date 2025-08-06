/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.http;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.gtfs.*;
import com.graphhopper.http.health.GraphHopperHealthCheck;
import com.graphhopper.isochrone.algorithm.JTSTriangulator;
import com.graphhopper.isochrone.algorithm.Triangulator;
import com.graphhopper.jackson.Jackson;
import com.graphhopper.matching.MapMatching;
import com.graphhopper.resources.*;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.traffic.TrafficAwareCustomModelCreator;
import com.graphhopper.traffic.TrafficAwareRequestTransformer;
import com.graphhopper.traffic.SpeedLimitAwareRequestTransformer;
import com.graphhopper.traffic.SpeedLimitService;
import com.graphhopper.traffic.TrafficDataService;
import com.graphhopper.traffic.CustomAreaDataService;
import com.graphhopper.traffic.CustomAreaGeometryMatcher;
import com.graphhopper.TrafficAwareGraphHopper;
import com.graphhopper.util.PMap;
import com.graphhopper.util.TranslationMap;
import com.graphhopper.util.details.PathDetailsBuilderFactory;
import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.core.ConfiguredBundle;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import org.apache.hc.client5.http.classic.HttpClient;
import org.glassfish.hk2.api.Factory;
import org.glassfish.hk2.utilities.binding.AbstractBinder;

import javax.inject.Inject;
import javax.inject.Singleton;

public class GraphHopperBundle implements ConfiguredBundle<GraphHopperBundleConfiguration> {

    static class TranslationMapFactory implements Factory<TranslationMap> {

        @Inject
        GraphHopper graphHopper;

        @Override
        public TranslationMap provide() {
            return graphHopper.getTranslationMap();
        }

        @Override
        public void dispose(TranslationMap instance) {

        }
    }

    static class BaseGraphFactory implements Factory<BaseGraph> {

        @Inject
        GraphHopper graphHopper;

        @Override
        public BaseGraph provide() {
            return graphHopper.getBaseGraph();
        }

        @Override
        public void dispose(BaseGraph instance) {

        }
    }

    static class GtfsStorageFactory implements Factory<GtfsStorage> {

        @Inject
        GraphHopperGtfs graphHopper;

        @Override
        public GtfsStorage provide() {
            return graphHopper.getGtfsStorage();
        }

        @Override
        public void dispose(GtfsStorage instance) {

        }
    }

    static class EncodingManagerFactory implements Factory<EncodingManager> {

        @Inject
        GraphHopper graphHopper;

        @Override
        public EncodingManager provide() {
            return graphHopper.getEncodingManager();
        }

        @Override
        public void dispose(EncodingManager instance) {

        }
    }

    static class LocationIndexFactory implements Factory<LocationIndex> {

        @Inject
        GraphHopper graphHopper;

        @Override
        public LocationIndex provide() {
            return graphHopper.getLocationIndex();
        }

        @Override
        public void dispose(LocationIndex instance) {

        }
    }

    static class ProfileResolverFactory implements Factory<ProfileResolver> {
        @Inject
        GraphHopper graphHopper;

        @Override
        public ProfileResolver provide() {
            return new ProfileResolver(graphHopper.getProfiles());
        }

        @Override
        public void dispose(ProfileResolver instance) {

        }
    }

    static class GHRequestTransformerFactory implements Factory<GHRequestTransformer> {
        @Override
        public GHRequestTransformer provide() {
            return req -> req;
        }

        @Override
        public void dispose(GHRequestTransformer instance) {
        }
    }

    static class PathDetailsBuilderFactoryFactory implements Factory<PathDetailsBuilderFactory> {

        @Inject
        GraphHopper graphHopper;

        @Override
        public PathDetailsBuilderFactory provide() {
            return graphHopper.getPathDetailsBuilderFactory();
        }

        @Override
        public void dispose(PathDetailsBuilderFactory profileResolver) {

        }
    }

    static class MapMatchingRouterFactoryFactory implements Factory<MapMatchingResource.MapMatchingRouterFactory> {

        @Inject
        GraphHopper graphHopper;

        @Override
        public MapMatchingResource.MapMatchingRouterFactory provide() {
            return new MapMatchingResource.MapMatchingRouterFactory() {
                @Override
                public MapMatching.Router createMapMatchingRouter(PMap hints) {
                    return MapMatching.routerFromGraphHopper(graphHopper, hints);
                }
            };
        }

        @Override
        public void dispose(MapMatchingResource.MapMatchingRouterFactory mapMatchingRouterFactory) {

        }
    }

    static class HasElevation implements Factory<Boolean> {

        @Inject
        GraphHopper graphHopper;

        @Override
        public Boolean provide() {
            return graphHopper.hasElevation();
        }

        @Override
        public void dispose(Boolean instance) {

        }
    }

    private static class EmptyRealtimeFeedFactory implements Factory<RealtimeFeed> {

        private final GtfsStorage staticGtfs;

        @Inject
        EmptyRealtimeFeedFactory(GtfsStorage staticGtfs) {
            this.staticGtfs = staticGtfs;
        }

        @Override
        public RealtimeFeed provide() {
            return RealtimeFeed.empty();
        }

        @Override
        public void dispose(RealtimeFeed realtimeFeed) {

        }
    }

    static class TrafficDataServiceFactory implements Factory<TrafficDataService> {

        @Inject
        GraphHopperConfig config;

        @Override
        public TrafficDataService provide() {
            String trafficApiUrl = config.getString("traffic.api.url", "https://api.metasyon.com/eds_data");
            TrafficDataService service = new TrafficDataService(trafficApiUrl);
            
            boolean autoStart = config.getBool("traffic.auto_start", true);
            if (autoStart) {
                service.start();
            }
            
            return service;
        }

        @Override
        public void dispose(TrafficDataService instance) {
            if (instance != null) {
                instance.stop();
            }
        }
    }
    
    static class CustomAreaDataServiceFactory implements Factory<CustomAreaDataService> {

        @Inject
        GraphHopperConfig config;

        @Override
        public CustomAreaDataService provide() {
            CustomAreaDataService service = new CustomAreaDataService();
            
            // Configure API URL from config if available
            if (config.has("graphhopper.custom_areas.api.url")) {
                String apiUrl = config.getString("graphhopper.custom_areas.api.url", "http://20.199.11.220:9000/custom-areas");
                if (!apiUrl.isEmpty()) {
                    service.setCustomAreaApiUrl(apiUrl);
                }
            }
            
            return service;
        }

        @Override
        public void dispose(CustomAreaDataService instance) {
            if (instance != null) {
                instance.shutdown();
            }
        }
    }
    
    static class CustomAreaGeometryMatcherFactory implements Factory<CustomAreaGeometryMatcher> {

        @Inject
        BaseGraph baseGraph;

        @Override
        public CustomAreaGeometryMatcher provide() {
            return new CustomAreaGeometryMatcher(baseGraph);
        }

        @Override
        public void dispose(CustomAreaGeometryMatcher instance) {
            // No cleanup needed
        }
    }

    static class TrafficAwareCustomModelCreatorFactory implements Factory<TrafficAwareCustomModelCreator> {

        @Inject
        BaseGraph baseGraph;
        
        @Inject
        CustomAreaDataService customAreaDataService;
        
        @Inject
        CustomAreaGeometryMatcher customAreaGeometryMatcher;
        
        @Inject
        GraphHopper graphHopper;

        @Override
        public TrafficAwareCustomModelCreator provide() {
            TrafficAwareCustomModelCreator creator = new TrafficAwareCustomModelCreator(baseGraph, customAreaDataService, customAreaGeometryMatcher);
            
            // Set the creator on TrafficAwareGraphHopper if applicable
            if (graphHopper instanceof TrafficAwareGraphHopper) {
                ((TrafficAwareGraphHopper) graphHopper).setTrafficAwareCustomModelCreator(creator);
            }
            
            return creator;
        }

        @Override
        public void dispose(TrafficAwareCustomModelCreator instance) {
            if (instance != null) {
                instance.clearCaches();
            }
        }
    }



        static class SpeedLimitServiceFactory implements Factory<SpeedLimitService> {

        @Inject
        GraphHopperConfig config;

        @Override
        public SpeedLimitService provide() {
            String speedLimitApiUrl = config.getString("graphhopper.speed_limits.api.url", 
                "http://20.199.11.220:9000/speedlimit/speedlimits");
            return new SpeedLimitService(speedLimitApiUrl);
        }

        @Override
        public void dispose(SpeedLimitService instance) {
            if (instance != null) {
                instance.shutdown();
            }
        }
    }

    static class SpeedLimitAwareRequestTransformerFactory implements Factory<SpeedLimitAwareRequestTransformer> {

        @Inject
        GraphHopperConfig config;

        @Inject
        BaseGraph baseGraph;

        @Inject
        TrafficAwareCustomModelCreator customModelCreator;

        @Inject
        SpeedLimitService speedLimitService;

        @Override
        public SpeedLimitAwareRequestTransformer provide() {
            return new SpeedLimitAwareRequestTransformer(config, baseGraph, customModelCreator, speedLimitService);
        }

        @Override
        public void dispose(SpeedLimitAwareRequestTransformer instance) {

        }
    }

    static class TrafficAwareRequestTransformerFactory implements Factory<TrafficAwareRequestTransformer> {

        @Inject
        GraphHopper graphHopper;

        @Inject
        TrafficDataService trafficDataService;

        @Inject
        CustomAreaDataService customAreaDataService;

        @Inject
        TrafficAwareCustomModelCreator customModelCreator;

        @Override
        public TrafficAwareRequestTransformer provide() {
            return new TrafficAwareRequestTransformer(graphHopper, trafficDataService, customAreaDataService, customModelCreator);
        }

        @Override
        public void dispose(TrafficAwareRequestTransformer instance) {

        }
    }

    @Override
    public void initialize(Bootstrap<?> bootstrap) {
        // See #1440: avoids warning regarding com.fasterxml.jackson.module.afterburner.util.MyClassLoader
        bootstrap.setObjectMapper(io.dropwizard.jackson.Jackson.newMinimalObjectMapper());
        // avoids warning regarding com.fasterxml.jackson.databind.util.ClassUtil
        bootstrap.getObjectMapper().registerModule(new Jdk8Module());

        Jackson.initObjectMapper(bootstrap.getObjectMapper());
        bootstrap.getObjectMapper().setDateFormat(new StdDateFormat());
        // See https://github.com/dropwizard/dropwizard/issues/1558
        bootstrap.getObjectMapper().enable(MapperFeature.ALLOW_EXPLICIT_PROPERTY_RENAMING);
    }

    @Override
    public void run(GraphHopperBundleConfiguration configuration, Environment environment) {
        for (Object k : System.getProperties().keySet()) {
            if (k instanceof String && ((String) k).startsWith("graphhopper."))
                throw new IllegalArgumentException("You need to prefix system parameters with '-Ddw.graphhopper.' instead of '-Dgraphhopper.' see #1879 and #1897");
        }

        // When Dropwizard's Hibernate Validation misvalidates a query parameter,
        // a JerseyViolationException is thrown.
        // With this mapper, we use our custom format for that (backwards compatibility),
        // and also coerce the media type of the response to JSON, so we can return JSON error
        // messages from methods that normally have a different return type.
        // That's questionable, but on the other hand, Dropwizard itself does the same thing,
        // not here, but in a different place (the custom parameter parsers).
        // So for the moment we have to assume that both mechanisms
        // a) always return JSON error messages, and
        // b) there's no need to annotate the method with media type JSON for that.
        //
        // However, for places that throw IllegalArgumentException or MultiException,
        // we DO need to use the media type JSON annotation, because
        // those are agnostic to the media type (could be GPX!), so the server needs to know
        // that a JSON error response is supported. (See below.)
        environment.jersey().register(new GHJerseyViolationExceptionMapper());

        // If the "?type=gpx" parameter is present, sets a corresponding media type header
        environment.jersey().register(new TypeGPXFilter());

        // Together, these two take care that MultiExceptions thrown from RouteResource
        // come out as JSON or GPX, depending on the media type
        environment.jersey().register(new MultiExceptionMapper());
        environment.jersey().register(new MultiExceptionGPXMessageBodyWriter());

        // This makes an IllegalArgumentException come out as a MultiException with
        // a single entry.
        environment.jersey().register(new IllegalArgumentExceptionMapper());

        final GraphHopperManaged graphHopperManaged = new GraphHopperManaged(configuration.getGraphHopperConfiguration());
        environment.lifecycle().manage(graphHopperManaged);
        final GraphHopper graphHopper = graphHopperManaged.getGraphHopper();
        
        // TrafficAwareCustomModelCreator will be initialized in GraphHopperManaged.start() after GraphHopper is loaded
        environment.jersey().register(new AbstractBinder() {
            @Override
            protected void configure() {
                bind(configuration.getGraphHopperConfiguration()).to(GraphHopperConfig.class);
                bind(graphHopper).to(GraphHopper.class);

                bind(new JTSTriangulator(graphHopper.getRouterConfig())).to(Triangulator.class);
                bindFactory(MapMatchingRouterFactoryFactory.class).to(MapMatchingResource.MapMatchingRouterFactory.class);
                bindFactory(PathDetailsBuilderFactoryFactory.class).to(PathDetailsBuilderFactory.class);
                bindFactory(ProfileResolverFactory.class).to(ProfileResolver.class);
                bindFactory(GHRequestTransformerFactory.class).to(GHRequestTransformer.class);
                bindFactory(HasElevation.class).to(Boolean.class).named("hasElevation");
                bindFactory(LocationIndexFactory.class).to(LocationIndex.class);
                bindFactory(TranslationMapFactory.class).to(TranslationMap.class);
                bindFactory(EncodingManagerFactory.class).to(EncodingManager.class);
                bindFactory(BaseGraphFactory.class).to(BaseGraph.class);
                bindFactory(GtfsStorageFactory.class).to(GtfsStorage.class);
                
                // Traffic-aware routing services
                bindFactory(TrafficDataServiceFactory.class).to(TrafficDataService.class).in(Singleton.class);
                bindFactory(CustomAreaDataServiceFactory.class).to(CustomAreaDataService.class).in(Singleton.class);
                bindFactory(CustomAreaGeometryMatcherFactory.class).to(CustomAreaGeometryMatcher.class).in(Singleton.class);
                bindFactory(TrafficAwareCustomModelCreatorFactory.class).to(TrafficAwareCustomModelCreator.class).in(Singleton.class);
                bindFactory(TrafficAwareRequestTransformerFactory.class).to(TrafficAwareRequestTransformer.class).in(Singleton.class);
                
                // Speed limit services
                bindFactory(SpeedLimitServiceFactory.class).to(SpeedLimitService.class).in(Singleton.class);
                bindFactory(SpeedLimitAwareRequestTransformerFactory.class).to(SpeedLimitAwareRequestTransformer.class).in(Singleton.class);
            }
        });

        environment.jersey().register(MVTResource.class);
        environment.jersey().register(NearestResource.class);
        environment.jersey().register(RouteResource.class);
        environment.jersey().register(IsochroneResource.class);
        environment.jersey().register(MapMatchingResource.class);
        if (configuration.getGraphHopperConfiguration().has("gtfs.file")) {
            // These are pt-specific implementations of /route and /isochrone, but the same API.
            // We serve them under different paths (/route-pt and /isochrone-pt), and forward
            // requests for ?vehicle=pt there.
            environment.jersey().register(new AbstractBinder() {
                @Override
                protected void configure() {
                    if (configuration.getGraphHopperConfiguration().getBool("gtfs.free_walk", false)) {
                        bind(PtRouterFreeWalkImpl.class).to(PtRouter.class);
                    } else {
                        bind(PtRouterImpl.class).to(PtRouter.class);
                    }
                }
            });
            environment.jersey().register(PtRouteResource.class);
            environment.jersey().register(PtIsochroneResource.class);
            environment.jersey().register(PtMVTResource.class);
            environment.jersey().register(PtRedirectFilter.class);
        }
        environment.jersey().register(SPTResource.class);
        environment.jersey().register(I18NResource.class);
        environment.jersey().register(InfoResource.class);
        environment.healthChecks().register("graphhopper", new GraphHopperHealthCheck(graphHopper));
        environment.jersey().register(environment.healthChecks());
        environment.jersey().register(HealthCheckResource.class);

        if (configuration.gtfsrealtime().getFeeds().isEmpty()) {
            environment.jersey().register(new AbstractBinder() {
                @Override
                protected void configure() {
                    bindFactory(EmptyRealtimeFeedFactory.class).to(RealtimeFeed.class).in(Singleton.class);
                }
            });
        } else {
            final HttpClient httpClient = new HttpClientBuilder(environment)
                    .using(configuration.gtfsrealtime().getHttpClientConfiguration())
                    .build("gtfs-realtime-feed-loader");
            RealtimeFeedLoadingCache realtimeFeedLoadingCache = new RealtimeFeedLoadingCache(((GraphHopperGtfs) graphHopper), httpClient, configuration);
            environment.lifecycle().manage(realtimeFeedLoadingCache);
            environment.jersey().register(new AbstractBinder() {
                @Override
                protected void configure() {
                    bind(httpClient).to(HttpClient.class);
                    bindFactory(realtimeFeedLoadingCache).to(RealtimeFeed.class);
                }
            });
        }
    }
}
