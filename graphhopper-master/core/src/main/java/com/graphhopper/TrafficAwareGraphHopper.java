package com.graphhopper;

import com.graphhopper.routing.TrafficAwareWeightingFactory;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.SpeedLimitAwareWeightingFactory;
import com.graphhopper.traffic.TrafficAwareCustomModelCreator;
import com.graphhopper.traffic.SpeedLimitService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extended GraphHopper that uses TrafficAwareWeightingFactory for traffic avoidance
 */
public class TrafficAwareGraphHopper extends GraphHopper {
    
    private static final Logger logger = LoggerFactory.getLogger(TrafficAwareGraphHopper.class);
    
    private TrafficAwareCustomModelCreator customModelCreator;
    private SpeedLimitService speedLimitService;
    
    public void setTrafficAwareCustomModelCreator(TrafficAwareCustomModelCreator customModelCreator) {
        this.customModelCreator = customModelCreator;
    }
    
    /**
     * SpeedLimitService'i başlat
     */
    public void initializeSpeedLimitService(String speedLimitApiUrl) {
        if (speedLimitService == null) {
            speedLimitService = new SpeedLimitService(speedLimitApiUrl);
            logger.info("SpeedLimitService başlatıldı: {}", speedLimitApiUrl);
        }
    }
    
    /**
     * SpeedLimitService'i getir
     */
    public SpeedLimitService getSpeedLimitService() {
        return speedLimitService;
    }
    
    @Override
    protected WeightingFactory createWeightingFactory() {
        if (customModelCreator != null) {
            // SpeedLimitAwareWeightingFactory'ı da inject etmek için önce onu oluştur
            SpeedLimitAwareWeightingFactory speedLimitWeightingFactory = new SpeedLimitAwareWeightingFactory(getBaseGraph().getBaseGraph(), customModelCreator);
            return new TrafficAwareWeightingFactory(getBaseGraph().getBaseGraph(), getEncodingManager(), customModelCreator, speedLimitWeightingFactory);
        } else {
            return super.createWeightingFactory();
        }
    }
    

    
    @Override
    public void close() {
        // SpeedLimitService'i kapat
        if (speedLimitService != null) {
            speedLimitService.shutdown();
            logger.info("SpeedLimitService kapatıldı");
        }
        
        // Parent'ı kapat
        super.close();
    }
} 