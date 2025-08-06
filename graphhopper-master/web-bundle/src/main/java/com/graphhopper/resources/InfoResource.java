/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.resources;

import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.StorableProperties;
import com.graphhopper.traffic.TrafficDataService;
import com.graphhopper.util.Constants;
import org.locationtech.jts.geom.Envelope;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.*;
import java.time.Instant;
import com.graphhopper.traffic.TrafficAwareRequestTransformer;
import com.graphhopper.traffic.TrafficAwareCustomModelCreator;
import com.graphhopper.util.CustomModel;

/**
 * @author Peter Karich
 */
@Path("info")
@Produces(MediaType.APPLICATION_JSON)
public class InfoResource {

    private final GraphHopperConfig config;
    private final GraphHopper graphHopper;
    private final BaseGraph baseGraph;
    private final EncodingManager encodingManager;
    private final StorableProperties properties;
    private final boolean hasElevation;
    private final Set<String> privateEV;
    private final TrafficDataService trafficDataService;
    private final TrafficAwareRequestTransformer trafficRequestTransformer;
    private final TrafficAwareCustomModelCreator trafficCustomModelCreator;

    @Inject
    public InfoResource(GraphHopperConfig config, GraphHopper graphHopper, TrafficDataService trafficDataService, 
                       TrafficAwareRequestTransformer trafficRequestTransformer, 
                       TrafficAwareCustomModelCreator trafficCustomModelCreator,
                       @Named("hasElevation") Boolean hasElevation) {
        this.config = config;
        this.graphHopper = graphHopper;
        this.encodingManager = graphHopper.getEncodingManager();
        this.privateEV = new HashSet<>(Arrays.asList(config.getString("graph.encoded_values.private", "").split(",")));
        for (String pEV : privateEV) {
            if (!pEV.isEmpty() && !encodingManager.hasEncodedValue(pEV))
                throw new IllegalArgumentException("A private encoded value does not exist.");
        }
        this.baseGraph = graphHopper.getBaseGraph();
        this.properties = graphHopper.getProperties();
        this.hasElevation = hasElevation;
        this.trafficDataService = trafficDataService;
        this.trafficRequestTransformer = trafficRequestTransformer;
        this.trafficCustomModelCreator = trafficCustomModelCreator;
    }

    public static class Info {
        public static class ProfileData {
            // for deserialization in e.g. tests
            public ProfileData() {
            }

            public ProfileData(String name) {
                this.name = name;
            }

            public String name;
        }

        public Envelope bbox;
        public final List<ProfileData> profiles = new ArrayList<>();
        public String version = Constants.VERSION;
        public boolean elevation;
        public Map<String, List<Object>> encoded_values;
        public String import_date;
        public String data_date;
    }

    @GET
    public Info getInfo() {
        final Info info = new Info();
        info.bbox = new Envelope(baseGraph.getBounds().minLon, baseGraph.getBounds().maxLon, baseGraph.getBounds().minLat, baseGraph.getBounds().maxLat);
        for (Profile p : config.getProfiles()) {
            Info.ProfileData profileData = new Info.ProfileData(p.getName());
            info.profiles.add(profileData);
        }
        if (config.has("gtfs.file"))
            info.profiles.add(new Info.ProfileData("pt"));

        info.elevation = hasElevation;
        info.import_date = properties.get("datareader.import.date");
        info.data_date = properties.get("datareader.data.date");

        List<EncodedValue> evList = encodingManager.getEncodedValues();
        info.encoded_values = new LinkedHashMap<>();
        for (EncodedValue encodedValue : evList) {
            List<Object> possibleValueList = new ArrayList<>();
            String name = encodedValue.getName();
            if (privateEV.contains(name)) {
                continue;
            } else if (encodedValue instanceof EnumEncodedValue) {
                for (Enum o : ((EnumEncodedValue) encodedValue).getValues()) {
                    possibleValueList.add(o.name());
                }
            } else if (encodedValue instanceof BooleanEncodedValue) {
                possibleValueList.add("true");
                possibleValueList.add("false");
            } else if (encodedValue instanceof DecimalEncodedValue || encodedValue instanceof IntEncodedValue) {
                possibleValueList.add(">number");
                possibleValueList.add("<number");
            } else {
                // we only add enum, boolean and numeric encoded values to the list
                continue;
            }
            info.encoded_values.put(name, possibleValueList);
        }
        return info;
    }

    @GET
    @Path("/traffic")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getTrafficInfo() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Basic traffic service status
            result.put("traffic_service_running", trafficRequestTransformer.isTrafficServiceRunning());
            
            // Current EDS road data
            Map<String, TrafficDataService.EdsRoadData> edsData = trafficDataService.getCurrentEdsData();
            result.put("total_eds_entries", edsData.size());
            
            // EDS road details
            List<Map<String, Object>> edsEntries = new ArrayList<>();
            for (Map.Entry<String, TrafficDataService.EdsRoadData> entry : edsData.entrySet()) {
                Map<String, Object> entryInfo = new HashMap<>();
                TrafficDataService.EdsRoadData data = entry.getValue();
                entryInfo.put("road_name", entry.getKey());
                entryInfo.put("coordinates_count", data.getGeometry() != null ? data.getGeometry().getCoordinates().length : 0);
                entryInfo.put("last_updated", data.getTimestamp());
                edsEntries.add(entryInfo);
            }
            result.put("eds_entries", edsEntries);
            
            // Traffic statistics
            TrafficAwareCustomModelCreator.TrafficStats stats = trafficCustomModelCreator.getTrafficStats();
            Map<String, Object> statsInfo = new HashMap<>();
            statsInfo.put("total_edges", stats.getTotalEdges());
            statsInfo.put("heavy_traffic_edges", stats.getHeavyTrafficEdges());
            statsInfo.put("moderate_traffic_edges", stats.getModerateTrafficEdges());
            statsInfo.put("light_traffic_edges", stats.getLightTrafficEdges());
            result.put("traffic_stats", statsInfo);
            
            result.put("timestamp", Instant.now().toString());
            result.put("status", "success");
            
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
            result.put("timestamp", Instant.now().toString());
        }
        
        return result;
    }
    
    @GET
    @Path("/traffic/debug")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getTrafficDebugInfo() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            result.put("traffic_service_running", trafficRequestTransformer.isTrafficServiceRunning());
            
            // Test custom model creation
            Map<String, TrafficDataService.EdsRoadData> edsData = trafficDataService.getCurrentEdsData();
            result.put("current_eds_data_count", edsData.size());
            
            // Try to create a test custom model to see if EDS processing works
            Profile testProfile = graphHopper.getProfile("car");
            if (testProfile != null && !edsData.isEmpty()) {
                // Convert EdsRoadData to TrafficData for compatibility
                Map<String, TrafficDataService.TrafficData> legacyData = trafficDataService.getCurrentTrafficData();
                CustomModel testModel = trafficCustomModelCreator.createTrafficAwareCustomModel(testProfile, legacyData);
                
                Map<String, Object> customModelInfo = new HashMap<>();
                customModelInfo.put("speed_rules_count", testModel.getSpeed().size());
                customModelInfo.put("priority_rules_count", testModel.getPriority().size());
                customModelInfo.put("distance_influence", testModel.getDistanceInfluence());
                customModelInfo.put("speed_rules", testModel.getSpeed());
                customModelInfo.put("priority_rules", testModel.getPriority());
                
                result.put("test_custom_model", customModelInfo);
                result.put("traffic_affected_edges", trafficCustomModelCreator.getTrafficStats().getTotalEdges());
                
                // Get sample edge traffic conditions
                List<Map<String, Object>> sampleEdges = new ArrayList<>();
                for (int i = 0; i < Math.min(10, trafficCustomModelCreator.getTrafficStats().getTotalEdges()); i++) {
                    TrafficAwareCustomModelCreator.TrafficCondition condition = trafficCustomModelCreator.getEdgeTrafficCondition(i);
                    if (condition != null) {
                        Map<String, Object> edgeInfo = new HashMap<>();
                        edgeInfo.put("edge_id", i);
                        edgeInfo.put("traffic_density", condition.getTrafficDensity());
                        edgeInfo.put("speed_factor", condition.getSpeedFactor());
                        edgeInfo.put("priority_factor", condition.getPriorityFactor());
                        edgeInfo.put("match_score", condition.getMatchScore());
                        sampleEdges.add(edgeInfo);
                    }
                }
                result.put("sample_traffic_edges", sampleEdges);
            }
            
            result.put("timestamp", Instant.now().toString());
            result.put("status", "success");
            
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
            result.put("stack_trace", Arrays.toString(e.getStackTrace()));
            result.put("timestamp", Instant.now().toString());
        }
        
        return result;
    }

    public static class TrafficInfo {
        public boolean isTrafficServiceRunning;
        public String trafficApiUrl;
        public int trafficDataCount;
        public List<TrafficSample> sampleData = new ArrayList<>();

        public static class TrafficSample {
            public String roadId;
            public double trafficDensity;
            public double speedLimit;
            public double currentSpeed;
            public double speedFactor;
            public long timestamp;
        }
    }
}
