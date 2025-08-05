package com.graphhopper.routing;

import com.graphhopper.config.Profile;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.traffic.TrafficAvoidanceWeighting;
import com.graphhopper.traffic.TrafficAwareCustomModelCreator;
import com.graphhopper.util.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extended weighting factory that integrates TrafficAvoidanceWeighting
 * This allows the routing system to use our custom traffic avoidance logic
 */
public class TrafficAwareWeightingFactory extends DefaultWeightingFactory {
    private static final Logger logger = LoggerFactory.getLogger(TrafficAwareWeightingFactory.class);
    
    private final TrafficAwareCustomModelCreator customModelCreator;
    
    public TrafficAwareWeightingFactory(BaseGraph graph, EncodingManager encodingManager, 
                                      TrafficAwareCustomModelCreator customModelCreator) {
        super(graph, encodingManager);
        this.customModelCreator = customModelCreator;
    }
    
    @Override
    public Weighting createWeighting(Profile profile, PMap requestHints, boolean disableTurnCosts) {
        // First create the base weighting using the parent class
        Weighting baseWeighting = super.createWeighting(profile, requestHints, disableTurnCosts);
        
        // Check if traffic avoidance is requested
        boolean avoidEdsRoads = requestHints.getBool("avoid_eds_roads", false);
        boolean avoidCustomAreas = requestHints.getBool("avoid_custom_areas", false);
        
        if (avoidEdsRoads || avoidCustomAreas) {
            logger.info("Creating TrafficAvoidanceWeighting wrapper for profile: {} (EDS={}, CustomAreas={})", 
                       profile.getName(), avoidEdsRoads, avoidCustomAreas);
            
            // Create TrafficAvoidanceWeighting that wraps the base weighting
            TrafficAvoidanceWeighting trafficAvoidanceWeighting = customModelCreator.createTrafficAvoidanceWeighting(
                baseWeighting, avoidEdsRoads, avoidCustomAreas);
            
            logger.info("TrafficAvoidanceWeighting created successfully");
            return trafficAvoidanceWeighting;
        } else {
            logger.debug("No traffic avoidance requested, using base weighting for profile: {}", profile.getName());
            return baseWeighting;
        }
    }
} 