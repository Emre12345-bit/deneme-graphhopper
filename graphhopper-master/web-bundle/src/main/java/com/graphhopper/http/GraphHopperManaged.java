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

package com.graphhopper.http;

import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.TrafficAwareGraphHopper;
import com.graphhopper.gtfs.GraphHopperGtfs;
import com.graphhopper.traffic.TrafficAwareCustomModelCreator;
import com.graphhopper.traffic.CustomAreaDataService;
import com.graphhopper.traffic.CustomAreaGeometryMatcher;
import io.dropwizard.lifecycle.Managed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GraphHopperManaged implements Managed {

    private final static Logger logger = LoggerFactory.getLogger(GraphHopperManaged.class);
    private final GraphHopper graphHopper;
    private final GraphHopperConfig configuration;

    public GraphHopperManaged(GraphHopperConfig configuration) {
        this.configuration = configuration;
        if (configuration.has("gtfs.file")) {
            graphHopper = new GraphHopperGtfs(configuration);
        } else {
            graphHopper = new TrafficAwareGraphHopper();
        }
        graphHopper.init(configuration);
    }
    
    public void setTrafficAwareCustomModelCreator(TrafficAwareCustomModelCreator customModelCreator) {
        if (graphHopper instanceof TrafficAwareGraphHopper) {
            ((TrafficAwareGraphHopper) graphHopper).setTrafficAwareCustomModelCreator(customModelCreator);
        }
    }

    @Override
    public void start() {
        graphHopper.importOrLoad();
        logger.info("loaded graph at:{}, data_reader_file:{}, encoded values:{}, {} bytes for edge flags, {}",
                graphHopper.getGraphHopperLocation(), graphHopper.getOSMFile(),
                graphHopper.getEncodingManager().toEncodedValuesAsString(),
                graphHopper.getEncodingManager().getBytesForFlags(),
                graphHopper.getBaseGraph().toDetailsString());
        
        // TrafficAwareCustomModelCreator will be initialized by HK2 dependency injection
        // after GraphHopper is loaded and all services are available
        logger.info("GraphHopper loaded successfully - services will be initialized by HK2");
    }

    public GraphHopper getGraphHopper() {
        return graphHopper;
    }

    @Override
    public void stop() {
        graphHopper.close();
    }


}
