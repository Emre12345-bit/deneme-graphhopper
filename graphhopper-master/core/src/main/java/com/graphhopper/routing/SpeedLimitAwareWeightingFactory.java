package com.graphhopper.routing;

import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.config.Profile;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.PMap;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.traffic.TrafficAwareCustomModelCreator;
import com.graphhopper.traffic.SpeedLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Set;
import java.util.Map;

/**
 * Hız limit verilerini kullanarak weighting oluşturan factory
 */
@Singleton
public class SpeedLimitAwareWeightingFactory {
    private static final Logger logger = LoggerFactory.getLogger(SpeedLimitAwareWeightingFactory.class);
    
    private final BaseGraph graph;
    private final EncodingManager encodingManager;
    private final TrafficAwareCustomModelCreator customModelCreator;
    
    // Speed limit edge encodings
    private final DecimalEncodedValue speedLimitEnc;
    private final BooleanEncodedValue hasSpeedLimitEnc;
    
    @Inject
    public SpeedLimitAwareWeightingFactory(BaseGraph graph, TrafficAwareCustomModelCreator customModelCreator) {
        this.graph = graph;
        // BaseGraph'da getEncodingManager() yok, bu yüzden encoding'leri doğrudan oluşturuyoruz
        this.encodingManager = null; // EncodingManager'ı kullanmayacağız
        this.customModelCreator = customModelCreator;
        
        // Speed limit encodings'i doğrudan oluştur (BaseGraph'da EncodingManager yok)
        this.speedLimitEnc = new DecimalEncodedValueImpl("speed_limit", 8, 1.0, false);
        this.hasSpeedLimitEnc = new SimpleBooleanEncodedValue("has_speed_limit", false);
        logger.info("Speed limit encodings created directly");
        
                 logger.info("SpeedLimitAwareWeightingFactory initialized");
     }
     
     /**
      * Araç tipi adını getir
      */
     private String getCarTypeName(int carTypeId) {
         switch (carTypeId) {
             case 1: return "Otomobil";
             case 2: return "Minibüs";
             case 3: return "Otobüs";
             case 4: return "Kamyonet";
             case 5: return "Kamyon";
             case 6: return "Çekici";
             default: return "Unknown";
         }
     }
    
    /**
     * Hız limit verilerini kullanarak weighting oluştur
     */
    public Weighting createSpeedLimitAwareWeighting(Profile profile, PMap requestHints, Weighting baseWeighting) {
        // Check if profile is foot or bike - skip speed limit weighting for these profiles
        String profileName = profile.getName();
        if (profileName != null && (profileName.equals("foot") || profileName.equals("bike"))) {
            logger.debug("Skipping speed limit weighting for {} profile - not applicable for pedestrians/cyclists", profileName);
            return baseWeighting;
        }
        
        // Hız limit parametrelerini kontrol et
        Integer carTypeId = requestHints.getInt("car_type_id", 0);
        boolean speedLimitEnabled = requestHints.getBool("speed_limit_enabled", false);
        
        // Mevcut avoidance parametrelerini kontrol et
        boolean avoidEdsRoads = requestHints.getBool("avoid_eds_roads", false);
        boolean avoidCustomAreas = requestHints.getBool("avoid_custom_areas", false);
        
        // Hız limiti false olsa bile EDS/Custom Area kontrolü yap
        if (!speedLimitEnabled || carTypeId == 0) {
            if (avoidEdsRoads || avoidCustomAreas) {
                logger.info("Speed limit disabled but avoidance enabled - Creating avoidance-only weighting");
                return new AvoidanceOnlyWeighting(baseWeighting, avoidEdsRoads, avoidCustomAreas);
            } else {
                logger.debug("Speed limit weighting disabled and no avoidance needed");
                return baseWeighting;
            }
        }
        
                 logger.info("⚖️ WEIGHTING CREATION: Creating speed limit aware weighting - CarType: {} ({}), AvoidEDS: {}, AvoidCustomAreas: {}", 
                    carTypeId, getCarTypeName(carTypeId), avoidEdsRoads, avoidCustomAreas);
        
        try {
            // Hız limit weighting'i oluştur
            SpeedLimitAwareWeighting speedLimitWeighting = new SpeedLimitAwareWeighting(
                baseWeighting, speedLimitEnc, hasSpeedLimitEnc, carTypeId, avoidEdsRoads, avoidCustomAreas, requestHints);
            
                         logger.info("✅ WEIGHTING CREATED: Speed limit aware weighting created successfully");
            return speedLimitWeighting;
            
        } catch (Exception e) {
            logger.error("Failed to create speed limit aware weighting: {}", e.getMessage());
            // Hata durumunda sadece avoidance weighting'i dene
            if (avoidEdsRoads || avoidCustomAreas) {
                return new AvoidanceOnlyWeighting(baseWeighting, avoidEdsRoads, avoidCustomAreas);
            }
            return baseWeighting;
        }
    }
    
    /**
     * Hız limit verilerini kullanarak weighting hesaplayan sınıf
     */
    public class SpeedLimitAwareWeighting implements Weighting {
        private final Weighting baseWeighting;
        private final DecimalEncodedValue speedLimitEnc;
        private final BooleanEncodedValue hasSpeedLimitEnc;
        private final int carTypeId;
        private final boolean avoidEdsRoads;
        private final boolean avoidCustomAreas;
        private final PMap requestHints;
        
        // Hız limit ağırlık faktörleri
        private static final double AVOIDANCE_PENALTY_FACTOR = 10.0;  // Kaçınılması gereken yollar için büyük ceza
        
        public SpeedLimitAwareWeighting(Weighting baseWeighting, DecimalEncodedValue speedLimitEnc, 
                                      BooleanEncodedValue hasSpeedLimitEnc, int carTypeId, 
                                      boolean avoidEdsRoads, boolean avoidCustomAreas, PMap requestHints) {
            this.baseWeighting = baseWeighting;
            this.speedLimitEnc = speedLimitEnc;
            this.hasSpeedLimitEnc = hasSpeedLimitEnc;
            this.carTypeId = carTypeId;
            this.avoidEdsRoads = avoidEdsRoads;
            this.avoidCustomAreas = avoidCustomAreas;
            this.requestHints = requestHints;
        }
        
        @Override
        public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
            // Edge ID geçerliliğini kontrol et
            int edgeId = edgeState.getEdge();
            int maxEdgeId = graph.getEdges();
            
            if (edgeId < 0 || edgeId >= maxEdgeId) {
                logger.warn("Invalid edge ID in SpeedLimitAwareWeighting: {} (max: {})", edgeId, maxEdgeId - 1);
                // Geçersiz edge için base weighting'i döndür
                return baseWeighting.calcEdgeWeight(edgeState, reverse);
            }
            
            // Base weighting'i hesapla
            double baseWeight = baseWeighting.calcEdgeWeight(edgeState, reverse);
            
                    // Önce kaçınma ağırlığını hesapla (EDS öncelikli)
        double avoidanceWeight = calculateAvoidanceWeight(edgeState, reverse);
        
        // EDS edge ise hız limit bonus'u uygulama
        if (avoidanceWeight > 1.0) {
            // EDS edge - sadece kaçınma ağırlığını kullan
            double totalWeight = baseWeight * avoidanceWeight;
            logger.debug("EDS edge {} - using only avoidance weight: {}", edgeId, totalWeight);
            return totalWeight;
        }
        
        // Sonra hız limit ağırlığını hesapla (sadece normal yollarda)
        double speedLimitWeight = calculateSpeedLimitWeight(edgeState, reverse);
        
        // Toplam ağırlığı hesapla - EDS öncelikli
        double totalWeight = baseWeight * avoidanceWeight * speedLimitWeight;
            
            // Debug log
            if (logger.isDebugEnabled()) {
                boolean hasSpeedLimit = hasSpeedLimitEnc.getBool(reverse, edgeId, graph.getEdgeAccess());
                boolean isEdsEdge = isEdsEdge(edgeId);
                boolean isCustomAreaEdge = isCustomAreaEdge(edgeId);
                
                                 if (hasSpeedLimit || isEdsEdge || isCustomAreaEdge) {
                     double osmSpeedLimit = getOsmSpeedLimit(edgeState, reverse);
                     
                     // Request hints'ten API hız limitini al
                     @SuppressWarnings("unchecked")
                     Map<Integer, SpeedLimitService.SpeedLimitData> speedLimitData = 
                         (Map<Integer, SpeedLimitService.SpeedLimitData>) requestHints.getObject("speed_limit_data", null);
                     int apiSpeedLimit = 0;
                     String speedLimitTitle = "";
                     if (speedLimitData != null && speedLimitData.containsKey(edgeId)) {
                         SpeedLimitService.SpeedLimitData speedLimit = speedLimitData.get(edgeId);
                         if (speedLimit != null) {
                             apiSpeedLimit = speedLimit.speedLimit;
                             speedLimitTitle = speedLimit.title;
                         }
                     }
                     
                     // Log seviyesini belirle
                     if (isEdsEdge || isCustomAreaEdge) {
                         logger.info("🚨 CRITICAL EDGE {}: base={}, avoidance={}, total={}, isEds={}, isCustomArea={}", 
                                   edgeId, baseWeight, avoidanceWeight, totalWeight, isEdsEdge, isCustomAreaEdge);
                     } else if (hasSpeedLimit) {
                         logger.info("🚗 SPEED LIMIT EDGE {}: base={}, speedLimit={}, total={}, osmSpeedLimit={}, apiSpeedLimit={}, title={}", 
                                   edgeId, baseWeight, speedLimitWeight, totalWeight, osmSpeedLimit, apiSpeedLimit, speedLimitTitle);
                     } else {
                         logger.debug("🔍 EDGE {}: base={}, avoidance={}, speedLimit={}, total={}, hasSpeedLimit={}, isEds={}, isCustomArea={}, osmSpeedLimit={}, apiSpeedLimit={}", 
                                    edgeId, baseWeight, avoidanceWeight, speedLimitWeight, totalWeight, 
                                    hasSpeedLimit, isEdsEdge, isCustomAreaEdge, osmSpeedLimit, apiSpeedLimit);
                     }
                 }
            }
            
            return totalWeight;
        }
        
        @Override
        public double calcMinWeightPerDistance() {
            return baseWeighting.calcMinWeightPerDistance();
        }
        
                /**
         * Hız limit ağırlığını hesapla - Request hints'ten veri al
         */
        private double calculateSpeedLimitWeight(EdgeIteratorState edgeState, boolean reverse) {
            int edgeId = edgeState.getEdge();
            int maxEdgeId = graph.getEdges();
            
            // Edge ID geçerliliğini kontrol et
            if (edgeId < 0 || edgeId >= maxEdgeId) {
                logger.warn("Invalid edge ID in calculateSpeedLimitWeight: {} (max: {})", edgeId, maxEdgeId - 1);
                return 1.0; // Geçersiz edge için nötr ağırlık
            }
            
            // Request hints'ten hız limit verilerini al
            @SuppressWarnings("unchecked")
            Map<Integer, SpeedLimitService.SpeedLimitData> speedLimitData = 
                (Map<Integer, SpeedLimitService.SpeedLimitData>) requestHints.getObject("speed_limit_data", null);
            
            if (speedLimitData == null || !speedLimitData.containsKey(edgeId)) {
                return 1.0; // Hız limit verisi yok - nötr ağırlık
            }
            
            SpeedLimitService.SpeedLimitData speedLimit = speedLimitData.get(edgeId);
            if (speedLimit == null || speedLimit.speedLimit <= 0) {
                return 1.0; // Geçersiz hız limiti
            }
            
            // OSM'deki hız limitini al
            double osmSpeedLimit = getOsmSpeedLimit(edgeState, reverse);
            
            // OSM karşılaştırma logunu INFO seviyesine çıkar
            logger.info("🔍 OSM COMPARISON: Edge {} - API Speed: {} km/h, OSM Speed: {} km/h", 
                       edgeId, speedLimit.speedLimit, osmSpeedLimit);
            
            // API'den gelen hız limiti ile OSM'deki hız limitini karşılaştır
            if (speedLimit.speedLimit >= osmSpeedLimit) {
                // API hız limiti OSM'den yüksek veya eşit - hız farkına göre dinamik bonus
                double speedDifference = speedLimit.speedLimit - osmSpeedLimit;
                double bonusFactor = calculateDynamicBonus(speedDifference);
                logger.info("✅ SPEED BONUS: Edge {} - API({}) >= OSM({}) → SpeedDiff: {} km/h, BonusFactor: {} (Dynamic Bonus applied)", 
                           edgeId, speedLimit.speedLimit, osmSpeedLimit, speedDifference, bonusFactor);
                return bonusFactor;
            } else {
                // API hız limiti OSM'den düşük - hız farkına göre dinamik ceza
                double speedDifference = osmSpeedLimit - speedLimit.speedLimit;
                double penaltyFactor = calculateDynamicPenalty(speedDifference, osmSpeedLimit, speedLimit.speedLimit);
                logger.info("⚠️ SPEED PENALTY: Edge {} - API({}) < OSM({}) → SpeedDiff: {} km/h, PenaltyFactor: {} (Dynamic Penalty applied)", 
                           edgeId, speedLimit.speedLimit, osmSpeedLimit, speedDifference, penaltyFactor);
                return penaltyFactor;
            }
        }
        
        /**
         * Kaçınma ağırlığını hesapla - EDS öncelikli olduğu için katı kaçınma
         */
        private double calculateAvoidanceWeight(EdgeIteratorState edgeState, boolean reverse) {
            int edgeId = edgeState.getEdge();
            int maxEdgeId = graph.getEdges();
            
            // Edge ID geçerliliğini kontrol et
            if (edgeId < 0 || edgeId >= maxEdgeId) {
                logger.warn("Invalid edge ID in calculateAvoidanceWeight: {} (max: {})", edgeId, maxEdgeId - 1);
                return 1.0; // Geçersiz edge için nötr ağırlık
            }
            
                         if (avoidEdsRoads) {
                 if (isEdsEdge(edgeId)) {
                     // EDS öncelikli - kesinlikle kaçın
                     logger.info("🚨 EDS AVOIDANCE: Edge {} detected as EDS road, applying strict avoidance (penalty: {})", 
                               edgeId, AVOIDANCE_PENALTY_FACTOR);
                     return AVOIDANCE_PENALTY_FACTOR; // Tam ceza
                 }
             }
            
                         if (avoidCustomAreas) {
                 if (isCustomAreaEdge(edgeId)) {
                     // Custom Area öncelikli - kesinlikle kaçın
                     logger.info("🚨 CUSTOM AREA AVOIDANCE: Edge {} detected as Custom Area road, applying strict avoidance (penalty: {})", 
                               edgeId, AVOIDANCE_PENALTY_FACTOR);
                     return AVOIDANCE_PENALTY_FACTOR; // Tam ceza
                 }
             }
            
            return 1.0; // Kaçınma yok veya bu edge kaçınılması gereken bir edge değil
        }
        
        /**
         * Edge'in EDS edge'i olup olmadığını kontrol et
         */
        private boolean isEdsEdge(int edgeId) {
            // Edge ID geçerliliğini kontrol et
            int maxEdgeId = graph.getEdges();
            if (edgeId < 0 || edgeId >= maxEdgeId) {
                logger.debug("Invalid edge ID in SpeedLimitAwareWeighting.isEdsEdge: {} (max: {})", edgeId, maxEdgeId - 1);
                return false;
            }
            
            try {
                // TrafficAwareCustomModelCreator'dan EDS edge set'ini al
                Set<Integer> edsEdgeIds = customModelCreator.getEdsEdgeIds();
                return edsEdgeIds != null && edsEdgeIds.contains(edgeId);
            } catch (Exception e) {
                logger.debug("Error checking EDS edge {}: {}", edgeId, e.getMessage());
                return false;
            }
        }
        
        /**
         * Edge'in Custom Area edge'i olup olmadığını kontrol et
         */
        private boolean isCustomAreaEdge(int edgeId) {
            // Edge ID geçerliliğini kontrol et
            int maxEdgeId = graph.getEdges();
            if (edgeId < 0 || edgeId >= maxEdgeId) {
                logger.debug("Invalid edge ID in SpeedLimitAwareWeighting.isCustomAreaEdge: {} (max: {})", edgeId, maxEdgeId - 1);
                return false;
            }
            
            try {
                // TrafficAwareCustomModelCreator'dan Custom Area edge set'ini al
                Set<Integer> customAreaEdgeIds = customModelCreator.getCustomAreaEdgeIds();
                return customAreaEdgeIds != null && customAreaEdgeIds.contains(edgeId);
            } catch (Exception e) {
                logger.debug("Error checking Custom Area edge {}: {}", edgeId, e.getMessage());
                return false;
            }
        }
        
        /**
         * Hız farkına göre dinamik bonus hesapla
         */
        private double calculateDynamicBonus(double speedDifference) {
            // Hız farkına göre dinamik bonus hesaplama
            // Küçük farklar için hafif bonus, büyük farklar için daha güçlü bonus
            
            if (speedDifference <= 5) {
                // 0-5 km/h fark: %3 bonus
                return 0.97;
            } else if (speedDifference <= 15) {
                // 6-15 km/h fark: %5 bonus
                return 0.95;
            } else if (speedDifference <= 30) {
                // 16-30 km/h fark: %8 bonus
                return 0.92;
            } else if (speedDifference <= 50) {
                // 31-50 km/h fark: %12 bonus
                return 0.88;
            } else {
                // 50+ km/h fark: %15 bonus (maksimum)
                return 0.85;
            }
        }
        
        /**
         * Hız farkına göre dinamik ceza hesapla
         */
        private double calculateDynamicPenalty(double speedDifference, double osmSpeed, double apiSpeed) {
            // Hız farkına göre dinamik ceza hesaplama
            // Küçük farklar için hafif ceza, büyük farklar için daha güçlü ceza
            
            if (speedDifference <= 5) {
                // 0-5 km/h fark: %3 ceza
                return 1.03;
            } else if (speedDifference <= 15) {
                // 6-15 km/h fark: %8 ceza
                return 1.08;
            } else if (speedDifference <= 30) {
                // 16-30 km/h fark: %15 ceza
                return 1.15;
            } else if (speedDifference <= 50) {
                // 31-50 km/h fark: %25 ceza
                return 1.25;
            } else {
                // 50+ km/h fark: %35 ceza (maksimum)
                return 1.35;
            }
        }
        
        /**
         * OSM'deki hız limitini al (eğer yoksa araç tipine göre varsayılan değer)
         */
        private double getOsmSpeedLimit(EdgeIteratorState edgeState, boolean reverse) {
            // OSM'deki hız limitini al
            DecimalEncodedValue osmMaxSpeedEnc = new DecimalEncodedValueImpl("max_speed", 7, 0, 2, false, true, true);
            double osmSpeedLimit = osmMaxSpeedEnc.getDecimal(reverse, edgeState.getEdge(), graph.getEdgeAccess());
            
            // Eğer OSM'de hız limiti varsa onu kullan
            if (osmSpeedLimit != Double.POSITIVE_INFINITY && osmSpeedLimit > 0) {
                logger.debug("OSM Speed Limit found: {} km/h for edge {}", osmSpeedLimit, edgeState.getEdge());
                return osmSpeedLimit;
            }
            
            // OSM'de hız limiti yoksa araç tipine göre varsayılan değer
            int defaultSpeed = getDefaultSpeedLimitForCarType(carTypeId);
            logger.debug("No OSM speed limit for edge {}, using default: {} km/h for car type {}", 
                        edgeState.getEdge(), defaultSpeed, carTypeId);
            return defaultSpeed;
        }
        
        /**
         * Araç tipine göre varsayılan hız limiti getir (Şehir içi için makul seviyeler)
         */
        private int getDefaultSpeedLimitForCarType(int carTypeId) {
            switch (carTypeId) {
                case 1: return 50; // Otomobil - Şehir içi 50 km/h
                case 2: return 50; // Minibüs - Şehir içi 50 km/h
                case 3: return 50; // Otobüs - Şehir içi 50 km/h
                case 4: return 45; // Kamyonet - Şehir içi 45 km/h
                case 5: return 40; // Kamyon - Şehir içi 40 km/h
                case 6: return 40; // Çekici - Şehir içi 40 km/h
                default: return 45; // Varsayılan şehir içi hız limiti
            }
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
        public String getName() {
            return "speed_limit_aware";
        }
        
        @Override
        public String toString() {
            return String.format("SpeedLimitAwareWeighting{carType=%d, avoidEDS=%s, avoidCustomAreas=%s}", 
                               carTypeId, avoidEdsRoads, avoidCustomAreas);
        }
    }
    
    /**
     * Sadece kaçınma weighting'i (hız limiti olmadan)
     */
    public class AvoidanceOnlyWeighting implements Weighting {
        private final Weighting baseWeighting;
        private final boolean avoidEdsRoads;
        private final boolean avoidCustomAreas;
        
        private static final double AVOIDANCE_PENALTY_FACTOR = 10.0;  // Kaçınılması gereken yollar için büyük ceza
        
        public AvoidanceOnlyWeighting(Weighting baseWeighting, boolean avoidEdsRoads, boolean avoidCustomAreas) {
            this.baseWeighting = baseWeighting;
            this.avoidEdsRoads = avoidEdsRoads;
            this.avoidCustomAreas = avoidCustomAreas;
        }
        
        @Override
        public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
            // Edge ID geçerliliğini kontrol et
            int edgeId = edgeState.getEdge();
            int maxEdgeId = graph.getEdges();
            
            if (edgeId < 0 || edgeId >= maxEdgeId) {
                logger.warn("Invalid edge ID in AvoidanceOnlyWeighting: {} (max: {})", edgeId, maxEdgeId - 1);
                // Geçersiz edge için base weighting'i döndür
                return baseWeighting.calcEdgeWeight(edgeState, reverse);
            }
            
            // Base weighting'i hesapla
            double baseWeight = baseWeighting.calcEdgeWeight(edgeState, reverse);
            
            // Sadece kaçınma ağırlığını hesapla
            double avoidanceWeight = calculateAvoidanceWeight(edgeState, reverse);
            
            // Toplam ağırlığı hesapla
            double totalWeight = baseWeight * avoidanceWeight;
            
            // Debug log
            if (logger.isDebugEnabled()) {
                boolean isEdsEdge = isEdsEdge(edgeId);
                boolean isCustomAreaEdge = isCustomAreaEdge(edgeId);
                
                if (isEdsEdge || isCustomAreaEdge) {
                    logger.debug("AvoidanceOnly Edge {}: base={}, avoidance={}, total={}, isEds={}, isCustomArea={}", 
                               edgeId, baseWeight, avoidanceWeight, totalWeight, isEdsEdge, isCustomAreaEdge);
                }
            }
            
            return totalWeight;
        }
        
        /**
         * Kaçınma ağırlığını hesapla (katı kaçınma)
         */
        private double calculateAvoidanceWeight(EdgeIteratorState edgeState, boolean reverse) {
            int edgeId = edgeState.getEdge();
            int maxEdgeId = graph.getEdges();
            
            // Edge ID geçerliliğini kontrol et
            if (edgeId < 0 || edgeId >= maxEdgeId) {
                logger.warn("Invalid edge ID in AvoidanceOnlyWeighting.calculateAvoidanceWeight: {} (max: {})", edgeId, maxEdgeId - 1);
                return 1.0; // Geçersiz edge için nötr ağırlık
            }
            
            if (avoidEdsRoads && isEdsEdge(edgeId)) {
                logger.debug("EDS edge detected ({}), applying strict avoidance", edgeId);
                return AVOIDANCE_PENALTY_FACTOR; // Tam ceza
            }
            
            if (avoidCustomAreas && isCustomAreaEdge(edgeId)) {
                logger.debug("Custom Area edge detected ({}), applying strict avoidance", edgeId);
                return AVOIDANCE_PENALTY_FACTOR; // Tam ceza
            }
            
            return 1.0; // Kaçınma yok veya bu edge kaçınılması gereken bir edge değil
        }
        
        /**
         * Edge'in EDS edge'i olup olmadığını kontrol et
         */
        private boolean isEdsEdge(int edgeId) {
            // Edge ID geçerliliğini kontrol et
            int maxEdgeId = graph.getEdges();
            if (edgeId < 0 || edgeId >= maxEdgeId) {
                logger.debug("Invalid edge ID in AvoidanceOnlyWeighting.isEdsEdge: {} (max: {})", edgeId, maxEdgeId - 1);
                return false;
            }
            
            try {
                // TrafficAwareCustomModelCreator'dan EDS edge set'ini al
                Set<Integer> edsEdgeIds = customModelCreator.getEdsEdgeIds();
                return edsEdgeIds != null && edsEdgeIds.contains(edgeId);
            } catch (Exception e) {
                logger.debug("Error checking EDS edge {}: {}", edgeId, e.getMessage());
                return false;
            }
        }
        
        /**
         * Edge'in Custom Area edge'i olup olmadığını kontrol et
         */
        private boolean isCustomAreaEdge(int edgeId) {
            // Edge ID geçerliliğini kontrol et
            int maxEdgeId = graph.getEdges();
            if (edgeId < 0 || edgeId >= maxEdgeId) {
                logger.debug("Invalid edge ID in AvoidanceOnlyWeighting.isCustomAreaEdge: {} (max: {})", edgeId, maxEdgeId - 1);
                return false;
            }
            
            try {
                // TrafficAwareCustomModelCreator'dan Custom Area edge set'ini al
                Set<Integer> customAreaEdgeIds = customModelCreator.getCustomAreaEdgeIds();
                return customAreaEdgeIds != null && customAreaEdgeIds.contains(edgeId);
            } catch (Exception e) {
                logger.debug("Error checking Custom Area edge {}: {}", edgeId, e.getMessage());
                return false;
            }
        }
        
        @Override
        public double calcMinWeightPerDistance() {
            return baseWeighting.calcMinWeightPerDistance();
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
        public String getName() {
            return "avoidance_only";
        }
        
        @Override
        public String toString() {
            return String.format("AvoidanceOnlyWeighting{avoidEDS=%s, avoidCustomAreas=%s}", 
                               avoidEdsRoads, avoidCustomAreas);
        }
    }
} 