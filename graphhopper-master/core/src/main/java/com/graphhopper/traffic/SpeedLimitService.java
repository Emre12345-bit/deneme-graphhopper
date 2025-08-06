package com.graphhopper.traffic;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Hız limit verilerini API'den çekip işleyen servis
 */
public class SpeedLimitService {
    private static final Logger logger = LoggerFactory.getLogger(SpeedLimitService.class);
    
    private final String speedLimitApiUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GeometryFactory geometryFactory;
    
    // Cache for speed limit data
    private final Map<String, SpeedLimitData> speedLimitCache = new ConcurrentHashMap<>();
    private long lastCacheUpdate = 0;
    private static final long CACHE_DURATION = 6 * 60 * 60 * 1000; // 6 saat
    
    // Scheduled executor for automatic updates
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private boolean isScheduled = false;
    
    public SpeedLimitService(String speedLimitApiUrl) {
        this.speedLimitApiUrl = speedLimitApiUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.geometryFactory = new GeometryFactory();
        
        // Otomatik güncelleme başlat
        startAutomaticUpdates();
    }
    
    /**
     * 6 saatte bir otomatik güncelleme başlat
     */
    private void startAutomaticUpdates() {
        if (!isScheduled) {
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    logger.info("Otomatik hız limit verisi güncellemesi başlatılıyor...");
                    SpeedLimitApiResponse response = fetchSpeedLimitsFromApi();
                    if (response != null && response.data != null) {
                        processSpeedLimitData(response.data.items);
                        lastCacheUpdate = System.currentTimeMillis();
                        logger.info("✅ SPEED LIMIT UPDATE: Successfully updated speed limit data. Total records: {}", speedLimitCache.size());
                    } else {
                        logger.warn("Otomatik güncelleme sırasında veri alınamadı");
                    }
                } catch (Exception e) {
                    logger.error("Otomatik güncelleme sırasında hata: {}", e.getMessage());
                }
            }, 0, 6, TimeUnit.HOURS); // İlk çalıştırma hemen, sonra 6 saatte bir
            
            isScheduled = true;
            logger.info("Hız limit verisi otomatik güncelleme başlatıldı (6 saatte bir)");
        }
    }
    
    /**
     * Servisi durdur
     */
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("SpeedLimitService kapatıldı");
        }
    }
    
    /**
     * Belirli bir araç tipi için hız limit verilerini getir
     */
    public Map<String, SpeedLimitData> getSpeedLimitsForCarType(int carTypeId) {
        try {
            // Cache'i kontrol et
            if (System.currentTimeMillis() - lastCacheUpdate < CACHE_DURATION) {
                return filterSpeedLimitsByCarType(carTypeId);
            }
            
            // API'den veri çek
            SpeedLimitApiResponse response = fetchSpeedLimitsFromApi();
            if (response != null && response.data != null) {
                processSpeedLimitData(response.data.items);
                lastCacheUpdate = System.currentTimeMillis();
                return filterSpeedLimitsByCarType(carTypeId);
            }
            
        } catch (Exception e) {
            logger.error("Hız limit verileri alınırken hata: {}", e.getMessage());
        }
        
        return new HashMap<>();
    }
    
    /**
     * API'den hız limit verilerini çek
     */
    private SpeedLimitApiResponse fetchSpeedLimitsFromApi() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(speedLimitApiUrl))
                .header("Accept", "application/json")
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            return objectMapper.readValue(response.body(), SpeedLimitApiResponse.class);
        } else {
            logger.error("API yanıt kodu: {}", response.statusCode());
            return null;
        }
    }
    
    /**
     * API yanıtını işle ve cache'e kaydet
     */
    private void processSpeedLimitData(List<SpeedLimitItem> items) {
        speedLimitCache.clear();
        
        for (SpeedLimitItem item : items) {
            try {
                // LineString oluştur
                LineString lineString = createLineStringFromCoordinates(item.linestring.coordinates);
                
                // Her araç tipi için ayrı kayıt oluştur
                for (CarSpeedLimit carSpeed : item.cars) {
                    String key = String.valueOf(item.id) + "_" + carSpeed.car_id;
                    
                    SpeedLimitData speedLimitData = new SpeedLimitData(
                            String.valueOf(item.id),
                            item.title,
                            item.description,
                            lineString,
                            carSpeed.car_id,
                            carSpeed.car_name,
                            carSpeed.speed,
                            item.created_at,
                            item.updated_at
                    );
                    
                    speedLimitCache.put(key, speedLimitData);
                }
                
            } catch (Exception e) {
                logger.warn("Hız limit verisi işlenirken hata (ID: {}): {}", item.id, e.getMessage());
            }
        }
        
        logger.info("📊 SPEED LIMIT PROCESSING: {} speed limit records processed", speedLimitCache.size());
    }
    
    /**
     * Belirli araç tipi için hız limit verilerini filtrele
     */
    private Map<String, SpeedLimitData> filterSpeedLimitsByCarType(int carTypeId) {
        Map<String, SpeedLimitData> filtered = new HashMap<>();
        
        for (SpeedLimitData data : speedLimitCache.values()) {
            if (data.carTypeId == carTypeId) {
                filtered.put(data.roadId, data);
            }
        }
        
        return filtered;
    }
    
    /**
     * Koordinat dizisinden LineString oluştur
     */
    private LineString createLineStringFromCoordinates(List<List<Double>> coordinates) {
        Coordinate[] coords = new Coordinate[coordinates.size()];
        
        for (int i = 0; i < coordinates.size(); i++) {
            List<Double> coord = coordinates.get(i);
            coords[i] = new Coordinate(coord.get(0), coord.get(1)); // lon, lat
        }
        
        return geometryFactory.createLineString(coords);
    }
    
    /**
     * Cache'i temizle
     */
    public void clearCache() {
        speedLimitCache.clear();
        lastCacheUpdate = 0;
        logger.debug("Hız limit cache temizlendi");
    }
    
    /**
     * Cache istatistiklerini getir
     */
    public String getCacheStats() {
        return String.format("Hız limit cache boyutu: %d, Son güncelleme: %d", 
                           speedLimitCache.size(), lastCacheUpdate);
    }
    
    // API Response Models
    public static class SpeedLimitApiResponse {
        public SpeedLimitDataWrapper data;
        public boolean success;
    }
    
    public static class SpeedLimitDataWrapper {
        public List<SpeedLimitItem> items;
        public int total_items;
        public int total_pages;
        public int current_page;
        public int page_size;
        public boolean has_next;
        public boolean has_prev;
    }
    
    public static class SpeedLimitItem {
        public int id;
        public String title;
        public String description;
        public LineStringGeometry linestring;
        public int owner_user_id;
        public String created_at;
        public String updated_at;
        public List<CarSpeedLimit> cars;
    }
    
    public static class LineStringGeometry {
        public List<List<Double>> coordinates;
        public String type;
    }
    
    public static class CarSpeedLimit {
        public int id;
        public int speed_limit_id;
        public int car_id;
        public String car_name;
        public int speed;
    }
    
    /**
     * Hız limit verisi modeli
     */
    public static class SpeedLimitData {
        public final String roadId;
        public final String title;
        public final String description;
        public final LineString geometry;
        public final int carTypeId;
        public final String carTypeName;
        public final int speedLimit;
        public final String createdAt;
        public final String updatedAt;
        
        public SpeedLimitData(String roadId, String title, String description, 
                            LineString geometry, int carTypeId, String carTypeName, 
                            int speedLimit, String createdAt, String updatedAt) {
            this.roadId = roadId;
            this.title = title;
            this.description = description;
            this.geometry = geometry;
            this.carTypeId = carTypeId;
            this.carTypeName = carTypeName;
            this.speedLimit = speedLimit;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
        
        @Override
        public String toString() {
            return String.format("SpeedLimitData{roadId='%s', carType='%s', speed=%d}", 
                               roadId, carTypeName, speedLimit);
        }
    }
} 