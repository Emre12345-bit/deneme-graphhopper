package com.graphhopper.traffic;

import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hız limit verilerini GraphHopper edge'leri ile eşleştiren sınıf
 */
public class SpeedLimitMatcher {
    private static final Logger logger = LoggerFactory.getLogger(SpeedLimitMatcher.class);
    
    private final BaseGraph graph;
    private final GeometryFactory geometryFactory;
    
    // Cache for edge geometries
    private final Map<Integer, LineString> edgeGeometryCache = new ConcurrentHashMap<>();
    
    // Maximum distance to consider edges as matching (in meters)
    private static final double MAX_MATCHING_DISTANCE = 30.0;
    
    // Minimum overlap ratio to consider as a match
    private static final double MIN_OVERLAP_RATIO = 0.7;
    
    public SpeedLimitMatcher(BaseGraph graph) {
        this.graph = graph;
        this.geometryFactory = new GeometryFactory();
    }
    
    /**
     * Hız limit verilerini edge'ler ile eşleştir
     */
    public Map<Integer, SpeedLimitService.SpeedLimitData> matchSpeedLimitsToEdges(Map<String, SpeedLimitService.SpeedLimitData> speedLimits) {
        Map<Integer, SpeedLimitService.SpeedLimitData> edgeSpeedLimits = new HashMap<>();
        
        int matchedCount = 0;
        int totalSpeedLimits = speedLimits.size();
        
        for (SpeedLimitService.SpeedLimitData speedLimit : speedLimits.values()) {
            try {
                List<EdgeMatch> matchingEdges = findMatchingEdges(speedLimit.geometry);
                
                for (EdgeMatch edgeMatch : matchingEdges) {
                    if (edgeMatch.getMatchScore() > MIN_OVERLAP_RATIO) {
                        edgeSpeedLimits.put(edgeMatch.getEdgeId(), speedLimit);
                        matchedCount++;
                    }
                }
                
            } catch (Exception e) {
                logger.debug("Hız limit eşleştirme hatası (ID: {}): {}", speedLimit.roadId, e.getMessage());
            }
        }
        
        logger.info("Hız limit eşleştirme tamamlandı: {}/{} eşleşme bulundu", matchedCount, totalSpeedLimits);
        
        return edgeSpeedLimits;
    }
    
    /**
     * Belirli bir edge için hız limit verisi getir
     */
    public SpeedLimitService.SpeedLimitData getSpeedLimitForEdge(int edgeId, Map<Integer, SpeedLimitService.SpeedLimitData> edgeSpeedLimits) {
        return edgeSpeedLimits.get(edgeId);
    }
    
    /**
     * Edge için hız limit değerini hesapla (km/h)
     */
    public int getSpeedLimitValue(int edgeId, Map<Integer, SpeedLimitService.SpeedLimitData> edgeSpeedLimits) {
        SpeedLimitService.SpeedLimitData speedLimit = edgeSpeedLimits.get(edgeId);
        return speedLimit != null ? speedLimit.speedLimit : -1; // -1 = hız limiti yok
    }
    
    /**
     * GraphHopper edge'leri ile eşleşen hız limit verilerini bul
     */
    private List<EdgeMatch> findMatchingEdges(LineString speedLimitLineString) {
        List<EdgeMatch> matches = new ArrayList<>();
        
        // Get approximate bounding box for spatial filtering
        double[] bounds = getBounds(speedLimitLineString);
        double minLat = bounds[0], minLon = bounds[1], maxLat = bounds[2], maxLon = bounds[3];
        
        // Get graph edge count for validation
        int maxEdgeId = graph.getEdges();
        logger.debug("Processing speed limit matching with graph edge count: {}", maxEdgeId);
        
        // Iterate through all edges and find potential matches
        var allEdges = graph.getAllEdges();
        while (allEdges.next()) {
            try {
                int edgeId = allEdges.getEdge();
                
                // Edge ID geçerliliğini kontrol et
                if (edgeId < 0 || edgeId >= maxEdgeId) {
                    logger.warn("Invalid edge ID encountered: {} (max: {})", edgeId, maxEdgeId - 1);
                    continue;
                }
                
                // Quick bounding box check first
                if (isEdgeInBounds(allEdges, minLat, minLon, maxLat, maxLon)) {
                    LineString edgeGeometry = getEdgeLineString(edgeId);
                    if (edgeGeometry != null) {
                        double matchScore = calculateMatchScore(speedLimitLineString, edgeGeometry);
                        if (matchScore > MIN_OVERLAP_RATIO) {
                            matches.add(new EdgeMatch(edgeId, matchScore));
                            logger.debug("🔗 EDGE MATCH: Found matching edge {} with score: {}", edgeId, matchScore);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Error processing edge {}: {}", allEdges.getEdge(), e.getMessage());
            }
        }
        
        // Sort by match score (best matches first)
        matches.sort((a, b) -> Double.compare(b.getMatchScore(), a.getMatchScore()));
        
        logger.info("Found {} matching edges for speed limit", matches.size());
        
        return matches;
    }
    
    /**
     * Get bounding box coordinates from LineString
     */
    private double[] getBounds(LineString lineString) {
        Coordinate[] coords = lineString.getCoordinates();
        double minLat = Double.MAX_VALUE, minLon = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        
        for (Coordinate coord : coords) {
            minLat = Math.min(minLat, coord.y);
            maxLat = Math.max(maxLat, coord.y);
            minLon = Math.min(minLon, coord.x);
            maxLon = Math.max(maxLon, coord.x);
        }
        
        // Add small buffer
        double latBuffer = (maxLat - minLat) * 0.1 + 0.001;
        double lonBuffer = (maxLon - minLon) * 0.1 + 0.001;
        
        return new double[]{
            minLat - latBuffer, minLon - lonBuffer,
            maxLat + latBuffer, maxLon + lonBuffer
        };
    }
    
    /**
     * Check if edge is within the given bounds
     */
    private boolean isEdgeInBounds(EdgeIteratorState edge, double minLat, double minLon, double maxLat, double maxLon) {
        int baseNode = edge.getBaseNode();
        int adjNode = edge.getAdjNode();
        
        double baseLat = graph.getNodeAccess().getLat(baseNode);
        double baseLon = graph.getNodeAccess().getLon(baseNode);
        double adjLat = graph.getNodeAccess().getLat(adjNode);
        double adjLon = graph.getNodeAccess().getLon(adjNode);
        
        // Check if any node is within bounds
        return (baseLat >= minLat && baseLat <= maxLat && baseLon >= minLon && baseLon <= maxLon) ||
               (adjLat >= minLat && adjLat <= maxLat && adjLon >= minLon && adjLon <= maxLon) ||
               // Or if the edge crosses the bounds
               (Math.min(baseLat, adjLat) <= maxLat && Math.max(baseLat, adjLat) >= minLat &&
                Math.min(baseLon, adjLon) <= maxLon && Math.max(baseLon, adjLon) >= minLon);
    }
    
    /**
     * Convert GraphHopper edge to JTS LineString
     */
    private LineString getEdgeLineString(int edgeId) {
        // Edge ID geçerliliğini kontrol et
        int maxEdgeId = graph.getEdges();
        if (edgeId < 0 || edgeId >= maxEdgeId) {
            logger.warn("Invalid edge ID in getEdgeLineString: {} (max: {})", edgeId, maxEdgeId - 1);
            return null;
        }
        
        // Check cache first
        LineString cached = edgeGeometryCache.get(edgeId);
        if (cached != null) {
            return cached;
        }
        
        try {
            EdgeIteratorState edge = graph.getEdgeIteratorState(edgeId, Integer.MIN_VALUE);
            
            // Edge'in geçerli olup olmadığını kontrol et
            if (edge == null) {
                logger.warn("Edge {} does not exist in graph", edgeId);
                return null;
            }
            
            PointList pointList = edge.fetchWayGeometry(FetchMode.ALL);
            
            if (pointList.size() < 2) {
                logger.debug("Edge {} has insufficient geometry points: {}", edgeId, pointList.size());
                return null;
            }
            
            Coordinate[] coordinates = new Coordinate[pointList.size()];
            for (int i = 0; i < pointList.size(); i++) {
                coordinates[i] = new Coordinate(pointList.getLon(i), pointList.getLat(i));
            }
            
            LineString lineString = geometryFactory.createLineString(coordinates);
            
            // Cache the result
            edgeGeometryCache.put(edgeId, lineString);
            
            return lineString;
            
        } catch (Exception e) {
            logger.warn("Failed to create LineString for edge {}: {}", edgeId, e.getMessage());
            return null;
        }
    }
    
    /**
     * Calculate match score between speed limit LineString and edge LineString
     */
    private double calculateMatchScore(LineString speedLimitLine, LineString edgeLine) {
        try {
            // Calculate Hausdorff distance (symmetric distance measure)
            double hausdorffDistance = speedLimitLine.distance(edgeLine);
            
            // Convert to approximate meters (rough approximation)
            double distanceInMeters = hausdorffDistance * 111000; // degrees to meters
            
            if (distanceInMeters > MAX_MATCHING_DISTANCE) {
                return 0.0;
            }
            
            // Distance score (closer is better)
            double distanceScore = Math.max(0.0, 1.0 - (distanceInMeters / MAX_MATCHING_DISTANCE));
            
            // Length similarity score
            double speedLimitLength = speedLimitLine.getLength();
            double edgeLength = edgeLine.getLength();
            double lengthRatio = Math.min(speedLimitLength, edgeLength) / Math.max(speedLimitLength, edgeLength);
            
            // Direction similarity score using start and end points
            double directionScore = calculateDirectionSimilarity(speedLimitLine, edgeLine);
            
            // Combine scores with weights
            double finalScore = (distanceScore * 0.4) + (lengthRatio * 0.3) + (directionScore * 0.3);
            
            return Math.min(1.0, finalScore);
            
        } catch (Exception e) {
            logger.debug("Error calculating match score: {}", e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Calculate direction similarity between two LineStrings
     */
    private double calculateDirectionSimilarity(LineString line1, LineString line2) {
        try {
            Coordinate[] coords1 = line1.getCoordinates();
            Coordinate[] coords2 = line2.getCoordinates();
            
            if (coords1.length < 2 || coords2.length < 2) {
                return 0.5; // Neutral score for insufficient data
            }
            
            // Calculate direction vectors
            double dx1 = coords1[coords1.length - 1].x - coords1[0].x;
            double dy1 = coords1[coords1.length - 1].y - coords1[0].y;
            double dx2 = coords2[coords2.length - 1].x - coords2[0].x;
            double dy2 = coords2[coords2.length - 1].y - coords2[0].y;
            
            // Normalize vectors
            double len1 = Math.sqrt(dx1 * dx1 + dy1 * dy1);
            double len2 = Math.sqrt(dx2 * dx2 + dy2 * dy2);
            
            if (len1 == 0 || len2 == 0) {
                return 0.5; // Neutral score for zero-length vectors
            }
            
            dx1 /= len1; dy1 /= len1;
            dx2 /= len2; dy2 /= len2;
            
            // Calculate dot product (cosine of angle between vectors)
            double dotProduct = dx1 * dx2 + dy1 * dy2;
            
            // Consider both same direction and opposite direction as valid matches
            return Math.abs(dotProduct);
            
        } catch (Exception e) {
            return 0.5; // Neutral score on error
        }
    }
    
    /**
     * Clear the geometry cache to free memory
     */
    public void clearCache() {
        edgeGeometryCache.clear();
        logger.debug("Cleared edge geometry cache");
    }
    
    /**
     * Get cache statistics
     */
    public String getCacheStats() {
        return String.format("Edge geometry cache size: %d", edgeGeometryCache.size());
    }
    
    /**
     * Represents a match between speed limit data and a GraphHopper edge
     */
    public static class EdgeMatch {
        private final int edgeId;
        private final double matchScore;
        
        public EdgeMatch(int edgeId, double matchScore) {
            this.edgeId = edgeId;
            this.matchScore = matchScore;
        }
        
        public int getEdgeId() {
            return edgeId;
        }
        
        public double getMatchScore() {
            return matchScore;
        }
        
        @Override
        public String toString() {
            return String.format("EdgeMatch{edgeId=%d, score=%.3f}", edgeId, matchScore);
        }
    }
} 