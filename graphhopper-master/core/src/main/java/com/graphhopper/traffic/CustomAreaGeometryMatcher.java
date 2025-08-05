package com.graphhopper.traffic;

import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.util.GeometricShapeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.graphhopper.traffic.EdgeGeometryMatcher.EdgeMatch;

/**
 * Matcher for finding GraphHopper edges that intersect with custom area circles
 * Based on location coordinates and radius (half_diameter)
 */
@Singleton
public class CustomAreaGeometryMatcher {
    private static final Logger logger = LoggerFactory.getLogger(CustomAreaGeometryMatcher.class);
    
    private final BaseGraph graph;
    private final GeometryFactory geometryFactory;
    
    @Inject
    public CustomAreaGeometryMatcher(BaseGraph graph) {
        this.graph = graph;
        this.geometryFactory = new GeometryFactory();
        logger.info("CustomAreaGeometryMatcher initialized");
    }
    
    /**
     * Find all GraphHopper edges that intersect with custom areas
     * @param customAreaData Map of custom area data from API
     * @return List of EdgeMatch objects representing matched edges
     */
    public List<EdgeMatch> findMatchingEdges(Map<String, Map<String, Object>> customAreaData) {
        List<EdgeMatch> allMatches = new ArrayList<>();
        
        for (Map.Entry<String, Map<String, Object>> entry : customAreaData.entrySet()) {
            String areaId = entry.getKey();
            Map<String, Object> areaData = entry.getValue();
            
            try {
                List<EdgeMatch> areaMatches = findMatchingEdgesForArea(areaId, areaData);
                allMatches.addAll(areaMatches);
                
                logger.debug("Custom area '{}' matched {} edges", areaId, areaMatches.size());
                
            } catch (Exception e) {
                logger.warn("Failed to process custom area '{}': {}", areaId, e.getMessage());
            }
        }
        
        logger.info("Total {} edges matched across {} custom areas", 
                   allMatches.size(), customAreaData.size());
        return allMatches;
    }
    
    /**
     * Find edges that intersect with a specific custom area
     */
    private List<EdgeMatch> findMatchingEdgesForArea(String areaId, Map<String, Object> areaData) {
        List<EdgeMatch> matches = new ArrayList<>();
        
        // Parse location coordinates
        Coordinate center = parseLocation(areaData.get("location"));
        if (center == null) {
            logger.warn("Invalid location for custom area '{}': {}", areaId, areaData.get("location"));
            return matches;
        }
        
        // Parse radius
        double radius = parseRadius(areaData.get("half_diameter"));
        if (radius <= 0) {
            logger.warn("Invalid radius for custom area '{}': {}", areaId, areaData.get("half_diameter"));
            return matches;
        }
        
        // Create circle geometry
        Polygon circle = createCircle(center, radius);
        
        // Find intersecting edges
        var edgeIterator = graph.getAllEdges();
        while (edgeIterator.next()) {
            try {
                PointList edgePoints = edgeIterator.fetchWayGeometry(FetchMode.ALL);
                
                if (edgePoints.size() >= 2) {
                    // Check if edge intersects with circle
                    if (edgeIntersectsCircle(edgePoints, circle)) {
                        double matchScore = calculateMatchScore(edgePoints, center, radius);
                        
                        EdgeMatch match = new EdgeMatch(
                            edgeIterator.getEdge(),
                            matchScore
                        );
                        matches.add(match);
                    }
                }
            } catch (Exception e) {
                logger.debug("Error processing edge {}: {}", edgeIterator.getEdge(), e.getMessage());
            }
        }
        
        return matches;
    }
    
    /**
     * Parse location string "lat, lon" to Coordinate
     */
    private Coordinate parseLocation(Object locationObj) {
        if (locationObj == null) return null;
        
        try {
            String location = locationObj.toString().trim();
            String[] parts = location.split(",");
            
            if (parts.length == 2) {
                double lat = Double.parseDouble(parts[0].trim());
                double lon = Double.parseDouble(parts[1].trim());
                
                // Validate coordinates
                if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) {
                    return new Coordinate(lon, lat); // JTS uses (x=lon, y=lat)
                }
            }
        } catch (NumberFormatException e) {
            logger.debug("Failed to parse location: {}", locationObj);
        }
        
        return null;
    }
    
    /**
     * Parse radius from half_diameter field
     */
    private double parseRadius(Object radiusObj) {
        if (radiusObj == null) return 0;
        
        try {
            if (radiusObj instanceof Number) {
                return ((Number) radiusObj).doubleValue();
            } else {
                return Double.parseDouble(radiusObj.toString());
            }
        } catch (NumberFormatException e) {
            logger.debug("Failed to parse radius: {}", radiusObj);
            return 0;
        }
    }
    
    /**
     * Create a circular polygon around the center point
     */
    private Polygon createCircle(Coordinate center, double radiusMeters) {
        GeometricShapeFactory shapeFactory = new GeometricShapeFactory(geometryFactory);
        shapeFactory.setCentre(center);
        
        // Convert meters to approximate degrees (rough approximation)
        // 1 degree ≈ 111,000 meters at equator
        double radiusDegrees = radiusMeters / 111000.0;
        
        shapeFactory.setWidth(radiusDegrees * 2);
        shapeFactory.setHeight(radiusDegrees * 2);
        shapeFactory.setNumPoints(32); // Circle approximation with 32 points
        
        return shapeFactory.createCircle();
    }
    
    /**
     * Check if edge geometry intersects with circle
     */
    private boolean edgeIntersectsCircle(PointList edgePoints, Polygon circle) {
        // Create edge line segments and check intersection
        for (int i = 0; i < edgePoints.size() - 1; i++) {
            double lat1 = edgePoints.getLat(i);
            double lon1 = edgePoints.getLon(i);
            double lat2 = edgePoints.getLat(i + 1);
            double lon2 = edgePoints.getLon(i + 1);
            
            var lineString = geometryFactory.createLineString(new Coordinate[]{
                new Coordinate(lon1, lat1),
                new Coordinate(lon2, lat2)
            });
            
            if (circle.intersects(lineString)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Calculate match score based on distance from center
     */
    private double calculateMatchScore(PointList edgePoints, Coordinate center, double radius) {
        double minDistance = Double.MAX_VALUE;
        
        // Find minimum distance from edge to center
        for (int i = 0; i < edgePoints.size(); i++) {
            double lat = edgePoints.getLat(i);
            double lon = edgePoints.getLon(i);
            
            Point edgePoint = geometryFactory.createPoint(new Coordinate(lon, lat));
            Point centerPoint = geometryFactory.createPoint(center);
            
            double distance = edgePoint.distance(centerPoint) * 111000; // Convert to meters
            minDistance = Math.min(minDistance, distance);
        }
        
        // Score: closer to center = higher score
        return Math.max(0, (radius - minDistance) / radius);
    }
}