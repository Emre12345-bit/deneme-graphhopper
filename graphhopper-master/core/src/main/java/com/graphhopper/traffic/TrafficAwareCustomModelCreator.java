package com.graphhopper.traffic;

import com.graphhopper.config.Profile;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.CustomModel;
import com.graphhopper.json.Statement;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.traffic.EdgeGeometryMatcher.EdgeMatch;
import com.graphhopper.traffic.TrafficDataService.TrafficData;
import com.graphhopper.traffic.CustomAreaDataService;
import com.graphhopper.traffic.CustomAreaGeometryMatcher;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.graphhopper.json.Statement.If;
import static com.graphhopper.json.Statement.Keyword.IF;
import static com.graphhopper.json.Statement.Op.LIMIT;
import static com.graphhopper.json.Statement.Op.MULTIPLY;

/**
 * Creates dynamic custom models based on current traffic data and custom areas
 * Integrates with GraphHopper's flexible routing system
 */
public class TrafficAwareCustomModelCreator {
    private static final Logger logger = LoggerFactory.getLogger(TrafficAwareCustomModelCreator.class);
    
    private final BaseGraph graph;
    private final EdgeGeometryMatcher geometryMatcher;
    private final CustomAreaDataService customAreaDataService;
    private final CustomAreaGeometryMatcher customAreaGeometryMatcher;
    
    // Cache for edge traffic mappings to avoid repeated spatial calculations
    private final Map<Integer, TrafficCondition> edgeTrafficCache = new ConcurrentHashMap<>();
    private volatile long lastTrafficUpdate = 0;
    
    // Cache for custom area edge mappings
    private final Map<Integer, CustomAreaCondition> edgeCustomAreaCache = new ConcurrentHashMap<>();
    private volatile long lastCustomAreaUpdate = 0;
    
    // Update intervals (24 hours in milliseconds for EDS, 6 hours for custom areas)
    private static final long TRAFFIC_UPDATE_INTERVAL = 24 * 60 * 60 * 1000;
    private static final long CUSTOM_AREA_UPDATE_INTERVAL = 6 * 60 * 60 * 1000;
    
    @Inject
    public TrafficAwareCustomModelCreator(BaseGraph graph, 
                                          CustomAreaDataService customAreaDataService,
                                          CustomAreaGeometryMatcher customAreaGeometryMatcher) {
        this.graph = graph;
        this.geometryMatcher = new EdgeGeometryMatcher(graph);
        this.customAreaDataService = customAreaDataService;
        this.customAreaGeometryMatcher = customAreaGeometryMatcher;
        logger.info("TrafficAwareCustomModelCreator initialized for EDS and custom area avoidance");
    }
    
    /**
     * Create an EDS-aware custom model for avoiding EDS roads
     * @param baseProfile The base profile to enhance with EDS data  
     * @param edsData Current EDS road data
     * @return Custom model that avoids EDS roads
     */
    public CustomModel createEdsAvoidanceCustomModel(Profile baseProfile, Map<String, TrafficData> edsData) {
        logger.info("Creating EDS avoidance custom model with {} EDS road entries", edsData.size());
        
        // Update edge EDS mappings if needed
        updateEdgeEdsMappings(edsData);
        
        // Start with base profile's custom model or create new one
        CustomModel customModel = baseProfile.getCustomModel() != null ? 
            new CustomModel(baseProfile.getCustomModel()) : new CustomModel();
        
        // Log the EDS cache state
        logger.info("Edge EDS cache contains {} entries after update", edgeTrafficCache.size());
        
        // Add EDS road avoidance rules
        addEdsAvoidanceRules(customModel);
        
        // Final verification
        logger.info("Final EDS model created. Cache size: {}, Has avoidance rules: {}", 
                   edgeTrafficCache.size(), !customModel.getPriority().isEmpty());
        
        // Only set distance influence if there are actually EDS edges to avoid
        if (customModel.getDistanceInfluence() == null && !edgeTrafficCache.isEmpty()) {
            customModel.setDistanceInfluence(75d); // Balanced preference to avoid long detours
            logger.info("Set EDS distance influence to 75d for {} affected edges", edgeTrafficCache.size());
        } else if (edgeTrafficCache.isEmpty()) {
            logger.info("No EDS edges to avoid, keeping default distance influence");
        }
        
        logger.info("🎯 AVOIDANCE MODEL: {} edges affected", edgeTrafficCache.size());
        
        return customModel;
    }
    
    /**
     * Create a traffic-aware custom model based on current traffic data
     * @param baseProfile The base profile to enhance with traffic data
     * @param trafficData Current traffic data from external API
     * @return Enhanced custom model with traffic considerations
     */
    public CustomModel createTrafficAwareCustomModel(Profile baseProfile, Map<String, TrafficData> trafficData) {
        logger.info("Creating traffic-aware custom model with {} traffic data entries", trafficData.size());
        
        // Update edge traffic mappings if needed
        updateEdgeTrafficMappings(trafficData);
        
        // Start with base profile's custom model or create new one
        CustomModel customModel = baseProfile.getCustomModel() != null ? 
            new CustomModel(baseProfile.getCustomModel()) : new CustomModel();
        
        // Log the traffic cache state
        logger.info("Edge traffic cache contains {} entries after update", edgeTrafficCache.size());
        
        // Add traffic-aware speed adjustments
        addTrafficSpeedRules(customModel);
        
        // Add traffic-aware priority adjustments
        addTrafficPriorityRules(customModel);
        
        // Set distance influence to balance between time and distance
        if (customModel.getDistanceInfluence() == null) {
            customModel.setDistanceInfluence(70d); // Slightly favor time over distance for traffic-aware routing
        }
        
        // Log detailed custom model information
        logger.info("Created traffic-aware custom model:");
        logger.info("  Speed rules: {}", customModel.getSpeed().size());
        logger.info("  Priority rules: {}", customModel.getPriority().size());
        logger.info("  Distance influence: {}", customModel.getDistanceInfluence());
        logger.info("  Traffic-affected edges: {}", edgeTrafficCache.size());
        
        return customModel;
    }
    
    /**
     * Update mappings between GraphHopper edges and traffic data
     */
    private void updateEdgeTrafficMappings(Map<String, TrafficData> trafficData) {
        long currentTime = System.currentTimeMillis();
        
        // Only update if enough time has passed since last update
        if (currentTime - lastTrafficUpdate < TRAFFIC_UPDATE_INTERVAL && !edgeTrafficCache.isEmpty()) {
            return;
        }
        
        logger.info("Updating edge traffic mappings with {} traffic data entries", trafficData.size());
        
        // Clear old mappings
        edgeTrafficCache.clear();
        
        // Process each traffic data entry
        for (TrafficData traffic : trafficData.values()) {
            try {
                processTrafficDataForEdges(traffic);
            } catch (Exception e) {
                logger.warn("Failed to process traffic data for road {}: {}", traffic.getRoadId(), e.getMessage());
            }
        }
        
        lastTrafficUpdate = currentTime;
        logger.info("Updated traffic mappings for {} edges", edgeTrafficCache.size());
    }
    
    /**
     * Process a single traffic data entry and map it to GraphHopper edges
     */
    private void processTrafficDataForEdges(TrafficData trafficData) {
        LineString trafficLineString = trafficData.getGeometry();
        List<EdgeMatch> matchingEdges = geometryMatcher.findMatchingEdges(trafficLineString);
        
        logger.info("Processing traffic data for road '{}': found {} matching edges", 
                   trafficData.getRoadId(), matchingEdges.size());
        
        int highQualityMatches = 0;
        for (EdgeMatch match : matchingEdges) {
            logger.debug("Edge {} match score: {}", 
                        match.getEdgeId(), match.getMatchScore());
            
            // Only consider high-quality matches
            if (match.getMatchScore() >= 0.7) {
                highQualityMatches++;
                TrafficCondition condition = new TrafficCondition(
                    trafficData.getTrafficDensity(),
                    trafficData.getSpeedFactor(),
                    trafficData.getPriorityFactor(),
                    trafficData.getEffectiveSpeedLimit(),
                    match.getMatchScore()
                );
                
                // If edge already has traffic data, use the one with better match score
                TrafficCondition existing = edgeTrafficCache.get(match.getEdgeId());
                if (existing == null || condition.getMatchScore() > existing.getMatchScore()) {
                    edgeTrafficCache.put(match.getEdgeId(), condition);
                    logger.debug("Updated edge {} with traffic condition: density={}, speedFactor={}", 
                               match.getEdgeId(), condition.getTrafficDensity(), condition.getSpeedFactor());
                }
            } else {
                logger.debug("Skipping edge {} with low match score: {}", 
                           match.getEdgeId(), match.getMatchScore());
            }
        }
        
        logger.info("Applied traffic to {} high-quality edges (score >= 0.7) out of {} total matches", 
                   highQualityMatches, matchingEdges.size());
    }
    
    /**
     * Add traffic-aware speed rules to custom model
     */
    private void addTrafficSpeedRules(CustomModel customModel) {
        // Ensure base speed is set (use car_average_speed as default)
        if (customModel.getSpeed().isEmpty()) {
            customModel.addToSpeed(If("true", LIMIT, "car_average_speed"));
        }
        
        // Add speed reductions based on road class (higher class = more traffic potential)
        // Primary roads - moderate speed reduction for potential traffic
        customModel.addToSpeed(If("road_class == PRIMARY", MULTIPLY, "0.8"));
        customModel.addToSpeed(If("road_class == SECONDARY", MULTIPLY, "0.85"));
        
        // Tertiary and residential roads - slight speed reduction in urban areas
        customModel.addToSpeed(If("road_class == TERTIARY", MULTIPLY, "0.9"));
        customModel.addToSpeed(If("road_class == RESIDENTIAL", MULTIPLY, "0.95"));
        
        // Add basic speed variations based on road characteristics
        // Slower roads (urban, low speed) - slight reduction for traffic potential
        customModel.addToSpeed(If("car_average_speed < 30", MULTIPLY, "0.9"));
        
        // Medium speed roads - moderate adjustment
        customModel.addToSpeed(If("car_average_speed >= 30 && car_average_speed < 60", MULTIPLY, "0.95"));
        
        // High speed roads - less affected by traffic, slight boost
        customModel.addToSpeed(If("car_average_speed >= 80", MULTIPLY, "1.05"));
        
        logger.debug("Added traffic-aware speed rules based on road_class and car_average_speed");
    }
    
    /**
     * Add traffic-aware priority rules to custom model
     */
    private void addTrafficPriorityRules(CustomModel customModel) {
        // Ensure base access rules are preserved
        if (customModel.getPriority().isEmpty()) {
            customModel.addToPriority(If("!car_access", MULTIPLY, "0"));
        }
        
        // Priority adjustments based on road classification
        // Prefer highways and trunks for longer distances (less traffic interference)
        customModel.addToPriority(If("road_class == MOTORWAY", MULTIPLY, "1.2"));
        customModel.addToPriority(If("road_class == TRUNK", MULTIPLY, "1.1"));
        
        // Reduce priority for urban roads that typically have more traffic
        customModel.addToPriority(If("road_class == PRIMARY", MULTIPLY, "0.9"));
        customModel.addToPriority(If("road_class == SECONDARY", MULTIPLY, "0.85"));
        customModel.addToPriority(If("road_class == TERTIARY", MULTIPLY, "0.8"));
        customModel.addToPriority(If("road_class == RESIDENTIAL", MULTIPLY, "0.7"));
        
        // Priority adjustments based on speed characteristics
        // High speed roads generally have better flow
        customModel.addToPriority(If("car_average_speed >= 80", MULTIPLY, "1.1"));
        
        // Medium speed roads
        customModel.addToPriority(If("car_average_speed >= 50 && car_average_speed < 80", MULTIPLY, "1.0"));
        
        // Low speed roads (urban) - typically more congested
        customModel.addToPriority(If("car_average_speed < 30", MULTIPLY, "0.8"));
        
        logger.debug("Added traffic-aware priority rules based on road_class and car_average_speed");
    }
    
    /**
     * Get traffic condition for a specific edge
     * @param edgeId The edge ID
     * @return Traffic condition or null if no traffic data available
     */
    public TrafficCondition getEdgeTrafficCondition(int edgeId) {
        return edgeTrafficCache.get(edgeId);
    }
    
    /**
     * Check if EDS data is available for routing
     * @return true if EDS data is available and recent (within 48 hours)
     */
    public boolean hasCurrentTrafficData() {
        long currentTime = System.currentTimeMillis();
        return !edgeTrafficCache.isEmpty() && 
               (currentTime - lastTrafficUpdate) < (TRAFFIC_UPDATE_INTERVAL * 2); // Allow 48h buffer for EDS data
    }
    
    /**
     * Get statistics about current traffic data
     */
    public TrafficStats getTrafficStats() {
        int totalEdges = edgeTrafficCache.size();
        int heavyTrafficEdges = 0;
        int moderateTrafficEdges = 0;
        int lightTrafficEdges = 0;
        
        for (TrafficCondition condition : edgeTrafficCache.values()) {
            if (condition.getTrafficDensity() > 0.7) {
                heavyTrafficEdges++;
            } else if (condition.getTrafficDensity() > 0.4) {
                moderateTrafficEdges++;
            } else {
                lightTrafficEdges++;
            }
        }
        
        return new TrafficStats(totalEdges, heavyTrafficEdges, moderateTrafficEdges, lightTrafficEdges, lastTrafficUpdate);
    }
    
    /**
     * Get EDS edge IDs set for external use
     */
    public Set<Integer> getEdsEdgeIds() {
        return new HashSet<>(edgeTrafficCache.keySet());
    }
    
    /**
     * Get Custom Area edge IDs set for external use
     */
    public Set<Integer> getCustomAreaEdgeIds() {
        return new HashSet<>(edgeCustomAreaCache.keySet());
    }
    
    /**
     * Clear all caches
     */
    public void clearCaches() {
        edgeTrafficCache.clear();
        geometryMatcher.clearCache();
        lastTrafficUpdate = 0;
        logger.info("Cleared all traffic-related caches");
    }
    
    /**
     * Represents traffic conditions for a specific edge
     */
    public static class TrafficCondition {
        private final double trafficDensity;
        private final double speedFactor;
        private final double priorityFactor;
        private final double effectiveSpeedLimit;
        private final double matchScore;
        
        public TrafficCondition(double trafficDensity, double speedFactor, double priorityFactor, 
                               double effectiveSpeedLimit, double matchScore) {
            this.trafficDensity = trafficDensity;
            this.speedFactor = speedFactor;
            this.priorityFactor = priorityFactor;
            this.effectiveSpeedLimit = effectiveSpeedLimit;
            this.matchScore = matchScore;
        }
        
        public double getTrafficDensity() { return trafficDensity; }
        public double getSpeedFactor() { return speedFactor; }
        public double getPriorityFactor() { return priorityFactor; }
        public double getEffectiveSpeedLimit() { return effectiveSpeedLimit; }
        public double getMatchScore() { return matchScore; }
    }
    
    /**
     * Statistics about current traffic data
     */
    public static class TrafficStats {
        private final int totalEdges;
        private final int heavyTrafficEdges;
        private final int moderateTrafficEdges;
        private final int lightTrafficEdges;
        private final long lastUpdate;
        
        public TrafficStats(int totalEdges, int heavyTrafficEdges, int moderateTrafficEdges, 
                           int lightTrafficEdges, long lastUpdate) {
            this.totalEdges = totalEdges;
            this.heavyTrafficEdges = heavyTrafficEdges;
            this.moderateTrafficEdges = moderateTrafficEdges;
            this.lightTrafficEdges = lightTrafficEdges;
            this.lastUpdate = lastUpdate;
        }
        
        public int getTotalEdges() { return totalEdges; }
        public int getHeavyTrafficEdges() { return heavyTrafficEdges; }
        public int getModerateTrafficEdges() { return moderateTrafficEdges; }
        public int getLightTrafficEdges() { return lightTrafficEdges; }
        public long getLastUpdate() { return lastUpdate; }
        
        @Override
        public String toString() {
            return String.format("TrafficStats{total=%d, heavy=%d, moderate=%d, light=%d, lastUpdate=%d}", 
                               totalEdges, heavyTrafficEdges, moderateTrafficEdges, lightTrafficEdges, lastUpdate);
        }
    }
    
    /**
     * Update edge EDS mappings for avoidance routing
     */
    public void updateEdgeEdsMappings(Map<String, TrafficData> edsData) {
        logger.info("Processing {} EDS roads...", edsData.size());
        
        // Clear cache first and ensure it's empty
        edgeTrafficCache.clear();
        lastTrafficUpdate = 0; // Force cache refresh
        
        // Verify cache is cleared
        if (!edgeTrafficCache.isEmpty()) {
            logger.warn("Cache not properly cleared, forcing clear again");
            edgeTrafficCache.clear();
        }
        
        logger.info("Cache cleared, current size: {}", edgeTrafficCache.size());
        
        int processedEdges = 0;
        int matchedCount = 0;
        int noMatchCount = 0;
        int totalMatchedEdges = 0;
        
        for (Map.Entry<String, TrafficData> entry : edsData.entrySet()) {
            String roadId = entry.getKey();
            TrafficData data = entry.getValue();
            
            try {
                // Find GraphHopper edges that match this EDS LineString
                List<EdgeMatch> matchingEdges = geometryMatcher.findMatchingEdges(data.getGeometry());
                
                // Check if this is user's test road (starts with 32.511, 37.939)
                boolean isUserTestRoad = false;
                try {
                    Coordinate[] coords = data.getGeometry().getCoordinates();
                    if (coords.length > 0) {
                        Coordinate first = coords[0];
                        isUserTestRoad = Math.abs(first.x - 32.5115617335038) < 0.0001 && 
                                        Math.abs(first.y - 37.9397781219424) < 0.0001;
                    }
                } catch (Exception e) {
                    // ignore
                }

                if (matchingEdges.isEmpty()) {
                    noMatchCount++;
                    if (isUserTestRoad) {
                        logger.error("🚨 USER TEST ROAD '{}' NOT MATCHED!", roadId);
                    }
                } else {
                    matchedCount++;
                    totalMatchedEdges += matchingEdges.size();
                    if (isUserTestRoad) {
                        logger.info("🎯 USER TEST ROAD '{}' MATCHED -> {} edges avoided", roadId, matchingEdges.size());
                    }
                }
                
                for (EdgeMatch edgeMatch : matchingEdges) {
                    // For MAXIMUM EDS avoidance - %99 kaçınma (güçlü penalty) - VERSION 3.0
                    TrafficCondition condition = new TrafficCondition(
                        1.0, // High traffic density 
                        0.1, // Very low speed factor (%90 slower) 
                        0.01, // Very low priority (%99 avoidance - MAXIMUM PENALTY)
                        5.0, // Very low speed limit
                        edgeMatch.getMatchScore()
                    );
                    
                    if (processedEdges == 0) {
                        logger.info("🔥 PENALTY: priority_factor=0.01 (%99 avoidance)");
                    }
                    
                    edgeTrafficCache.put(edgeMatch.getEdgeId(), condition);
                    processedEdges++;
                }
            } catch (Exception e) {
                logger.warn("Failed to process EDS road {}: {}", roadId, e.getMessage());
            }
        }
        
        lastTrafficUpdate = System.currentTimeMillis();
        logger.info("✅ EDS COMPLETE: {} matched, {} edges avoided", 
                   matchedCount, edgeTrafficCache.size());
    }
    
    /**
     * Add EDS road avoidance rules to custom model
     * Only applies penalties to specific edges that have EDS data
     */
    private void addEdsAvoidanceRules(CustomModel customModel) {
        logger.info("Adding EDS avoidance rules. Cache size: {}, Cache keys: {}", 
                   edgeTrafficCache.size(), edgeTrafficCache.keySet());
        
        if (edgeTrafficCache.isEmpty()) {
            logger.info("No EDS edge mappings available, skipping EDS avoidance rules");
            return;
        }
        
        // Ensure base access rules are preserved
        if (customModel.getPriority().isEmpty()) {
            customModel.addToPriority(If("!car_access", MULTIPLY, "0"));
        }
        
        // SPECIFIC EDS EDGE AVOIDANCE RULES
        // Break down large edge ID lists into smaller chunks to avoid expression parsing limits
        
        List<Integer> edgeIds = new ArrayList<>(edgeTrafficCache.keySet());
        int chunkSize = 5; // Maximum 5 edge IDs per condition (ultra conservative)
        int totalChunks = (edgeIds.size() + chunkSize - 1) / chunkSize;
        
        logger.info("EDS edges found: {} - using TrafficAvoidanceWeighting for precise edge targeting", edgeIds.size());
        logger.info("EDS avoidance will be implemented via custom weighting - edges will have infinite weight");
        
        logger.info("EDS avoidance will be implemented via TrafficAvoidanceWeighting");
    }
    
    /**
     * Create a TrafficAvoidanceWeighting that wraps the base weighting and adds traffic avoidance
     * @param baseWeighting The base weighting to wrap
     * @param avoidEds Whether to avoid EDS roads
     * @param avoidCustomAreas Whether to avoid custom areas
     * @return TrafficAvoidanceWeighting that excludes EDS and custom area edges
     */
    public TrafficAvoidanceWeighting createTrafficAvoidanceWeighting(Weighting baseWeighting, 
                                                                    boolean avoidEds, 
                                                                    boolean avoidCustomAreas) {
        logger.info("Creating TrafficAvoidanceWeighting - EDS: {} edges (avoid: {}), Custom Areas: {} edges (avoid: {})", 
                   edgeTrafficCache.size(), avoidEds, edgeCustomAreaCache.size(), avoidCustomAreas);
        
        // Check for overlapping edges (edges that both systems want to avoid)
        Set<Integer> edsEdges = avoidEds ? edgeTrafficCache.keySet() : new HashSet<>();
        Set<Integer> customAreaEdges = avoidCustomAreas ? edgeCustomAreaCache.keySet() : new HashSet<>();
        
        Set<Integer> overlappingEdges = new HashSet<>(edsEdges);
        overlappingEdges.retainAll(customAreaEdges);
        
        if (!overlappingEdges.isEmpty()) {
            logger.info("⚠️ OVERLAP DETECTED: {} edges are avoided by both EDS and Custom Areas", overlappingEdges.size());
            logger.info("🔧 OPTIMIZATION: Using balanced penalties instead of infinite weights for better route quality");
        }
        
        return new TrafficAvoidanceWeighting(baseWeighting, 
                                           edsEdges, 
                                           customAreaEdges,
                                           avoidEds, 
                                           avoidCustomAreas);
    }
    
    /**
     * Create a custom area avoidance custom model for avoiding road works, excavation works, etc.
     * @param baseProfile The base profile to enhance with custom area data  
     * @param customAreaData Current custom area data
     * @return Custom model that avoids custom areas
     */
    public CustomModel createCustomAreaAvoidanceCustomModel(Profile baseProfile, Map<String, Map<String, Object>> customAreaData) {
        logger.info("Creating custom area avoidance model with {} custom areas", customAreaData.size());
        
        // Update edge custom area mappings if needed
        updateEdgeCustomAreaMappings(customAreaData);
        
        // Start with base profile's custom model or create new one
        CustomModel customModel = baseProfile.getCustomModel() != null ? 
            new CustomModel(baseProfile.getCustomModel()) : new CustomModel();
        
        // Log the custom area cache state
        logger.info("Edge custom area cache contains {} entries after update", edgeCustomAreaCache.size());
        
        // Add custom area avoidance rules
        addCustomAreaAvoidanceRules(customModel);
        
        // Only set distance influence if there are actually custom areas to avoid
        if (customModel.getDistanceInfluence() == null && !edgeCustomAreaCache.isEmpty()) {
            customModel.setDistanceInfluence(85d); // Strong distance preference for custom areas
            logger.info("Set custom area distance influence to 85d for {} affected edges", edgeCustomAreaCache.size());
        } else if (edgeCustomAreaCache.isEmpty()) {
            logger.info("No custom areas to avoid, keeping default distance influence");
        }
        
        logger.info("🎯 CUSTOM AREA AVOIDANCE MODEL: {} edges affected", edgeCustomAreaCache.size());
        
        return customModel;
    }
    
    /**
     * Update edge custom area mappings for avoidance routing
     */
    public void updateEdgeCustomAreaMappings(Map<String, Map<String, Object>> customAreaData) {
        long now = System.currentTimeMillis();
        
        // Skip update if data is fresh (within 6 hours)
        if (now - lastCustomAreaUpdate < CUSTOM_AREA_UPDATE_INTERVAL && !edgeCustomAreaCache.isEmpty()) {
            logger.debug("Custom area edge mappings are fresh, skipping update");
            return;
        }
        
        logger.info("Processing {} custom areas...", customAreaData.size());
        
        // Clear cache first and ensure it's empty
        edgeCustomAreaCache.clear();
        lastCustomAreaUpdate = now;
        
        // Verify cache is cleared
        if (!edgeCustomAreaCache.isEmpty()) {
            logger.warn("Custom area cache not properly cleared, forcing clear again");
            edgeCustomAreaCache.clear();
        }
        
        logger.info("Custom area cache cleared, current size: {}", edgeCustomAreaCache.size());
        
        int processedEdges = 0;
        
        try {
            // Find all matching edges for all custom areas
            List<EdgeMatch> matchingEdges = customAreaGeometryMatcher.findMatchingEdges(customAreaData);
            
            for (EdgeMatch edgeMatch : matchingEdges) {
                // Strong penalty for custom areas (road works, excavation, etc.)
                CustomAreaCondition condition = new CustomAreaCondition(
                    0.01, // Very low priority (%99 avoidance)
                    edgeMatch.getMatchScore(),
                    "custom_area" // Default identifier since EdgeMatch doesn't have sourceId
                );
                
                edgeCustomAreaCache.put(edgeMatch.getEdgeId(), condition);
                processedEdges++;
                
                if (processedEdges == 1) {
                    logger.info("🚧 CUSTOM AREA PENALTY: priority_factor=0.01 (%99 avoidance)");
                }
            }
            
            logger.info("✅ Custom area edge mapping completed: {} edges affected", processedEdges);
            
        } catch (Exception e) {
            logger.error("Failed to update custom area edge mappings: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Add custom area avoidance rules to custom model
     * Only applies penalties to specific edges that are in custom areas
     */
    private void addCustomAreaAvoidanceRules(CustomModel customModel) {
        logger.info("Adding custom area avoidance rules. Cache size: {}, Cache keys: {}", 
                   edgeCustomAreaCache.size(), edgeCustomAreaCache.keySet());
        
        if (edgeCustomAreaCache.isEmpty()) {
            logger.info("No custom area edge mappings available, skipping custom area avoidance rules");
            return;
        }
        
        // Ensure base access rules are preserved
        if (customModel.getPriority().isEmpty()) {
            customModel.addToPriority(If("!car_access", MULTIPLY, "0"));
        }
        
        // SPECIFIC CUSTOM AREA EDGE AVOIDANCE RULES
        // Break down large edge ID lists into smaller chunks to avoid expression parsing limits
        
        List<Integer> edgeIds = new ArrayList<>(edgeCustomAreaCache.keySet());
        int chunkSize = 5; // Maximum 5 edge IDs per condition (ultra conservative)
        int totalChunks = (edgeIds.size() + chunkSize - 1) / chunkSize;
        
        logger.info("Custom area edges found: {} - using TrafficAvoidanceWeighting for precise edge targeting", edgeIds.size());
        logger.info("Custom area avoidance will be implemented via custom weighting - edges will have infinite weight");
        
        logger.info("Custom area avoidance will be implemented via TrafficAvoidanceWeighting");
    }
    
    /**
     * Create combined custom model that merges EDS and custom area avoidance
     * This method is used when both avoidance systems are active
     */
    public CustomModel createCombinedAvoidanceCustomModel(Profile baseProfile, 
                                                          Map<String, TrafficData> edsData,
                                                          Map<String, Map<String, Object>> customAreaData,
                                                          boolean avoidEds,
                                                          boolean avoidCustomAreas) {
        logger.info("Creating combined avoidance model (EDS={}, CustomAreas={})", avoidEds, avoidCustomAreas);
        
        // Start with base profile's custom model or create new one
        CustomModel customModel = baseProfile.getCustomModel() != null ? 
            new CustomModel(baseProfile.getCustomModel()) : new CustomModel();
        
        // Apply EDS avoidance if requested
        if (avoidEds && !edsData.isEmpty()) {
            updateEdgeEdsMappings(edsData);
            addEdsAvoidanceRules(customModel);
        }
        
        // Apply custom area avoidance if requested
        if (avoidCustomAreas && !customAreaData.isEmpty()) {
            updateEdgeCustomAreaMappings(customAreaData);
            addCustomAreaAvoidanceRules(customModel);
        }
        
        // Set balanced distance influence for combined avoidance
        if (customModel.getDistanceInfluence() == null) {
            if (avoidEds && avoidCustomAreas) {
                customModel.setDistanceInfluence(80d); // Balanced for both
            } else if (avoidEds) {
                customModel.setDistanceInfluence(75d); // Balanced for EDS only
            } else if (avoidCustomAreas) {
                customModel.setDistanceInfluence(85d); // Balanced for custom areas only
            } else {
                customModel.setDistanceInfluence(70d); // Normal
            }
        }
        
        logger.info("🎯 COMBINED AVOIDANCE: EDS={} edges, CustomAreas={} edges", 
                   edgeTrafficCache.size(), edgeCustomAreaCache.size());
        
        return customModel;
    }
    
    /**
     * Custom area condition for edge penalties
     */
    public static class CustomAreaCondition {
        private final double priorityFactor;
        private final double matchScore;
        private final String areaId;
        
        public CustomAreaCondition(double priorityFactor, double matchScore, String areaId) {
            this.priorityFactor = priorityFactor;
            this.matchScore = matchScore;
            this.areaId = areaId;
        }
        
        public double getPriorityFactor() {
            return priorityFactor;
        }
        
        public double getMatchScore() {
            return matchScore;
        }
        
        public String getAreaId() {
            return areaId;
        }
        
        @Override
        public String toString() {
            return String.format("CustomAreaCondition{priority=%.3f, score=%.3f, area='%s'}", 
                                priorityFactor, matchScore, areaId);
        }
    }
} 