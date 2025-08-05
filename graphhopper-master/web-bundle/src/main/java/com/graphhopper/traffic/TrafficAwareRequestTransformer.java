package com.graphhopper.traffic;

import com.graphhopper.GHRequest;
import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.traffic.TrafficAvoidanceWeighting;
import com.graphhopper.util.CustomModel;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.traffic.TrafficDataService.TrafficData;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;

/**
 * Transforms routing requests to inject traffic-aware custom models
 * Works transparently with existing GET/route endpoint without breaking API
 */
@Singleton
public class TrafficAwareRequestTransformer {
    private static final Logger logger = LoggerFactory.getLogger(TrafficAwareRequestTransformer.class);
    
    private final TrafficDataService trafficDataService;
    private final CustomAreaDataService customAreaDataService;
    private final TrafficAwareCustomModelCreator customModelCreator;
    private final GraphHopper graphHopper;
    
    // Configuration for traffic-aware routing
    private final boolean trafficAwareRoutingEnabled;
    private final boolean fallbackToNormalRouting;
    
    @Inject
    public TrafficAwareRequestTransformer(GraphHopper graphHopper, 
                                        TrafficDataService trafficDataService,
                                        CustomAreaDataService customAreaDataService,
                                        TrafficAwareCustomModelCreator customModelCreator) {
        this.graphHopper = graphHopper;
        this.trafficDataService = trafficDataService;
        this.customAreaDataService = customAreaDataService;
        this.customModelCreator = customModelCreator;
        
        // Read configuration (can be moved to config file)
        this.trafficAwareRoutingEnabled = true; // Enable traffic-aware routing by default
        this.fallbackToNormalRouting = true; // Fallback to normal routing if traffic data unavailable
        
        logger.info("Traffic-aware request transformer initialized. Enabled: {}, Fallback: {}", 
                   trafficAwareRoutingEnabled, fallbackToNormalRouting);
        logger.info("Custom area avoidance system enabled");  
    }
    
    /**
     * Transform the routing request to include traffic-aware custom model
     * This method is called for both GET and POST requests
     * 
     * @param request Original GHRequest
     * @return Transformed GHRequest with traffic-aware routing
     */
    public GHRequest transformRequest(GHRequest request) {
        // Skip transformation if traffic-aware routing is disabled
        if (!trafficAwareRoutingEnabled) {
            logger.debug("Traffic-aware routing is disabled, returning original request");
            return request;
        }
        
        // Check if user explicitly disabled traffic-aware routing
        if (request.getHints().getBool("traffic_aware", true) == false) {
            logger.info("Traffic-aware routing disabled by user parameter, returning original request");
            return request;
        }
        
        // Check avoidance parameters
        boolean avoidEdsRoads = request.getHints().getBool("avoid_eds_roads", false);
        boolean avoidCustomAreas = request.getHints().getBool("avoid_custom_areas", false);
        logger.info("Starting traffic-aware request transformation for profile: {} (avoid_eds_roads={}, avoid_custom_areas={})", 
                   request.getProfile(), avoidEdsRoads, avoidCustomAreas);
        
        try {
            // Get current EDS road data
            Map<String, TrafficData> trafficData = trafficDataService.getCurrentTrafficData();
            logger.info("Retrieved {} EDS road entries from service", trafficData.size());
            
            // Get current custom area data
            Map<String, Map<String, Object>> customAreaData = customAreaDataService.getCurrentCustomAreaData();
            logger.info("Retrieved {} custom area entries from service", customAreaData.size());
            
            // Always enable alternative routes for better user experience
            // Apply avoidance models based on user preferences
            
            // Get the profile for this request
            String profileName = request.getProfile();
            if (profileName == null || profileName.isEmpty()) {
                logger.debug("No profile specified in request, skipping traffic transformation");
                return request;
            }
            
            Profile profile = graphHopper.getProfile(profileName);
            if (profile == null) {
                logger.warn("Profile {} not found, skipping traffic transformation", profileName);
                return request;
            }
            
            // Update edge mappings for both systems if avoidance is requested
            if (avoidEdsRoads && !trafficData.isEmpty()) {
                logger.info("Updating EDS edge mappings with {} traffic data entries", trafficData.size());
                customModelCreator.updateEdgeEdsMappings(trafficData);
            }
            
            if (avoidCustomAreas && !customAreaData.isEmpty()) {
                logger.info("Updating custom area edge mappings with {} custom area entries", customAreaData.size());
                customModelCreator.updateEdgeCustomAreaMappings(customAreaData);
            }
            
            // Add avoidance parameters to hints for TrafficAwareWeightingFactory
            if (avoidEdsRoads || avoidCustomAreas) {
                logger.info("Adding avoidance parameters to request hints (EDS={}, CustomAreas={})", 
                           avoidEdsRoads, avoidCustomAreas);
                request.putHint("avoid_eds_roads", avoidEdsRoads);
                request.putHint("avoid_custom_areas", avoidCustomAreas);
            }
            
            // Clone the request and set the custom model (EDS or normal)
            GHRequest transformedRequest = new GHRequest(request.getPoints());
            transformedRequest.setProfile(request.getProfile());
            transformedRequest.setAlgorithm(request.getAlgorithm());
            transformedRequest.setLocale(request.getLocale());
            transformedRequest.setHeadings(request.getHeadings());
            transformedRequest.setPointHints(request.getPointHints());
            transformedRequest.setCurbsides(request.getCurbsides());
            transformedRequest.setSnapPreventions(request.getSnapPreventions());
            transformedRequest.setPathDetails(request.getPathDetails());
            
            // Copy all hints
            request.getHints().toMap().forEach((key, value) -> {
                transformedRequest.putHint(key, value);
            });
            
            // Set the custom model (use original request's custom model)
            transformedRequest.setCustomModel(request.getCustomModel());
            
            // Always configure for alternative routes (with or without EDS avoidance)
            transformedRequest.setAlgorithm("alternative_route");
            
            // Ensure flexible routing is enabled (disable CH and LM)
            PMap hints = transformedRequest.getHints();
            hints.putObject(Parameters.CH.DISABLE, true);
            hints.putObject(Parameters.Landmark.DISABLE, true);
            
            // Configure alternative routes with optimized parameters based on avoidance settings
            hints.putObject("alternative_route.max_paths", 3);
            
            if (avoidEdsRoads && avoidCustomAreas) {
                // Both avoidance systems active - use balanced parameters for better alternative routes
                hints.putObject("alternative_route.max_weight_factor", 1.5); // Routes can be 50% longer
                hints.putObject("alternative_route.max_share_factor", 0.7);  // Routes can share 70% of path
                hints.putObject("alternative_route.max_exploration_factor", 1.3); // More exploration
                logger.info("🔧 BALANCED-DUAL: Balanced parameters for dual avoidance");
            } else if (avoidCustomAreas) {
                // Only custom areas active - use more flexible parameters for large areas
                hints.putObject("alternative_route.max_weight_factor", 2.0); // Routes can be up to 100% longer
                hints.putObject("alternative_route.max_share_factor", 0.5);  // Routes can share only 50% of path
                hints.putObject("alternative_route.max_exploration_factor", 1.5); // More exploration
                logger.info("🔧 CUSTOM-AREA-FLEXIBLE: More flexible parameters for large custom areas");
            } else if (avoidEdsRoads) {
                // Only EDS active - use balanced parameters
                hints.putObject("alternative_route.max_weight_factor", 1.3); // Routes can be 30% longer
                hints.putObject("alternative_route.max_share_factor", 0.7);  // Routes can share 70% of path
                hints.putObject("alternative_route.max_exploration_factor", 1.2); // Balanced exploration
                logger.info("🔧 BALANCED: Standard alternative route parameters for EDS avoidance");
            } else {
                // No avoidance - use default parameters
                hints.putObject("alternative_route.max_weight_factor", 1.4); // Routes can be 40% longer
                hints.putObject("alternative_route.max_share_factor", 0.6);  // Routes can share 60% of path
                hints.putObject("alternative_route.max_exploration_factor", 1.3); // More exploration for variety
                logger.info("🔧 DEFAULT: Standard alternative route parameters for no avoidance");
            }
            
            // Add traffic-aware routing indicator for logging/debugging
            hints.putObject("traffic_aware", true);
            hints.putObject("traffic_edges_count", customModelCreator.getTrafficStats().getTotalEdges());
            
            // Log avoidance status with optimization info
            String statusMessage = "✅ ALTERNATIVE ROUTES: 3 routes returned";
            if (avoidEdsRoads && avoidCustomAreas) {
                statusMessage += " (EDS + Custom Areas avoidance - BALANCED-DUAL)";
            } else if (avoidCustomAreas) {
                statusMessage += " (Custom Areas avoidance only - CUSTOM-AREA-FLEXIBLE)";
            } else if (avoidEdsRoads) {
                statusMessage += " (EDS avoidance only - BALANCED)";
            } else {
                statusMessage += " (no avoidance - DEFAULT)";
            }
            logger.info(statusMessage);
            
            return transformedRequest;
            
        } catch (Exception e) {
            logger.error("Failed to transform request with traffic data: {}", e.getMessage(), e);
            
            // Return original request on any error if fallback is enabled
            if (fallbackToNormalRouting) {
                logger.info("Fallback enabled, returning original request");
                return request;
            } else {
                throw new RuntimeException("Traffic-aware routing failed and fallback is disabled", e);
            }
        }
    }
    
    /**
     * Merge user-provided custom model with traffic-aware custom model
     * Traffic-aware rules are added first, then user rules are applied
     */
    private CustomModel mergeCustomModels(CustomModel trafficModel, CustomModel userModel) {
        try {
            // Start with traffic-aware model as base
            CustomModel mergedModel = new CustomModel(trafficModel);
            
            // Add user's speed rules (applied after traffic rules)
            if (userModel.getSpeed() != null) {
                userModel.getSpeed().forEach(mergedModel::addToSpeed);
            }
            
            // Add user's priority rules (applied after traffic rules)
            if (userModel.getPriority() != null) {
                userModel.getPriority().forEach(mergedModel::addToPriority);
            }
            
            // User's distance influence overrides traffic-aware setting
            if (userModel.getDistanceInfluence() != null) {
                mergedModel.setDistanceInfluence(userModel.getDistanceInfluence());
            }
            
            // User's heading penalty overrides traffic-aware setting
            if (userModel.getHeadingPenalty() != null) {
                mergedModel.setHeadingPenalty(userModel.getHeadingPenalty());
            }
            
            // Add user's areas
            if (userModel.getAreas() != null) {
                mergedModel.setAreas(userModel.getAreas());
            }
            
            logger.debug("Merged user custom model with traffic-aware model");
            return mergedModel;
            
        } catch (Exception e) {
            logger.warn("Failed to merge custom models, using traffic-aware model only: {}", e.getMessage());
            return trafficModel;
        }
    }
    
    /**
     * Check if a request should use traffic-aware routing
     * Can be extended to support per-request configuration
     */
    public boolean shouldUseTrafficAwareRouting(GHRequest request) {
        if (!trafficAwareRoutingEnabled) {
            return false;
        }
        
        // Check if user explicitly disabled traffic-aware routing
        if (request.getHints().getBool("traffic_aware", true) == false) {
            return false;
        }
        
        // Check if we have recent traffic data
        if (!customModelCreator.hasCurrentTrafficData() && !fallbackToNormalRouting) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Get traffic statistics for monitoring
     */
    public TrafficAwareCustomModelCreator.TrafficStats getTrafficStats() {
        return customModelCreator.getTrafficStats();
    }
    
    /**
     * Check if traffic data service is running
     */
    public boolean isTrafficServiceRunning() {
        try {
            // Check if EDS data is available
            Map<String, TrafficData> edsData = trafficDataService.getCurrentTrafficData();
            
            // Service is running if:
            // 1. EDS data is available, OR 
            // 2. Edge mappings have been created
            boolean hasEdsData = !edsData.isEmpty();
            boolean hasEdgeMappings = customModelCreator.hasCurrentTrafficData();
            
            logger.debug("EDS service status - EDS data: {}, Edge mappings: {}", hasEdsData, hasEdgeMappings);
            
            return hasEdsData || hasEdgeMappings;
        } catch (Exception e) {
            logger.warn("Error checking traffic service status: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Enable or disable traffic-aware routing at runtime
     * Note: This is a simple implementation. In production, you might want
     * to use configuration management or feature flags.
     */
    public void setTrafficAwareRoutingEnabled(boolean enabled) {
        // This would require making the field non-final and adding proper synchronization
        logger.info("Traffic-aware routing enabled status change requested: {}", enabled);
        logger.warn("Runtime configuration change not implemented in this version");
    }
    
    /**
     * Get current configuration status
     */
    public String getConfigurationStatus() {
        return String.format("Traffic-aware routing: %s, Fallback: %s, Service running: %s, Traffic data available: %s",
                           trafficAwareRoutingEnabled,
                           fallbackToNormalRouting,
                           isTrafficServiceRunning(),
                           customModelCreator.hasCurrentTrafficData());
    }
} 