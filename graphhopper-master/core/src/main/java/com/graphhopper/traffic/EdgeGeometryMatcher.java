package com.graphhopper.traffic;

import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Matches traffic data LineStrings with GraphHopper edges using spatial operations
 */
public class EdgeGeometryMatcher {
    private static final Logger logger = LoggerFactory.getLogger(EdgeGeometryMatcher.class);
    
    private final BaseGraph graph;
    private final NodeAccess nodeAccess;
    private final GeometryFactory geometryFactory;
    
    // Cache for edge geometries to avoid repeated fetching
    private final Map<Integer, LineString> edgeGeometryCache = new ConcurrentHashMap<>();
    
    // Maximum distance to consider edges as matching (in meters) - STRICT FOR EDS PRECISION
    private static final double MAX_MATCHING_DISTANCE = 50.0;
    
    // Minimum overlap ratio to consider as a match - STRICT FOR EDS PRECISION  
    private static final double MIN_OVERLAP_RATIO = 0.6;
    
    public EdgeGeometryMatcher(BaseGraph graph) {
        this.graph = graph;
        this.nodeAccess = graph.getNodeAccess();
        this.geometryFactory = new GeometryFactory();
    }
    
    /**
     * Find GraphHopper edges that match the given traffic LineString
     * @param trafficLineString LineString from traffic data
     * @return List of matching edge IDs with their match quality scores
     */
    public List<EdgeMatch> findMatchingEdges(LineString trafficLineString) {
        List<EdgeMatch> matches = new ArrayList<>();
        
        // Get approximate bounding box for spatial filtering
        double[] bounds = getBounds(trafficLineString);
        double minLat = bounds[0], minLon = bounds[1], maxLat = bounds[2], maxLon = bounds[3];
        
        // Iterate through all edges and find potential matches
        var allEdges = graph.getAllEdges();
        while (allEdges.next()) {
            try {
                // Quick bounding box check first
                if (isEdgeInBounds(allEdges, minLat, minLon, maxLat, maxLon)) {
                    LineString edgeGeometry = getEdgeLineString(allEdges.getEdge());
                    if (edgeGeometry != null) {
                        double matchScore = calculateMatchScore(trafficLineString, edgeGeometry);
                        if (matchScore > MIN_OVERLAP_RATIO) {
                            matches.add(new EdgeMatch(allEdges.getEdge(), matchScore));
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Error processing edge {}: {}", allEdges.getEdge(), e.getMessage());
            }
        }
        
        // Sort by match score (best matches first)
        matches.sort((a, b) -> Double.compare(b.getMatchScore(), a.getMatchScore()));
        
        // Only log summary - no detailed debug output
        
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
        
        double baseLat = nodeAccess.getLat(baseNode);
        double baseLon = nodeAccess.getLon(baseNode);
        double adjLat = nodeAccess.getLat(adjNode);
        double adjLon = nodeAccess.getLon(adjNode);
        
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
        // Check cache first
        LineString cached = edgeGeometryCache.get(edgeId);
        if (cached != null) {
            return cached;
        }
        
        try {
            EdgeIteratorState edge = graph.getEdgeIteratorState(edgeId, Integer.MIN_VALUE);
            PointList pointList = edge.fetchWayGeometry(FetchMode.ALL);
            
            if (pointList.size() < 2) {
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
            logger.debug("Failed to create LineString for edge {}: {}", edgeId, e.getMessage());
            return null;
        }
    }
    
    /**
     * Calculate match score between traffic LineString and edge LineString
     * Uses both distance and directional similarity
     */
    private double calculateMatchScore(LineString trafficLine, LineString edgeLine) {
        try {
            // Calculate Hausdorff distance (symmetric distance measure)
            double hausdorffDistance = trafficLine.distance(edgeLine);
            
            // Convert to approximate meters (rough approximation)
            double distanceInMeters = hausdorffDistance * 111000; // degrees to meters
            
            if (distanceInMeters > MAX_MATCHING_DISTANCE) {
                return 0.0;
            }
            
            // Distance score (closer is better)
            double distanceScore = Math.max(0.0, 1.0 - (distanceInMeters / MAX_MATCHING_DISTANCE));
            
            // Length similarity score
            double trafficLength = trafficLine.getLength();
            double edgeLength = edgeLine.getLength();
            double lengthRatio = Math.min(trafficLength, edgeLength) / Math.max(trafficLength, edgeLength);
            
            // Direction similarity score using start and end points
            double directionScore = calculateDirectionSimilarity(trafficLine, edgeLine);
            
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
     * Represents a match between traffic data and a GraphHopper edge
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