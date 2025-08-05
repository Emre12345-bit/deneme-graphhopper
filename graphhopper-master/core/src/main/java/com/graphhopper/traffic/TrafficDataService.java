package com.graphhopper.traffic;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service that fetches EDS traffic data from external API on startup and every 24 hours
 * and provides traffic conditions for route calculation
 */
public class TrafficDataService {
    private static final Logger logger = LoggerFactory.getLogger(TrafficDataService.class);
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GeometryFactory geometryFactory;
    private final ScheduledExecutorService scheduler;
    private final String edsApiUrl;
    
    private final Map<String, EdsRoadData> currentEdsData = new ConcurrentHashMap<>();
    private volatile boolean isRunning = false;
    
    public TrafficDataService(String edsApiUrl) {
        this.edsApiUrl = edsApiUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.geometryFactory = new GeometryFactory();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EdsDataUpdater");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Start the EDS data fetching service
     */
    public void start() {
        if (isRunning) {
            logger.info("EDS data service is already running");
            return;
        }
        
        logger.info("Starting EDS data service with API URL: {}", edsApiUrl);
        isRunning = true;
        
        try {
            // Fetch immediately on start
            logger.info("Performing initial EDS data fetch...");
            fetchEdsData();
            
            // Schedule to fetch every 24 hours
            scheduler.scheduleAtFixedRate(this::fetchEdsData, 24, 24, TimeUnit.HOURS);
            logger.info("EDS data service started successfully. Next update in 24 hours.");
        } catch (Exception e) {
            logger.error("Failed to start EDS data service", e);
            isRunning = false;
            throw new RuntimeException("EDS data service startup failed", e);
        }
    }
    
    /**
     * Stop the EDS data fetching service
     */
    public void stop() {
        if (!isRunning) {
            return;
        }
        
        logger.info("Stopping EDS data service");
        isRunning = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Get current EDS road data
     */
    public Map<String, EdsRoadData> getCurrentEdsData() {
        return new ConcurrentHashMap<>(currentEdsData);
    }
    
    /**
     * Get current traffic data - backward compatibility
     * @deprecated Use getCurrentEdsData() instead
     */
    @Deprecated
    public Map<String, TrafficData> getCurrentTrafficData() {
        Map<String, TrafficData> legacyData = new ConcurrentHashMap<>();
        for (Map.Entry<String, EdsRoadData> entry : currentEdsData.entrySet()) {
            EdsRoadData edsData = entry.getValue();
            // EDS yolları için default değerler (sadece compatibility için)
            TrafficData trafficData = new TrafficData(
                edsData.getRoadId(),
                edsData.getGeometry(),
                0.3, // Default traffic density
                50.0, // Default speed limit
                40.0, // Default current speed
                edsData.getTimestamp()
            );
            legacyData.put(entry.getKey(), trafficData);
        }
        return legacyData;
    }
    
    /**
     * Fetch EDS data from external API
     */
    private void fetchEdsData() {
        try {
            logger.info("Fetching EDS data from: {}", edsApiUrl);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(edsApiUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            
            logger.debug("Sending HTTP request to EDS endpoint...");
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            logger.info("EDS API responded with status code: {}", response.statusCode());
            
            if (response.statusCode() == 200) {
                String responseBody = response.body();
                logger.debug("EDS response body length: {} characters", responseBody.length());
                
                // EDS servisi bir array döndürüyor, birden fazla GeoJSON dosyası içerebilir
                List<EdsGeoJsonResponse> geoJsonResponses = objectMapper.readValue(
                        responseBody, 
                        new TypeReference<List<EdsGeoJsonResponse>>() {}
                );
                
                logger.info("Successfully parsed {} GeoJSON responses from EDS", geoJsonResponses.size());
                processEdsDataArray(geoJsonResponses);
                logger.info("Successfully updated EDS data for {} roads", currentEdsData.size());
                
                // Find user's test road
                for (String roadName : currentEdsData.keySet()) {
                    EdsRoadData road = currentEdsData.get(roadName);
                    Coordinate[] coords = road.getGeometry().getCoordinates();
                    
                    if (coords.length > 0) {
                        Coordinate first = coords[0];
                        boolean isTestRoad = Math.abs(first.x - 32.5115617335038) < 0.0001 && 
                                           Math.abs(first.y - 37.9397781219424) < 0.0001;
                        
                        if (isTestRoad) {
                            logger.info("🎯 FOUND USER TEST ROAD: '{}'", roadName);
                        }
                    }
                }
            } else {
                logger.error("Failed to fetch EDS data. HTTP status: {}, Response body: {}", 
                           response.statusCode(), response.body());
            }
            
        } catch (IOException e) {
            logger.error("IO Error fetching EDS data from {}: {}", edsApiUrl, e.getMessage(), e);
        } catch (InterruptedException e) {
            logger.error("Request interrupted while fetching EDS data: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Unexpected error fetching EDS data: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Process EDS data from array of GeoJSON responses
     */
    private void processEdsDataArray(List<EdsGeoJsonResponse> geoJsonResponses) {
        Map<String, EdsRoadData> newEdsData = new ConcurrentHashMap<>();
        
        logger.info("Processing {} EDS GeoJSON files", geoJsonResponses.size());
        
        for (EdsGeoJsonResponse geoJsonResponse : geoJsonResponses) {
            processEdsData(geoJsonResponse, newEdsData);
        }
        
        // Update current EDS data atomically
        currentEdsData.clear();
        currentEdsData.putAll(newEdsData);
        logger.info("Successfully updated {} EDS road entries from {} files", newEdsData.size(), geoJsonResponses.size());
    }
    
    /**
     * Process EDS data from single GeoJSON response
     */
    private void processEdsData(EdsGeoJsonResponse geoJsonResponse, Map<String, EdsRoadData> edsDataMap) {
        if (geoJsonResponse.features == null) {
            logger.warn("No features found in EDS GeoJSON response");
            return;
        }
        
        logger.info("Processing {} EDS features from file: {}", geoJsonResponse.features.size(), 
                   geoJsonResponse.filename != null ? geoJsonResponse.filename : "unknown");
        
        for (EdsFeature feature : geoJsonResponse.features) {
            try {
                if (feature.geometry == null || !"LineString".equals(feature.geometry.type)) {
                    logger.debug("Skipping feature with non-LineString geometry");
                    continue;
                }
                
                // Convert GeoJSON coordinates to LineString
                LineString lineString = createLineStringFromGeoJson(feature.geometry.coordinates);
                
                // Use feature name as road ID, or generate one if not available
                String roadId = feature.properties != null && feature.properties.Name != null 
                    ? feature.properties.Name 
                    : "road_" + System.currentTimeMillis() + "_" + edsDataMap.size();
                
                // EDS verilerini sadece geometry ve name için kullan
                // Hız limiti ve trafik yoğunluğu bilgisi yok, sadece konum ve ad var
                
                EdsRoadData edsRoadData = new EdsRoadData(
                        roadId,
                        lineString,
                        System.currentTimeMillis()
                );
                
                edsDataMap.put(roadId, edsRoadData);
                logger.debug("Processed EDS road: {} with {} coordinates", 
                           roadId, lineString.getCoordinates().length);
                
            } catch (Exception e) {
                logger.warn("Failed to process EDS feature: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Create LineString from GeoJSON coordinate array
     */
    private LineString createLineStringFromGeoJson(Object coordinatesObj) {
        if (coordinatesObj == null) {
            throw new IllegalArgumentException("Coordinates cannot be null");
        }
        
        try {
            // Parse coordinates as List of Lists
            @SuppressWarnings("unchecked")
            List<List<Double>> coordinatesList = (List<List<Double>>) coordinatesObj;
            
            if (coordinatesList.size() < 2) {
                throw new IllegalArgumentException("LineString must have at least 2 coordinates");
            }
            
            Coordinate[] coords = new Coordinate[coordinatesList.size()];
            for (int i = 0; i < coordinatesList.size(); i++) {
                List<Double> point = coordinatesList.get(i);
                if (point.size() < 2) {
                    throw new IllegalArgumentException("Each coordinate must have at least 2 elements (lon, lat)");
                }
                // GeoJSON format: [longitude, latitude, altitude(optional)]
                coords[i] = new Coordinate(point.get(0), point.get(1));
            }
            
            return geometryFactory.createLineString(coords);
            
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Invalid coordinates format: " + e.getMessage());
        }
    }
    
    /**
     * GeoJSON response structure for EDS data
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class EdsGeoJsonResponse {
        @JsonProperty("__filename")
        public String filename;
        
        @JsonProperty("crs")
        public EdsCrs crs;
        
        @JsonProperty("features")
        public List<EdsFeature> features;
        
        @JsonProperty("name")
        public String name; // EDS'den gelen name field'i için
    }
    
    /**
     * CRS information from GeoJSON
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class EdsCrs {
        @JsonProperty("properties")
        public EdsCrsProperties properties;
        
        @JsonProperty("type")
        public String type;
    }
    
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class EdsCrsProperties {
        @JsonProperty("name")
        public String name;
    }
    
    /**
     * Feature from GeoJSON
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class EdsFeature {
        @JsonProperty("geometry")
        public EdsGeometry geometry;
        
        @JsonProperty("properties")
        public EdsProperties properties;
        
        @JsonProperty("type")
        public String type;
    }
    
    /**
     * Geometry from GeoJSON feature
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class EdsGeometry {
        @JsonProperty("coordinates")
        public Object coordinates; // Esnek format için Object kullanıyoruz
        
        @JsonProperty("type")
        public String type;
    }
    
    /**
     * Properties from GeoJSON feature
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class EdsProperties {
        @JsonProperty("Name")
        public String Name;
        
        @JsonProperty("altitudeMode")
        public String altitudeMode;
        
        @JsonProperty("begin")
        public String begin;
        
        @JsonProperty("description")
        public String description;
        
        @JsonProperty("drawOrder")
        public String drawOrder;
        
        @JsonProperty("end")
        public String end;
        
        @JsonProperty("extrude")
        public Integer extrude;
        
        @JsonProperty("icon")
        public String icon;
        
        // EDS Properties sadece Name kullanıyor, diğer field'lar artık gerekli değil
        
        @JsonProperty("id")
        public String id;
        
        @JsonProperty("tessellate")
        public Integer tessellate;
        
        @JsonProperty("timestamp")
        public String timestamp;
        
        @JsonProperty("visibility")
        public Integer visibility;
    }
    
    /**
     * EDS road data - sadece geometry ve name bilgisi
     */
    public static class EdsRoadData {
        private final String roadId;
        private final LineString geometry;
        private final long timestamp;
        
        public EdsRoadData(String roadId, LineString geometry, long timestamp) {
            this.roadId = roadId;
            this.geometry = geometry;
            this.timestamp = timestamp;
        }
        
        public String getRoadId() { return roadId; }
        public LineString getGeometry() { return geometry; }
        public long getTimestamp() { return timestamp; }
    }
    
    /**
     * Legacy TrafficData class - backward compatibility için
     * @deprecated Use EdsRoadData instead
     */
    @Deprecated
    public static class TrafficData extends EdsRoadData {
        private final double trafficDensity;
        private final double edsSpeedLimit;
        private final double currentSpeed;
        
        public TrafficData(String roadId, LineString geometry, double trafficDensity, 
                          double edsSpeedLimit, double currentSpeed, long timestamp) {
            super(roadId, geometry, timestamp);
            this.trafficDensity = trafficDensity;
            this.edsSpeedLimit = edsSpeedLimit;
            this.currentSpeed = currentSpeed;
        }
        
        public double getTrafficDensity() { return trafficDensity; }
        public double getEdsSpeedLimit() { return edsSpeedLimit; }
        public double getCurrentSpeed() { return currentSpeed; }
        
        /**
         * Calculate speed factor based on traffic conditions
         * @return multiplier for base speed (0.1 to 1.0)
         */
        public double getSpeedFactor() {
            return Math.max(0.1, 1.0 - (trafficDensity * 0.8));
        }
        
        /**
         * Calculate priority factor for route preference
         * @return multiplier for route priority (0.1 to 1.0)
         */
        public double getPriorityFactor() {
            return Math.max(0.1, 1.0 - (trafficDensity * 0.6));
        }
        
        /**
         * Get effective speed limit considering EDS and current conditions
         * @return speed limit in km/h
         */
        public double getEffectiveSpeedLimit() {
            if (edsSpeedLimit > 0) {
                return edsSpeedLimit;
            }
            if (currentSpeed > 0) {
                return currentSpeed;
            }
            return 50; // Default speed limit
        }
    }
} 