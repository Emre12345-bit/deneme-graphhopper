package com.graphhopper;

import com.graphhopper.routing.TrafficAwareWeightingFactory;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.traffic.TrafficAwareCustomModelCreator;

/**
 * Extended GraphHopper that uses TrafficAwareWeightingFactory for traffic avoidance
 */
public class TrafficAwareGraphHopper extends GraphHopper {
    
    private TrafficAwareCustomModelCreator customModelCreator;
    
    public void setTrafficAwareCustomModelCreator(TrafficAwareCustomModelCreator customModelCreator) {
        this.customModelCreator = customModelCreator;
    }
    
    @Override
    protected WeightingFactory createWeightingFactory() {
        if (customModelCreator != null) {
            return new TrafficAwareWeightingFactory(getBaseGraph().getBaseGraph(), getEncodingManager(), customModelCreator);
        } else {
            return super.createWeightingFactory();
        }
    }
} 