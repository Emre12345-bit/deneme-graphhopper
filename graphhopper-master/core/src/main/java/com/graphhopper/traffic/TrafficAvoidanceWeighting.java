package com.graphhopper.traffic;

import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.EdgeIteratorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Custom weighting that adds traffic avoidance by returning infinite weight for EDS and custom area edges
 * This allows precise edge-level avoidance without relying on custom model expressions
 */
public class TrafficAvoidanceWeighting implements Weighting {
    private static final Logger logger = LoggerFactory.getLogger(TrafficAvoidanceWeighting.class);
    
    private final Weighting baseWeighting;
    private final Set<Integer> edsEdgeIds;
    private final Set<Integer> customAreaEdgeIds;
    private final boolean avoidEds;
    private final boolean avoidCustomAreas;
    
    public TrafficAvoidanceWeighting(Weighting baseWeighting, Set<Integer> edsEdgeIds, Set<Integer> customAreaEdgeIds,
                                   boolean avoidEds, boolean avoidCustomAreas) {
        this.baseWeighting = baseWeighting;
        this.edsEdgeIds = edsEdgeIds;
        this.customAreaEdgeIds = customAreaEdgeIds;
        this.avoidEds = avoidEds;
        this.avoidCustomAreas = avoidCustomAreas;
        
        logger.info("TrafficAvoidanceWeighting created - EDS: {} edges (avoid: {}), Custom Areas: {} edges (avoid: {})", 
                   edsEdgeIds.size(), avoidEds, customAreaEdgeIds.size(), avoidCustomAreas);
    }
    
    @Override
    public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
        int edgeId = edgeState.getEdge();
        
        // Check if this edge should be avoided
        boolean isEdsEdge = avoidEds && edsEdgeIds.contains(edgeId);
        boolean isCustomAreaEdge = avoidCustomAreas && customAreaEdgeIds.contains(edgeId);
        
        if (isEdsEdge || isCustomAreaEdge) {
            // If both systems want to avoid this edge, use a very high penalty instead of infinite
            // This allows the router to find alternative routes while still strongly avoiding the edge
            double baseWeight = baseWeighting.calcEdgeWeight(edgeState, reverse);
            
            if (isEdsEdge && isCustomAreaEdge) {
                // Both systems want to avoid - use very high penalty (1000x)
                logger.debug("Edge {} avoided by both EDS and Custom Areas - applying 1000x penalty", edgeId);
                return baseWeight * 1000.0;
            } else if (isEdsEdge) {
                // Only EDS avoidance - use high penalty (500x)
                logger.debug("EDS edge {} has 500x penalty", edgeId);
                return baseWeight * 500.0;
            } else {
                // Only Custom Area avoidance - use lower penalty (50x) for better alternative routes
                logger.debug("Custom area edge {} has 50x penalty", edgeId);
                return baseWeight * 50.0;
            }
        }
        
        // Use base weighting for normal edges
        return baseWeighting.calcEdgeWeight(edgeState, reverse);
    }
    
    @Override
    public long calcEdgeMillis(EdgeIteratorState edgeState, boolean reverse) {
        return baseWeighting.calcEdgeMillis(edgeState, reverse);
    }
    
    @Override
    public double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
        return baseWeighting.calcTurnWeight(inEdge, viaNode, outEdge);
    }
    
    @Override
    public long calcTurnMillis(int inEdge, int viaNode, int outEdge) {
        return baseWeighting.calcTurnMillis(inEdge, viaNode, outEdge);
    }
    
    @Override
    public boolean hasTurnCosts() {
        return baseWeighting.hasTurnCosts();
    }
    
    @Override
    public double calcMinWeightPerDistance() {
        return baseWeighting.calcMinWeightPerDistance();
    }
    
    @Override
    public String getName() {
        return "traffic_avoidance_" + baseWeighting.getName();
    }
    
    @Override
    public String toString() {
        return "TrafficAvoidanceWeighting{" +
                "baseWeighting=" + baseWeighting.getName() +
                ", edsEdgeIds=" + edsEdgeIds.size() +
                ", customAreaEdgeIds=" + customAreaEdgeIds.size() +
                ", avoidEds=" + avoidEds +
                ", avoidCustomAreas=" + avoidCustomAreas +
                '}';
    }
} 