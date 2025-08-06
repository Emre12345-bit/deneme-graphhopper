package com.graphhopper.traffic;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.PMap;
import com.graphhopper.util.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.HashMap;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValueImpl;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;
import com.graphhopper.routing.util.EncodingManager;

/**
 * Hız limit verilerini mevcut EDS ve custom-area sistemi ile entegre eden request transformer
 */
@Singleton
public class SpeedLimitAwareRequestTransformer {
    private static final Logger logger = LoggerFactory.getLogger(SpeedLimitAwareRequestTransformer.class);
    
    private final GraphHopperConfig config;
    private final BaseGraph graph;
    private final SpeedLimitService speedLimitService;
    private final SpeedLimitMatcher speedLimitMatcher;
    private final TrafficAwareCustomModelCreator customModelCreator;
    
    // Edge encoding for speed limits - BaseGraph'da kullanmayacağız
    // private final DecimalEncodedValue speedLimitEnc;
    // private final BooleanEncodedValue hasSpeedLimitEnc;
    
    @Inject
    public SpeedLimitAwareRequestTransformer(GraphHopperConfig config, BaseGraph graph, 
                                           TrafficAwareCustomModelCreator customModelCreator,
                                           SpeedLimitService speedLimitService) {
        this.config = config;
        this.graph = graph;
        this.customModelCreator = customModelCreator;
        this.speedLimitService = speedLimitService;
        this.speedLimitMatcher = new SpeedLimitMatcher(graph);
        
        // Edge encoding'leri kullanmayacağız - request hints'e ekleyeceğiz
        // this.speedLimitEnc = new DecimalEncodedValueImpl("speed_limit", 8, 1.0, false);
        // this.hasSpeedLimitEnc = new SimpleBooleanEncodedValue("has_speed_limit", false);
        
        logger.info("Speed limit encodings will be handled in weighting factory");
        
        logger.info("SpeedLimitAwareRequestTransformer initialized");
    }
    
    /**
     * Request'i hız limit verileri ile dönüştür
     */
    public GHRequest transformRequest(GHRequest request) {
        // Check if profile is foot or bike - skip speed limit routing for these profiles
        String profileName = request.getProfile();
        if (profileName != null && (profileName.equals("foot") || profileName.equals("bike"))) {
            logger.info("Skipping speed limit routing for {} profile - not applicable for pedestrians/cyclists", profileName);
            return request;
        }
        
        // Hız limit parametrelerini kontrol et
        Integer carTypeId = request.getHints().getInt("car_type_id", 0);
        boolean enableSpeedLimits = request.getHints().getBool("enable_speed_limits", true);
        
        // Mevcut avoidance parametrelerini al
        boolean avoidEdsRoads = request.getHints().getBool("avoid_eds_roads", false);
        boolean avoidCustomAreas = request.getHints().getBool("avoid_custom_areas", false);
        
        logger.info("Starting request transformation - SpeedLimits: {}, CarType: {}, AvoidEDS: {}, AvoidCustomAreas: {}", 
                   enableSpeedLimits, carTypeId, avoidEdsRoads, avoidCustomAreas);
        
        // Hız limiti false ise sadece EDS/Custom Area kontrolü yap
        if (!enableSpeedLimits) {
            logger.debug("Speed limit routing disabled, checking only EDS/Custom Area avoidance");
            if (avoidEdsRoads || avoidCustomAreas) {
                configureAvoidanceOnly(request, avoidEdsRoads, avoidCustomAreas);
            } else {
                configureNormalPriority(request);
            }
            return request;
        }
        
        // Hız limiti true ama car_type_id yok ise sadece EDS/Custom Area kontrolü yap
        if (carTypeId == 0) {
            logger.debug("Speed limit enabled but no car_type_id provided, checking only EDS/Custom Area avoidance");
            if (avoidEdsRoads || avoidCustomAreas) {
                configureAvoidanceOnly(request, avoidEdsRoads, avoidCustomAreas);
            } else {
                configureNormalPriority(request);
            }
            return request;
        }
        
        try {
                         // 1. Araç tipi için hız limit verilerini al
             Map<String, SpeedLimitService.SpeedLimitData> speedLimits = 
                 speedLimitService.getSpeedLimitsForCarType(carTypeId);
             
             logger.info("🚗 SPEED LIMIT DATA: Found {} speed limit records for car type {} ({})", 
                        speedLimits.size(), carTypeId, getCarTypeName(carTypeId));
             
             // Debug: İlk birkaç kaydı logla
             if (logger.isDebugEnabled() && !speedLimits.isEmpty()) {
                 int count = 0;
                 for (Map.Entry<String, SpeedLimitService.SpeedLimitData> entry : speedLimits.entrySet()) {
                     if (count++ < 3) { // İlk 3 kaydı logla
                                                 SpeedLimitService.SpeedLimitData data = entry.getValue();
                        logger.debug("📊 Speed Limit Record {}: ID={}, Speed={} km/h, Title={}", 
                                   count, data.roadId, data.speedLimit, data.title);
                     }
                 }
             }
            
                         // 2. Hız limit verilerini edge'ler ile eşleştir
             Map<Integer, SpeedLimitService.SpeedLimitData> edgeSpeedLimits = 
                 speedLimitMatcher.matchSpeedLimitsToEdges(speedLimits);
             
             logger.info("🔗 EDGE MATCHING: Matched {} edges with speed limit data", edgeSpeedLimits.size());
             
             // Debug: Eşleşen edge'lerin bir kısmını logla
             if (logger.isDebugEnabled() && !edgeSpeedLimits.isEmpty()) {
                 int count = 0;
                 for (Map.Entry<Integer, SpeedLimitService.SpeedLimitData> entry : edgeSpeedLimits.entrySet()) {
                     if (count++ < 5) { // İlk 5 edge'i logla
                         int edgeId = entry.getKey();
                         SpeedLimitService.SpeedLimitData data = entry.getValue();
                         logger.debug("🔗 Matched Edge {}: EdgeID={}, Speed={} km/h, Title={}", 
                                    count, edgeId, data.speedLimit, data.title);
                     }
                 }
             }
            
            // 3. Edge'lere hız limit verilerini uygula
            applySpeedLimitsToEdges(edgeSpeedLimits);
            
            // 4. Request'e hız limit bilgilerini ekle
            addSpeedLimitHints(request, carTypeId, edgeSpeedLimits);
            
            // 5. EDS/Custom Area kaçınması varsa önce onu uygula, sonra hız limiti
            if (avoidEdsRoads || avoidCustomAreas) {
                configureEdsFirstThenSpeedLimit(request, avoidEdsRoads, avoidCustomAreas, edgeSpeedLimits.size());
                logger.info("EDS/Custom Area first priority request transformation completed successfully");
            } else {
                // Sadece hız limiti uygula
                configureSpeedLimitOnly(request, edgeSpeedLimits.size());
                logger.info("Speed limit only request transformation completed successfully");
            }
            
            return request;
            
        } catch (Exception e) {
            logger.error("Speed limit request transformation failed: {}", e.getMessage());
            // Hata durumunda sadece EDS/Custom Area kontrolü yap
            if (avoidEdsRoads || avoidCustomAreas) {
                configureAvoidanceOnly(request, avoidEdsRoads, avoidCustomAreas);
            } else {
                configureNormalPriority(request);
            }
            return request;
        }
    }
    
    /**
     * Edge'lere hız limit verilerini uygula - Request hints'e ekle
     */
    private void applySpeedLimitsToEdges(Map<Integer, SpeedLimitService.SpeedLimitData> edgeSpeedLimits) {
        int validEdgeCount = 0;
        int invalidEdgeCount = 0;
        int maxEdgeId = graph.getEdges();
        
        logger.info("Graph edge count: {}, Max edge ID: {}", graph.getEdges(), maxEdgeId - 1);
        
        for (Map.Entry<Integer, SpeedLimitService.SpeedLimitData> entry : edgeSpeedLimits.entrySet()) {
            int edgeId = entry.getKey();
            SpeedLimitService.SpeedLimitData speedLimit = entry.getValue();
            
            // Edge ID geçerliliğini kontrol et
            if (edgeId < 0 || edgeId >= maxEdgeId) {
                logger.warn("Invalid edge ID: {} (valid range: 0-{})", edgeId, maxEdgeId - 1);
                invalidEdgeCount++;
                continue;
            }
            
            try {
                // Edge'i al
                EdgeIteratorState edge = graph.getEdgeIteratorState(edgeId, Integer.MIN_VALUE);
                
                // Edge'in geçerli olup olmadığını kontrol et
                if (edge == null) {
                    logger.warn("Edge {} does not exist in graph", edgeId);
                    invalidEdgeCount++;
                    continue;
                }
                
                // Edge geçerli - weighting factory'de kullanılacak
                validEdgeCount++;
                logger.debug("Valid edge {} with speed limit {}", edgeId, speedLimit.speedLimit);
                
            } catch (Exception e) {
                logger.warn("Failed to validate edge {}: {}", edgeId, e.getMessage());
                invalidEdgeCount++;
            }
        }
        
                 logger.info("✅ EDGE VALIDATION: Found {} valid edges with speed limit data, {} invalid edges skipped", 
                    validEdgeCount, invalidEdgeCount);
    }
    
    /**
     * Request'e hız limit bilgilerini ekle
     */
    private void addSpeedLimitHints(GHRequest request, int carTypeId, 
                                  Map<Integer, SpeedLimitService.SpeedLimitData> edgeSpeedLimits) {
        PMap hints = request.getHints();
        
        // Hız limit bilgilerini ekle
        hints.putObject("speed_limit_enabled", true);
        hints.putObject("car_type_id", carTypeId);
        hints.putObject("speed_limit_edges_count", edgeSpeedLimits.size());
        
        // Hız limit verilerini request hints'e ekle
        hints.putObject("speed_limit_data", edgeSpeedLimits);
        
        // Araç tipi bilgisini ekle
        Map<Integer, String> carTypes = getCarTypes();
        String carTypeName = carTypes.getOrDefault(carTypeId, "Unknown");
        hints.putObject("car_type_name", carTypeName);
        
        logger.debug("Added speed limit hints - CarType: {} ({}), Edges: {}", 
                   carTypeId, carTypeName, edgeSpeedLimits.size());
    }
    
    /**
     * EDS/Custom Area öncelikli yapılandırma - Önce kaçınma, sonra hız limiti
     */
    private void configureEdsFirstThenSpeedLimit(GHRequest request, boolean avoidEdsRoads, 
                                               boolean avoidCustomAreas, int speedLimitEdgesCount) {
        PMap hints = request.getHints();
        
        // Normal routing algoritması kullan (gereksiz uzun rotalar çizme)
        if (request.getAlgorithm().isEmpty()) {
            request.setAlgorithm("dijkstrabi");
        }
        
        // CH ve LM'yi etkinleştir (hızlı routing için)
        hints.putObject(Parameters.CH.DISABLE, false);
        hints.putObject(Parameters.Landmark.DISABLE, false);
        

        
        logger.info("🚀 EDS FIRST + SPEED LIMIT: Avoid EDS/Custom Areas (STRICT BLOCKING) + Speed Limit on matched roads only");
        hints.putObject("routing_priority", "eds_first_then_speed_limit");
    }
    
    /**
     * Sadece hız limiti yapılandırması (kaçınma olmadan)
     */
    private void configureSpeedLimitOnly(GHRequest request, int speedLimitEdgesCount) {
        PMap hints = request.getHints();
        
        // Normal routing algoritması kullan (gereksiz uzun rotalar çizme)
        if (request.getAlgorithm().isEmpty()) {
            request.setAlgorithm("dijkstrabi");
        }
        
        // CH ve LM'yi etkinleştir (hızlı routing için)
        hints.putObject(Parameters.CH.DISABLE, false);
        hints.putObject(Parameters.Landmark.DISABLE, false);
        
        logger.info("🟢 SPEED LIMIT ONLY: Optimizing for speed limit compliance on matched roads only");
        hints.putObject("routing_priority", "speed_limit_only");
    }
    

    
    /**
     * Sadece kaçınma yapılandırması (hız limiti olmadan)
     */
    private void configureAvoidanceOnly(GHRequest request, boolean avoidEdsRoads, boolean avoidCustomAreas) {
        PMap hints = request.getHints();
        
        // Normal routing algoritması kullan (gereksiz uzun rotalar çizme)
        if (request.getAlgorithm().isEmpty()) {
            request.setAlgorithm("dijkstrabi");
        }
        
        // CH ve LM'yi etkinleştir (hızlı routing için)
        hints.putObject(Parameters.CH.DISABLE, false);
        hints.putObject(Parameters.Landmark.DISABLE, false);
        

        
        if (avoidEdsRoads && avoidCustomAreas) {
            logger.info("🔴 AVOIDANCE ONLY: Both EDS and Custom Areas avoidance (STRICT BLOCKING)");
        } else if (avoidEdsRoads) {
            logger.info("🟡 AVOIDANCE ONLY: EDS avoidance only (STRICT BLOCKING)");
        } else {
            logger.info("🟡 AVOIDANCE ONLY: Custom Areas avoidance only (STRICT BLOCKING)");
        }
        
        hints.putObject("routing_priority", "avoidance_only");
    }
    

    

    
    /**
     * Normal öncelik yapılandırması
     */
    private void configureNormalPriority(GHRequest request) {
        PMap hints = request.getHints();
        
        // Varsayılan algoritma kullan
        if (request.getAlgorithm().isEmpty()) {
            request.setAlgorithm("dijkstrabi");
        }
        
        logger.info("🔵 NORMAL PRIORITY: Standard routing without special constraints");
        hints.putObject("routing_priority", "normal");
    }
    
         /**
      * Araç tipi bilgilerini getir
      */
     private Map<Integer, String> getCarTypes() {
         Map<Integer, String> carTypes = new HashMap<>();
         carTypes.put(1, "Otomobil");
         carTypes.put(2, "Minibüs");
         carTypes.put(3, "Otobüs");
         carTypes.put(4, "Kamyonet");
         carTypes.put(5, "Kamyon");
         carTypes.put(6, "Çekici");
         return carTypes;
     }
     
     /**
      * Araç tipi adını getir
      */
     private String getCarTypeName(int carTypeId) {
         Map<Integer, String> carTypes = getCarTypes();
         return carTypes.getOrDefault(carTypeId, "Unknown");
     }
    
    /**
     * Cache'i temizle
     */
    public void clearCache() {
        speedLimitService.clearCache();
        speedLimitMatcher.clearCache();
        logger.info("Speed limit cache cleared");
    }
    
    /**
     * Cache istatistiklerini getir
     */
    public String getCacheStats() {
        return String.format("SpeedLimit: %s, Matcher: %s", 
                           speedLimitService.getCacheStats(), 
                           speedLimitMatcher.getCacheStats());
    }
} 