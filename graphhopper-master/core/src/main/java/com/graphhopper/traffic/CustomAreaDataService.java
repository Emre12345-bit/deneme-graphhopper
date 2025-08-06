package com.graphhopper.traffic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service for fetching custom area data (road works, excavation works, etc.) from external API
 * Updates data every 6 hours automatically
 */
@Singleton
public class CustomAreaDataService {
    private static final Logger logger = LoggerFactory.getLogger(CustomAreaDataService.class);
    
    private final Map<String, Map<String, Object>> customAreaCache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // Configuration
    private String customAreaApiUrl = "http://20.199.11.220:9000/custom-areas"; // Will be configured
    private volatile LocalDateTime lastUpdate = LocalDateTime.now().minusHours(7); // Force initial load
    private final Duration cacheExpiry = Duration.ofHours(6);
    
    public CustomAreaDataService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        
        // Schedule automatic updates every 6 hours
        scheduler.scheduleAtFixedRate(this::refreshCustomAreaData, 0, 6, TimeUnit.HOURS);
        
        logger.info("CustomAreaDataService initialized - will update every 6 hours");
    }
    
    /**
     * Configure the API URL for custom area data
     */
    public void setCustomAreaApiUrl(String apiUrl) {
        this.customAreaApiUrl = apiUrl;
        logger.info("Custom area API URL configured: {}", apiUrl);
    }
    
    /**
     * Get current custom area data from cache
     * @return Map of custom areas by ID
     */
    public Map<String, Map<String, Object>> getCurrentCustomAreaData() {
        if (isDataExpired()) {
            logger.warn("Custom area data expired, triggering refresh");
            refreshCustomAreaData();
        }
        return new ConcurrentHashMap<>(customAreaCache);
    }
    
    /**
     * Check if custom area data is available and fresh
     */
    public boolean hasValidData() {
        return !customAreaCache.isEmpty() && !isDataExpired();
    }
    
    /**
     * Get count of active custom areas
     */
    public int getActiveCustomAreaCount() {
        return customAreaCache.size();
    }
    
    /**
     * Refresh custom area data from external API
     */
    private void refreshCustomAreaData() {
        if (customAreaApiUrl == null || customAreaApiUrl.isEmpty()) {
            logger.debug("Custom area API URL not configured, skipping update");
            return;
        }
        
        try {
            logger.info("Fetching custom area data from: {}", customAreaApiUrl);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(customAreaApiUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            
            logger.debug("Sending HTTP request to custom areas endpoint...");
            HttpResponse<String> response = httpClient.send(request, 
                    HttpResponse.BodyHandlers.ofString());
            
            logger.info("Custom areas API responded with status code: {}", response.statusCode());
            
            if (response.statusCode() == 200) {
                String responseBody = response.body();
                logger.debug("Custom areas response body length: {} characters", responseBody.length());
                
                processCustomAreaResponse(responseBody);
                lastUpdate = LocalDateTime.now();
                logger.info("✅ Custom area data updated successfully - {} areas loaded", 
                           customAreaCache.size());
                
                // Log active custom areas for debugging
                if (logger.isDebugEnabled()) {
                    for (Map.Entry<String, Map<String, Object>> entry : customAreaCache.entrySet()) {
                        Map<String, Object> area = entry.getValue();
                        logger.debug("Active custom area: ID={}, title={}, location={}, radius={}m", 
                                   entry.getKey(), area.get("title"), area.get("location"), area.get("half_diameter"));
                    }
                }
            } else {
                logger.error("Failed to fetch custom area data. HTTP status: {}, Response body: {}", 
                           response.statusCode(), response.body());
            }
            
        } catch (IOException e) {
            logger.error("IO Error fetching custom area data from {}: {}", customAreaApiUrl, e.getMessage(), e);
        } catch (InterruptedException e) {
            logger.error("Request interrupted while fetching custom area data: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Unexpected error fetching custom area data: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Process the JSON response and update cache
     */
    private void processCustomAreaResponse(String jsonResponse) {
        try {
            // Parse JSON array
            List<Map<String, Object>> customAreas = objectMapper.readValue(
                    jsonResponse, new TypeReference<List<Map<String, Object>>>() {});
            
            logger.info("Successfully parsed {} custom areas from JSON response", customAreas.size());
            
            Map<String, Map<String, Object>> newCache = new ConcurrentHashMap<>();
            int validAreas = 0;
            int skippedAreas = 0;
            
            for (Map<String, Object> area : customAreas) {
                String id = String.valueOf(area.get("id"));
                
                // Validate required fields
                if (hasRequiredFields(area)) {
                    newCache.put(id, area);
                    validAreas++;
                    
                    if (logger.isDebugEnabled()) {
                        logger.debug("Loaded custom area: ID={}, title={}, location={}, radius={}m", 
                                   id, area.get("title"), area.get("location"), area.get("half_diameter"));
                    }
                } else {
                    logger.warn("Skipping custom area with missing required fields: ID={}, data={}", 
                               id, area);
                    skippedAreas++;
                }
            }
            
            // Update cache atomically
            customAreaCache.clear();
            customAreaCache.putAll(newCache);
            
            logger.info("Custom area cache updated: {} valid areas, {} skipped areas", validAreas, skippedAreas);
            
        } catch (IOException e) {
            logger.error("Failed to parse custom area JSON response: {}", e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error processing custom area response: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Validate that custom area has required fields
     */
    private boolean hasRequiredFields(Map<String, Object> area) {
        return area.containsKey("id") && 
               area.containsKey("location") && 
               area.containsKey("half_diameter") &&
               area.get("location") != null &&
               area.get("half_diameter") != null;
    }
    
    /**
     * Check if cached data is expired
     */
    private boolean isDataExpired() {
        return Duration.between(lastUpdate, LocalDateTime.now()).compareTo(cacheExpiry) > 0;
    }
    
    /**
     * Shutdown the service
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("CustomAreaDataService shutdown complete");
    }
}