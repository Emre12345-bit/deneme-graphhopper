package com.graphhopper.traffic;

import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.util.EdgeIteratorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * EdgeFilter that excludes EDS and custom area edges from routing
 * This allows precise edge-level avoidance without relying on custom model expressions
 */
public class TrafficAvoidanceEdgeFilter implements EdgeFilter {
    private static final Logger logger = LoggerFactory.getLogger(TrafficAvoidanceEdgeFilter.class);
    
    private final Set<Integer> edsEdgeIds;
    private final Set<Integer> customAreaEdgeIds;
    private final boolean avoidEds;
    private final boolean avoidCustomAreas;
    
    public TrafficAvoidanceEdgeFilter(Set<Integer> edsEdgeIds, Set<Integer> customAreaEdgeIds, 
                                    boolean avoidEds, boolean avoidCustomAreas) {
        this.edsEdgeIds = edsEdgeIds;
        this.customAreaEdgeIds = customAreaEdgeIds;
        this.avoidEds = avoidEds;
        this.avoidCustomAreas = avoidCustomAreas;
        
        logger.info("TrafficAvoidanceEdgeFilter created - EDS: {} edges (avoid: {}), Custom Areas: {} edges (avoid: {})", 
                   edsEdgeIds.size(), avoidEds, customAreaEdgeIds.size(), avoidCustomAreas);
    }
    
    @Override
    public boolean accept(EdgeIteratorState edgeState) {
        int edgeId = edgeState.getEdge();
        
        // Check if this edge should be avoided
        if (avoidEds && edsEdgeIds.contains(edgeId)) {
            logger.debug("Rejecting EDS edge: {}", edgeId);
            return false;
        }
        
        if (avoidCustomAreas && customAreaEdgeIds.contains(edgeId)) {
            logger.debug("Rejecting custom area edge: {}", edgeId);
            return false;
        }
        
        // Accept the edge if it's not in any avoidance list
        return true;
    }
    
    @Override
    public String toString() {
        return "TrafficAvoidanceEdgeFilter{" +
                "edsEdgeIds=" + edsEdgeIds.size() +
                ", customAreaEdgeIds=" + customAreaEdgeIds.size() +
                ", avoidEds=" + avoidEds +
                ", avoidCustomAreas=" + avoidCustomAreas +
                '}';
    }
} 