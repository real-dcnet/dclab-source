package org.onos.dclab;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import org.apache.felix.scr.annotations.*;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;
import org.onosproject.app.ApplicationAdminService;
import org.onosproject.core.CoreService;
import org.onosproject.net.Device;
import org.onosproject.net.config.NetworkConfigService;
import org.onosproject.net.config.basics.BasicDeviceConfig;
import org.onosproject.net.device.DeviceAdminService;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.link.LinkAdminService;
import org.onosproject.net.topology.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

/**
 * ONOS App implementing DCLab forwarding scheme.
 */
@Component(immediate = true)
public class DClab {
    /** Logs information, errors, and warnings during runtime. */
    private static Logger log = LoggerFactory.getLogger(DClab.class);

    /** Service used to manage flow rules installed on switches. */
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private CoreService coreService;

    /** Service used to manage flow rules installed on switches. */
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private DeviceService deviceService;

    /** Service used to manage flow rules installed on switches. */
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private TopologyService topologyService;

    /** Service used to manage flow rules installed on switches. */
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private NetworkConfigService networkService;

    /** Service used to manage flow rules installed on switches. */
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private DeviceAdminService deviceAdminService;

    /** Service used to manage flow rules installed on switches. */
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private LinkAdminService linkAdminService;

    /** Service used to activate and deactivate application. */
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private ApplicationAdminService applicationAdminService;

    private static String configLoc =
            System.getProperty("user.home") + "/dclab-source/config/dclab/";

    private static String switchConfigLoc =
            System.getProperty("user.home") + "/dclab-source/config/mininet/";


    public static class QueueEntry implements Comparable<QueueEntry> {
        private int key;
        private int value;

        public QueueEntry(int key, int value) {
            this.key = key;
            this.value = value;
        }

        private int getKey() {
            return this.key;
        }

        private int getValue() {
            return this.value;
        }

        public int compareTo(QueueEntry entry) {
            return this.key - entry.getKey();
        }
    }

    /** Holds information about switches parsed from JSON. */
    private static final class SwitchEntry {
        /** Human readable name for the switch. */
        private String name;

        /** Physical location of switch in topology representation. */
        private double latitude;
        private double longitude;

        private SwitchEntry(final String switchName,
                            final double latitude,
                            final double longitude) {
            this.name = switchName;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        private String getName() {
            return this.name;
        }

        private double getLatitude() {
            return this.latitude;
        }

        private double getLongitude() {
            return this.longitude;
        }
    }

    /** Maps Chassis ID to a switch entry. */
    private Map<String, SwitchEntry> switchDB = new TreeMap<>();

    /** Initialize structure needed to store and access location information for switches in network */
    private void init() {
        switchDB = new TreeMap<>();

        try {
            /* Setup switch database by reading fields in switch config JSON */
            JsonArray configArray = Json.parse(new BufferedReader(
                    new FileReader(switchConfigLoc + "switch_config.json"))
            ).asArray();

            for (JsonValue obj : configArray) {
                JsonObject config = obj.asObject();
                SwitchEntry entry = new SwitchEntry(
                        config.get("name").asString(),
                        config.get("latitude").asDouble(),
                        config.get("longitude").asDouble());
                switchDB.put(config.get("id").asString(), entry);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Allows application to be started by ONOS controller. */
    @Activate
    public void activate() {
        init();
        coreService.registerApplication("org.onosproject.dclab");
        for (Device d : deviceService.getAvailableDevices()) {
            setLocation(d);
        }
        try {
            /* Deactivate LLDP Provider to prevent interference with DClab */
            applicationAdminService.deactivate(applicationAdminService.getId("org.onosproject.lldpprovider"));
            Thread.sleep(5000);
            analyzeTopology();
        }
        catch (InterruptedException e){
            applicationAdminService.activate(applicationAdminService.getId("org.onosproject.lldpprovider"));
        }
        log.info("Started");
    }

    /** Allows application to be stopped by ONOS controller. */
    @Deactivate
    public void deactivate() {
        /* Reactivate LLDP Provider so that links removed by DClab can be restored */
        applicationAdminService.activate(applicationAdminService.getId("org.onosproject.lldpprovider"));
        log.info("Stopped");
    }

    /**
     * Register location information for a connected device to ONOS so that it can be displayed in gui
     * @param device    Switch that has been connected
     */
    private synchronized void setLocation(final Device device) {
        String id = device.chassisId().toString();
        log.info("Chassis " + id + " connected");
        if (switchDB.containsKey(id)) {
            SwitchEntry entry = switchDB.get(id);
            BasicDeviceConfig cfg = networkService.addConfig(device.id(), BasicDeviceConfig.class);

            cfg.name(entry.getName());
            cfg.latitude(40.0 * entry.getLatitude());
            cfg.longitude(40.0 * entry.getLongitude());
            cfg.apply();
        }
    }

    /** Main logic for DClab that parses a configuration file and applies an overlay */
    private void analyzeTopology() {
        Topology topo = topologyService.currentTopology();
        TopologyGraph topoGraph = topologyService.getGraph(topo);
        Graph<TopologyVertex, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);

        /* Copy ONOS internal graph of topology into a JGraphT graph */
        for (TopologyVertex v : topoGraph.getVertexes()) {
            graph.addVertex(v);
        }
        for (TopologyEdge e : topoGraph.getEdges()) {
            if (DijkstraShortestPath.findPathBetween(graph, e.src(), e.dst()) == null) {
                graph.addEdge(e.src(), e.dst());
            }
        }
        log.info(graph.toString());

        try {
            JsonArray config = Json.parse(new BufferedReader(
                    new FileReader(configLoc + "test_config.json"))
            ).asArray();
            List<Graph<TopologyVertex, DefaultEdge>> allTopos = new ArrayList<>();

            /* Iterate through each subgraph specified in configuration file */
            for (JsonValue obj : config) {
                JsonObject spec = obj.asObject();
                String type = spec.get("type").asString();
                List<Graph<TopologyVertex, DefaultEdge>> topos = new ArrayList<>();
                int count;

                /* Parse parameters based on type of subgraph specified */
                switch (type) {
                    case "linear":
                        int length = spec.getInt("length", 3);
                        count = spec.getInt("count", 1000);
                        topos = createLinearTopos(graph, length, count);
                        break;
                    case "star":
                        int points = spec.getInt("points", 3);
                        count = spec.getInt("count", 1000);
                        topos = createStarTopos(graph, points, count);
                        break;
                    case "tree":
                        int depth = spec.getInt("depth", 3);
                        int fanout = spec.getInt("fanout", 2);
                        count = spec.getInt("count", 1000);
                        topos = createTreeTopos(graph, depth, fanout, count);
                        break;
                    case "clos":
                        int spines = spec.getInt("spines", 2);
                        int leaves = spec.getInt("leaves", 4);
                        count = spec.getInt("count", 1000);
                        break;
                    default:
                        log.info("Invalid topology type");
                        topos = new ArrayList<>();
                }
                allTopos.addAll(topos);
                log.info(topos.toString());

                /* Remove used nodes from graph so that they aren't used in another subgraph */
                removeSubTopology(graph, topos);
            }
            disablePorts(topoGraph, allTopos);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Disables links in the topologies that aren't in any of the overlaid topologies
     * @param graphOld  Graph representing the previously active network topology
     * @param graphNew  List of graphs representing the new topologies about to be overlaid
     */
    private void disablePorts(TopologyGraph graphOld, List<Graph<TopologyVertex, DefaultEdge>> graphNew) {
        for (TopologyVertex v : graphOld.getVertexes()) {
            boolean exit = false;
            for (Graph<TopologyVertex, DefaultEdge> g : graphNew) {
                for (TopologyVertex u : g.vertexSet()) {
                    if (v.equals(u)) {
                        /* Check each edge in the previous network to see which should be active in overlaid network */
                        for (TopologyEdge e : graphOld.getEdgesFrom(v)) {
                            boolean exitTwo = false;
                            /* Check for edges in overlaid network where source is current node */
                            for (DefaultEdge f : g.outgoingEdgesOf(u)) {
                                if (e.dst().equals(g.getEdgeTarget(f))) {
                                    exitTwo = true;
                                    break;
                                }
                            }
                            if (!exitTwo) {
                                /* Check for edges in overlaid network where destination is current node */
                                for (DefaultEdge f : g.incomingEdgesOf(u)) {
                                    if (e.dst().equals(g.getEdgeSource(f))) {
                                        exitTwo = true;
                                        break;
                                    }
                                }
                            }
                            if (!exitTwo) {
                                /* Disable edge if no edge in overlaid network matches */
                                linkAdminService.removeLink(e.link().src(), e.link().dst());
                            }
                        }
                        exit = true;
                        break;
                    }
                }
                if (exit) {
                    break;
                }
            }
            if (!exit) {
                /* Disable all edges for nodes not in overlaid network */
                linkAdminService.removeLinks(v.deviceId());
            }
        }
    }

    /**
     * Removes all of the nodes and edges contained in topos from graph
     * @param graph Graph being modified for use later
     * @param topos List of graphs where each node and edge is to be removed from graph
     */
    private void removeSubTopology(Graph<TopologyVertex, DefaultEdge> graph, List<Graph<TopologyVertex, DefaultEdge>> topos) {
        for (Graph<TopologyVertex, DefaultEdge> t : topos) {
            for (DefaultEdge e : t.edgeSet()) {
                for (DefaultEdge f : graph.edgeSet()) {
                    if (t.getEdgeSource(e).equals(graph.getEdgeSource(f)) &&
                            t.getEdgeTarget(e).equals(graph.getEdgeTarget(f))) {
                        graph.removeEdge(f);
                        break;
                    }
                }
            }
            for (TopologyVertex v : t.vertexSet()) {
                graph.removeVertex(v);
            }
        }
    }

    /**
     * Trims excess nodes from a subgraph to better fit an overlay
     * @param graph Original graph that overlay is constructed from
     * @param nodes Nodes in the overlay that is being created
     * @param edges Edges in the overlay that is being created
     * @param trims Number of trims that need to be performed
     * @param cut   True if an entire path of nodes should be removed for each trim,
     *              false if all but one needs to be removed
     */
    private void trimEdges(Graph<TopologyVertex, DefaultEdge> graph, List<TopologyVertex> nodes, List<DefaultEdge> edges, int trims, boolean cut) {
        /* Check if topology is linear (trim algorithm won't work) */
        if (!cut && trims < 3) {
            return;
        }

        /* Create map from each vertex to a list of neighbors */
        Map<TopologyVertex, List<TopologyVertex>> outgoingEdges = new HashMap<>();
        for (TopologyVertex v : nodes) {
            outgoingEdges.put(v, new ArrayList<>());
        }
        for (DefaultEdge e : edges) {
            outgoingEdges.get(graph.getEdgeSource(e)).add(graph.getEdgeTarget(e));
            outgoingEdges.get(graph.getEdgeTarget(e)).add(graph.getEdgeSource(e));
        }

        /* Resulting vertex and edge list after trim */
        List<TopologyVertex> trimmedVertices = new ArrayList<>();
        List<DefaultEdge> trimmedEdges = new ArrayList<>();

        int counter = 0;
        for (TopologyVertex v : outgoingEdges.keySet()) {

            /* Check for edges with only one outgoing edge to start trim */
            if (outgoingEdges.get(v).size() == 1) {
                TopologyVertex u = outgoingEdges.get(v).get(0);
                /* Remove nodes and edges until first node with at least 3 outgoing edges is encountered */
                if (cut) {
                    trimmedVertices.add(v);
                    while (outgoingEdges.get(u).size() == 2) {
                        trimmedVertices.add(u);
                        trimmedEdges.add(graph.getEdge(v, u));
                        TopologyVertex old = v;
                        v = u;
                        u = outgoingEdges.get(v).get(0);
                        if (u == old) {
                            u = outgoingEdges.get(v).get(1);
                        }
                    }
                    trimmedEdges.add(graph.getEdge(v, u));
                }
                /* Remove nodes and edges until node just before first node with at least 3 outgoing edges */
                else if (outgoingEdges.get(u).size() == 2) {
                    trimmedVertices.add(v);
                    while (true) {
                        TopologyVertex old = v;
                        v = u;
                        u = outgoingEdges.get(v).get(0);
                        if (u == old) {
                            u = outgoingEdges.get(v).get(1);
                        }
                        if (outgoingEdges.get(u).size() == 2) {
                            trimmedVertices.add(v);
                            trimmedEdges.add(graph.getEdge(old, v));
                        }
                        else {
                            u = v;
                            v = old;
                            break;
                        }
                    }
                    trimmedEdges.add(graph.getEdge(v, u));
                }
                counter++;
            }
            if (counter == trims) {
                break;
            }
        }
        for (TopologyVertex v : trimmedVertices) {
            nodes.remove(v);
        }
        for (DefaultEdge e : trimmedEdges) {
            for (DefaultEdge f : edges) {
                if (graph.getEdgeSource(e).equals(graph.getEdgeSource(f)) &&
                        graph.getEdgeTarget(e).equals(graph.getEdgeTarget(f)))  {
                    edges.remove(f);
                    break;
                }
            }
        }
    }

    /**
     * Create linear topologies using parameters supplied in configuration file
     * @param graph     Graph that overlays are being constructed from
     * @param length    Number of nodes in each linear topology being overlayed
     * @param count     Number of linear topologies to overlay
     * @return          List of count linear topologies, each with specified length
     */
    private List<Graph<TopologyVertex, DefaultEdge>> createLinearTopos(Graph<TopologyVertex, DefaultEdge> graph, int length, int count) {
        /* Repeatedly use longest path to segment graph until longest path is of specified length or less */
        while(true) {
            int max = 0;
            GraphPath longest = null;

            /* Search graph for longest path */
            for (TopologyVertex v : graph.vertexSet()) {
                for (TopologyVertex u : graph.vertexSet()) {
                    GraphPath path = DijkstraShortestPath.findPathBetween(graph, v, u);
                    if (path != null && path.getLength() > max) {
                        max = path.getLength();
                        longest = path;
                    }
                }
            }
            if(max <= length || longest == null) {
                break;
            }
            int counter = 1;

            /* Segment longest path into linear topologies */
            for(Object e : longest.getEdgeList()) {
                if(counter == length) {
                    graph.removeEdge((DefaultEdge) e);
                    counter = 1;
                }
                else {
                    counter++;
                }
            }
        }
        List<Graph<TopologyVertex, DefaultEdge>> topos = new ArrayList<>();
        List<TopologyVertex> addedVertices = new ArrayList<>();
        int counter = 0;

        // TODO: Can probably do this during the while loop instead
        for (TopologyVertex v : graph.vertexSet()) {
            for (TopologyVertex u : graph.vertexSet()) {
                GraphPath path = DijkstraShortestPath.findPathBetween(graph, v, u);
                /* Check if path is long enough for linear topology */
                if (path != null && path.getLength() == length - 1) {
                    boolean exit = false;

                    /* Make sure nodes in path haven't been used already */
                    for (Object k : path.getVertexList()) {
                        if (addedVertices.contains((TopologyVertex) k)) {
                            exit = true;
                            break;
                        }
                    }
                    if (exit) {
                        continue;
                    }

                    /* Construct graph using nodes in path as a linear topology */
                    Graph<TopologyVertex, DefaultEdge> topo = new SimpleGraph<>(DefaultEdge.class);
                    for (Object x : path.getVertexList()) {
                        addedVertices.add((TopologyVertex) x);
                        topo.addVertex((TopologyVertex) x);
                    }
                    for (Object e : path.getEdgeList()) {
                        DefaultEdge edge = (DefaultEdge) e;
                        topo.addEdge(graph.getEdgeSource(edge), graph.getEdgeTarget(edge));
                    }
                    topos.add(topo);
                    counter++;
                }
                if (counter >= count) {
                    return topos;
                }
            }
        }
        return topos;
    }

    /**
     * Calculate closest pairwise distances between components, and the vertices with that distance
     * @param partitions    Current state of the network graph
     * @param components    Components being analyzed for distances
     * @param compDist      List to store pairwise distance between components
     * @param closestVert   List to store vertex tuples that are closest between components.
     *                      First vertex in tuple is in source component, second is in destination component
     * @param minDist       Minimum distance allowed between components
     */
    private void calculateComponentDistances(Graph<TopologyVertex, DefaultEdge> partitions,
                                             List<List<TopologyVertex>> components, List<List<Integer>> compDist,
                                             List<List<List<TopologyVertex>>> closestVert, int minDist) {

        /* Initialize component distance and closest vertex maps */
        for (int i = 0; i < components.size(); i++) {
            compDist.add(new ArrayList<>());
            closestVert.add(new ArrayList<>());
            for (int j = 0; j < components.size(); j++) {
                compDist.get(i).add(Integer.MAX_VALUE);
                closestVert.get(i).add(new ArrayList<>());
            }
        }

        /* Prevent components with less than minDist nodes between them from being added to queue */
        List<List<Integer>> blacklist = new ArrayList<>();
        if (minDist > 0) {
            for (int i = 0; i < components.size() - 1; i++) {
                blacklist.add(new ArrayList<>());
                for (int j = i + 1; j < components.size(); j++) {
                    boolean flag = false;
                    for (TopologyVertex v : components.get(i)) {
                        for (TopologyVertex u : components.get(j)) {
                            GraphPath path = DijkstraShortestPath.findPathBetween(partitions, v, u);
                            if (path == null) {
                                continue;
                            }
                            int dist = path.getLength();

                            /* Don't allow any path between two components if they are less than min distance apart */
                            if (dist < minDist) {
                                blacklist.get(i).add(j);
                                flag = true;
                                break;
                            }
                        }
                        if (flag) {
                            break;
                        }
                    }
                }
            }
        }

        /* Find shortest distance between all pairs of components */
        for (int i = 0; i < components.size() - 1; i++) {
            for (int j = i + 1; j < components.size(); j++) {
                if (minDist > 0 && blacklist.get(i).contains(j)) {
                    continue;
                }
                for (TopologyVertex v : components.get(i)) {
                    for (TopologyVertex u : components.get(j)) {
                        GraphPath path = DijkstraShortestPath.findPathBetween(partitions, v, u);
                        if (path == null) {
                            continue;
                        }
                        int dist = path.getLength();

                        /* Update distance for component i if new distance is closer than previously known */
                        if (dist < compDist.get(i).get(j)) {
                            compDist.get(i).set(j, dist);
                            if (closestVert.get(i).get(j).size() > 0) {
                                closestVert.get(i).get(j).set(0, v);
                                closestVert.get(i).get(j).set(1, u);
                            }
                            else {
                                closestVert.get(i).get(j).add(v);
                                closestVert.get(i).get(j).add(u);
                            }
                        }

                        /* Update distance for component j if new distance is closer than previously known */
                        if (dist < compDist.get(j).get(i)) {
                            compDist.get(j).set(i, dist);
                            if (closestVert.get(j).get(i).size() > 0) {
                                closestVert.get(j).get(i).set(0, u);
                                closestVert.get(j).get(i).set(1, v);
                            }
                            else {
                                closestVert.get(j).get(i).add(u);
                                closestVert.get(j).get(i).add(v);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Initializes the component list with vertices that have one outgoing edge
     * @param graph         Current graph representing the network
     * @param components    Stores the newly created components
     * @param compEdges     Holds an initially empty list of edges for each created component
     * @param pointList     Tracks number of points in each component (like in star topology), initially 1
     */
    private void initializeComponents(Graph<TopologyVertex, DefaultEdge> graph, List<List<TopologyVertex>> components,
                                          List<List<DefaultEdge>> compEdges, List<Integer> pointList) {
        for (TopologyVertex v : graph.vertexSet()) {
            if (graph.degreeOf(v) == 1) {
                List<TopologyVertex> component = new ArrayList<>();
                component.add(v);
                components.add(component);
                compEdges.add(new ArrayList<>());
                pointList.add(1);
            }
        }
    }

    /**
     * Creates a copy of the input graph
     * @param graph Graph to copy
     * @return      New graph with same vertex references and new edges as input graph
     */
    private Graph<TopologyVertex, DefaultEdge> copyGraph(Graph<TopologyVertex, DefaultEdge> graph) {
        Graph<TopologyVertex, DefaultEdge> partitions = new SimpleGraph<>(DefaultEdge.class);
        for (TopologyVertex v : graph.vertexSet()) {
            partitions.addVertex(v);
        }
        for (DefaultEdge e : graph.edgeSet()) {
            partitions.addEdge(graph.getEdgeSource(e), graph.getEdgeTarget(e));
        }
        return partitions;
    }

    /**
     * Creates a merged component and removes it from the current graph
     * @param minI          Index of source component being merged
     * @param minJ          Index of destination component being merged
     * @param partitions    Current graph of the network
     * @param minPath       Minimum distance path between two components
     * @param components    List of components
     * @param compEdges     List of edges for each component
     * @param finalComp     List of other components that have been extracted from graph
     * @param finalEdges    List of other component edges that have been extracted from graph
     */
    private void createFinalComponent(int minI, int minJ, Graph<TopologyVertex, DefaultEdge> partitions, GraphPath minPath,
                                      List<List<TopologyVertex>> components, List<List<DefaultEdge>> compEdges,
                                      List<List<TopologyVertex>> finalComp, List<List<DefaultEdge>> finalEdges) {
        finalComp.add(new ArrayList<>());
        finalEdges.add(new ArrayList<>());
        /* Add nodes on path connecting components to new component */
        for (Object x : minPath.getVertexList()) {
            Set<DefaultEdge> edges = new HashSet<>(partitions.edgesOf((TopologyVertex) x));
            partitions.removeAllEdges(edges);
            finalComp.get(finalComp.size() - 1).add((TopologyVertex) x);
        }

        /* Add edges on path connecting components to new edge list */
        for (Object e : minPath.getEdgeList()) {
            finalEdges.get(finalEdges.size() - 1).add((DefaultEdge) e);
        }

        /* Add nodes in source component to new component */
        for (TopologyVertex x : components.get(minI)) {
            if (!partitions.containsVertex(x)) {
                continue;
            }
            Set<DefaultEdge> edges = new HashSet<>(partitions.edgesOf(x));
            partitions.removeAllEdges(edges);
            finalComp.get(finalComp.size() - 1).add(x);
        }

        /* Add edges in source component to new edge list */
        for (DefaultEdge e : compEdges.get(minI)) {
            if (finalEdges.contains(e)) {
                continue;
            }
            finalEdges.get(finalEdges.size() - 1).add(e);
        }

        /* Add nodes in destination component to new component */
        for (TopologyVertex x : components.get(minJ)) {
            if (!partitions.containsVertex(x)) {
                continue;
            }
            Set<DefaultEdge> edges = new HashSet<>(partitions.edgesOf(x));
            partitions.removeAllEdges(edges);
            finalComp.get(finalComp.size() - 1).add(x);
        }

        /* Add edges in destination component to new edge list */
        for (DefaultEdge e : compEdges.get(minJ)) {
            if (finalEdges.contains(e)) {
                continue;
            }
            finalEdges.get(finalEdges.size() - 1).add(e);
        }
    }

    /**
     * Creates a merged component
     * @param minI          Index of source component being merged
     * @param minJ          Index of destination component being merged
     * @param minPath       Minimum distance path between two components
     * @param matched       Boolean map to mark that source and destination components have been merged
     * @param components    List of components
     * @param compEdges     List of edges for each component
     * @param newComp       List of other components that have been extracted from graph
     * @param newEdges      List of other component edges that have been extracted from graph
     */
    private void mergeComponents(int minI, int minJ, GraphPath minPath, Map<TopologyVertex, Boolean> matched,
                                 List<List<TopologyVertex>> components, List<List<DefaultEdge>> compEdges,
                                 List<TopologyVertex> newComp, List<DefaultEdge> newEdges) {

        /* Add nodes on path connecting components to new component */
        for (Object x : minPath.getVertexList()) {
            if (newComp.contains((TopologyVertex) x)) {
                continue;
            }
            newComp.add((TopologyVertex) x);
            matched.put((TopologyVertex) x, true);
        }

        /* Add edges on path connecting components to new component */
        for (Object e : minPath.getEdgeList()) {
            if (newEdges.contains((DefaultEdge) e)) {
                continue;
            }
            newEdges.add((DefaultEdge) e);
        }

        /* Add nodes in source component to new component */
        for (TopologyVertex x : components.get(minI)) {
            if (newComp.contains(x)) {
                continue;
            }
            newComp.add(x);
            matched.put(x, true);
        }

        /* Add edge in source component to new edge list */
        for (DefaultEdge e : compEdges.get(minI)) {
            if (newEdges.contains(e)) {
                continue;
            }
            newEdges.add(e);
        }

        /* Add nodes in destination component to new component */
        for (TopologyVertex x : components.get(minJ)) {
            if (newComp.contains(x)) {
                continue;
            }
            newComp.add(x);
            matched.put(x, true);
        }

        /* Add edge in destination component to new edge list */
        for (DefaultEdge e : compEdges.get(minJ)) {
            if (newEdges.contains(e)) {
                continue;
            }
            newEdges.add(e);
        }
    }

    /**
     * Adds several related lists into appropriate target list
     * @param targetComp    Target list for components
     * @param targetEdges   Target list for component edges
     * @param targetPoints  Target list for point tally
     * @param newComp       Component being added
     * @param newEdges      Edges being added
     * @param newPoints     Point tally being added
     */
    private void updateComponents(List<List<TopologyVertex>> targetComp, List<List<DefaultEdge>> targetEdges, List<Integer> targetPoints,
                                  List<TopologyVertex> newComp, List<DefaultEdge> newEdges, int newPoints) {
        targetComp.add(newComp);
        targetEdges.add(newEdges);
        targetPoints.add(newPoints);
    }

    /**
     * Creates a star topology according to configuration file specifications
     * @param graph     Current graph representing the network
     * @param points    Number of points (nodes with one outgoing edge) on each star
     * @param count     Number of stars to create
     * @return
     */
    private List<Graph<TopologyVertex, DefaultEdge>> createStarTopos(Graph<TopologyVertex, DefaultEdge> graph, int points, int count) {
        List<List<TopologyVertex>> components = new ArrayList<>();
        List<List<DefaultEdge>> compEdges = new ArrayList<>();
        List<List<TopologyVertex>> finalComp = new ArrayList<>();
        List<List<DefaultEdge>> finalEdges = new ArrayList<>();
        List<Integer> pointList = new ArrayList<>();
        initializeComponents(graph, components, compEdges, pointList);
        Graph<TopologyVertex, DefaultEdge> partitions = copyGraph(graph);
        int counter = 0;
        /* Create star topologies until it is either impossible to make any more or the specified count has been reached */
        while (true) {
            List<List<Integer>> compDist = new ArrayList<>();
            List<List<List<TopologyVertex>>> closestVert = new ArrayList<>();
            calculateComponentDistances(partitions, components, compDist, closestVert, 0);

            /* Put distances into a minheap */
            List<PriorityQueue<QueueEntry>> compQueue = new ArrayList<>();
            for (int i = 0; i < components.size(); i++) {
                compQueue.add(new PriorityQueue<>());
                for (int j = 0; j < components.size(); j++) {
                    compQueue.get(i).add(new QueueEntry(compDist.get(i).get(j), j));
                }
            }

            Map<TopologyVertex, Boolean> matched = new HashMap<>();
            boolean changed = false;

            /* Combine components to form stars with more points until one with the required number of points is formed */
            while (true) {
                int minDist = Integer.MAX_VALUE;
                GraphPath minPath = null;
                List<List<TopologyVertex>> tempComp = new ArrayList<>();
                List<List<DefaultEdge>> tempEdges = new ArrayList<>();
                List<Integer> tempPoints = new ArrayList<>();
                int minI = 0;
                int minJ = 0;
                int pos = 0;

                /* Check each components priority queue of distance to other nodes */
                for (int i = 0; i < compQueue.size(); i++) {
                    /* Pop from priority queue until a valid node is encountered */
                    while (compQueue.get(i).peek() != null && compQueue.get(i).peek().getKey() < minDist) {
                        TopologyVertex v = closestVert.get(i).get(compQueue.get(i).peek().getValue()).get(0);
                        TopologyVertex u = closestVert.get(i).get(compQueue.get(i).peek().getValue()).get(1);
                        GraphPath path = DijkstraShortestPath.findPathBetween(partitions, v, u);
                        boolean used = false;
                        /* Check that nodes in the connecting path are not used by other merged components */
                        for (Object x : path.getVertexList()) {
                            if (matched.containsKey((TopologyVertex) x)) {
                                compQueue.get(i).remove();
                                used = true;
                                break;
                            }
                        }

                        /* Uppdate minimum distance, path, and source and destination components */
                        if (!used) {
                            minDist = compQueue.get(i).peek().getKey();
                            minPath = path;
                            minI = i;
                            minJ = compQueue.get(i).peek().getValue();
                            changed = true;
                            pos = i;
                            break;
                        }
                    }
                }
                if (minPath == null) {
                    break;
                }
                compQueue.get(pos).remove();
                boolean exit = true;
                int newPoints = pointList.get(minI) + pointList.get(minJ);
                List<TopologyVertex> newComp = new ArrayList<>();
                List<DefaultEdge> newEdges = new ArrayList<>();

                /* If star topology formed has the required number of points, create star and trim points to be 1 node long */
                if (newPoints == points) {
                    createFinalComponent(minI, minJ, partitions, minPath, components, compEdges, finalComp, finalEdges);
                    trimEdges(graph, finalComp.get(finalComp.size() - 1), finalEdges.get(finalEdges.size() - 1), points, false);
                    counter++;
                }

                /* If star topology has more than the required number of points, create star,
                    trim off excess points, then trim all other points to be 1 node long
                 */
                else if (newPoints > points) {
                    createFinalComponent(minI, minJ, partitions, minPath, components, compEdges, finalComp, finalEdges);
                    trimEdges(graph, finalComp.get(finalComp.size() - 1), finalEdges.get(finalEdges.size() - 1), newPoints - points, true);
                    trimEdges(graph, finalComp.get(finalComp.size() - 1), finalEdges.get(finalEdges.size() - 1), points, false);
                    counter++;
                }

                /* Otherwise just merge component and continue loop to merge components */
                else {
                    mergeComponents(minI, minJ, minPath, matched, components, compEdges, newComp, newEdges);
                    exit = false;
                }

                /* Update component i with merged component, make component j empty, and copy all other components */
                for (int i = 0; i < components.size(); i++) {
                    if (i != minI && i != minJ) {
                        updateComponents(tempComp, tempEdges, tempPoints, components.get(i), compEdges.get(i), pointList.get(i));
                    }
                    else if (i == minI && newPoints < points) {
                        updateComponents(tempComp, tempEdges, tempPoints, newComp, newEdges, newPoints);
                    }
                    else {
                        updateComponents(tempComp, tempEdges, tempPoints, new ArrayList<>(), new ArrayList<>(), 0);
                    }
                }
                components = tempComp;
                compEdges = tempEdges;
                pointList = tempPoints;
                if (exit) {
                    break;
                }
            }

            /* Exit loop if no new components were merged or finalized, or required number of components have been made */
            if (!changed || counter >= count) {
                break;
            }

            List<List<TopologyVertex>> tempComp = new ArrayList<>();
            List<List<DefaultEdge>> tempEdges = new ArrayList<>();
            List<Integer> tempPoints = new ArrayList<>();

            /* Remove empty components from merges */
            for (int i = 0; i < components.size(); i++) {
                if (!components.get(i).isEmpty()) {
                    updateComponents(tempComp, tempEdges, tempPoints, components.get(i), compEdges.get(i), pointList.get(i));
                }
            }
            components = tempComp;
            compEdges = tempEdges;
            pointList = tempPoints;
        }

        /* Add finalized star topologies to overlay list */
        List<Graph<TopologyVertex, DefaultEdge>> topos = new ArrayList<>();
        for (int i = 0; i < finalComp.size(); i++) {
            topos.add(new SimpleGraph<>(DefaultEdge.class));
            for (TopologyVertex v : finalComp.get(i)) {
                topos.get(i).addVertex(v);
            }
            for (DefaultEdge e : finalEdges.get(i)) {
                topos.get(i).addEdge(graph.getEdgeSource(e), graph.getEdgeTarget(e));
            }
        }
        return topos;
    }

    /**
     * Creates a tree topology according to configuration file specifications
     * @param graph     Current graph representing the network
     * @param depth     Depth of the tree
     * @param fanout    Fanout at each level of the tree
     * @param count     Number of tree topologies to create
     * @return          List of tree topology graphs
     */
    private List<Graph<TopologyVertex, DefaultEdge>> createTreeTopos(Graph<TopologyVertex, DefaultEdge> graph, int depth, int fanout, int count) {
        List<List<TopologyVertex>> components = new ArrayList<>();
        List<List<DefaultEdge>> compEdges = new ArrayList<>();
        List<List<TopologyVertex>> finalComp = new ArrayList<>();
        List<List<DefaultEdge>> finalEdges = new ArrayList<>();
        List<List<TopologyVertex>> treeComp = new ArrayList<>();
        List<List<DefaultEdge>> treeEdges = new ArrayList<>();
        List<Integer> pointList = new ArrayList<>();
        initializeComponents(graph, components, compEdges, pointList);
        Graph<TopologyVertex, DefaultEdge> partitions = copyGraph(graph);
        Graph<TopologyVertex, DefaultEdge> originalParts = copyGraph(graph);
        boolean changed = false;
        int currFan = 0;
        int currDepth = 0;

        /* Iterate up until the required number of trees is created */
        for (int counter = 0; counter < count; counter++) {
            /* Iterate until the working tree has appropriate depth */
            while (currDepth < depth) {
                int targetFan = (int) Math.round(Math.pow(fanout, currDepth + 1));
                currFan = 0;
                finalComp = new ArrayList<>();
                finalEdges = new ArrayList<>();
                while (true) {
                    List<List<Integer>> compDist = new ArrayList<>();
                    List<List<List<TopologyVertex>>> closestVert = new ArrayList<>();
                    calculateComponentDistances(partitions, components, compDist, closestVert,3);

                    /* Put distances into a minheap */
                    List<PriorityQueue<QueueEntry>> compQueue = new ArrayList<>();
                    for (int i = 0; i < components.size(); i++) {
                        compQueue.add(new PriorityQueue<>());
                        for (int j = 0; j < components.size(); j++) {
                            compQueue.get(i).add(new QueueEntry(compDist.get(i).get(j), j));
                        }
                    }

                    Map<TopologyVertex, Boolean> matched = new HashMap<>();
                    changed = false;
                    /* Combine subtrees to form trees with more fanout until one with the required fanout is formed */
                    while (true) {
                        int minDist = Integer.MAX_VALUE;
                        GraphPath minPath = null;
                        List<List<TopologyVertex>> tempComp = new ArrayList<>();
                        List<List<DefaultEdge>> tempEdges = new ArrayList<>();
                        List<Integer> tempPoints = new ArrayList<>();
                        int minI = 0;
                        int minJ = 0;
                        int pos = 0;
                        // TODO: Make components using nodes in min path

                        /* Check each components priority queue of distance to other nodes */
                        for (int i = 0; i < compQueue.size(); i++) {
                            /* Pop from priority queue until a valid node is encountered */
                            while (compQueue.get(i).peek() != null && compQueue.get(i).peek().getKey() < minDist) {
                                TopologyVertex v = closestVert.get(i).get(compQueue.get(i).peek().getValue()).get(0);
                                TopologyVertex u = closestVert.get(i).get(compQueue.get(i).peek().getValue()).get(1);
                                GraphPath path = DijkstraShortestPath.findPathBetween(partitions, v, u);
                                boolean used = false;

                                /* Check that nodes in the connecting path are not used by other merged components */
                                for (Object x : path.getVertexList()) {
                                    if (matched.containsKey((TopologyVertex) x)) {
                                        compQueue.get(i).remove();
                                        used = true;
                                        break;
                                    }
                                }

                                /* Uppdate minimum distance, path, and source and destination components */
                                if (!used) {
                                    minDist = compQueue.get(i).peek().getKey();
                                    minPath = path;
                                    minI = i;
                                    minJ = compQueue.get(i).peek().getValue();
                                    changed = true;
                                    break;
                                }
                            }
                        }
                        if (minPath == null) {
                            break;
                        }
                        compQueue.get(minI).remove();
                        boolean exit = true;
                        int newPoints = pointList.get(minI) + pointList.get(minJ);
                        List<TopologyVertex> newComp = new ArrayList<>();
                        List<DefaultEdge> newEdges = new ArrayList<>();

                        /* Create final component for current depth if required fanout is reached (shouldn't be surpassed) */
                        if (newPoints >= targetFan) {
                            createFinalComponent(minI, minJ, partitions, minPath, components, compEdges, finalComp, finalEdges);
                            /* Trim if the final tree is about to be formed */
                            if (currDepth == depth - 1) {
                                trimEdges(graph, finalComp.get(finalComp.size() - 1), finalEdges.get(finalEdges.size() - 1), targetFan, false);
                            }
                            currFan++;
                        }

                        /* Otherwise just merge components */
                        else {
                            mergeComponents(minI, minJ, minPath, matched, components, compEdges, newComp, newEdges);
                            exit = false;
                        }

                        /* Update component i with merged component, make component j empty, and copy all other components */
                        for (int i = 0; i < components.size(); i++) {
                            if (i != minI && i != minJ) {
                                updateComponents(tempComp, tempEdges, tempPoints, components.get(i), compEdges.get(i), pointList.get(i));
                            } else if (i == minI && newPoints < targetFan) {
                                updateComponents(tempComp, tempEdges, tempPoints, newComp, newEdges, newPoints);
                            } else {
                                updateComponents(tempComp, tempEdges, tempPoints, new ArrayList<>(), new ArrayList<>(), 0);
                            }
                        }
                        components = tempComp;
                        compEdges = tempEdges;
                        pointList = tempPoints;
                        if (exit) {
                            break;
                        }
                    }

                    /* If at least the required number of subtrees was created and no more can be created, indicate so and break */
                    if (currFan >= fanout && !changed) {
                        changed = true;
                        currDepth++;
                        break;
                    }

                    /* Otherwise if subtree requirement is not met and no more can be created, break */
                    if (!changed) {
                        break;
                    }
                    List<List<TopologyVertex>> tempComp = new ArrayList<>();
                    List<List<DefaultEdge>> tempEdges = new ArrayList<>();
                    List<Integer> tempPoints = new ArrayList<>();

                    /* Remove empty components from merges */
                    for (int i = 0; i < components.size(); i++) {
                        if (!components.get(i).isEmpty()) {
                            updateComponents(tempComp, tempEdges, tempPoints, components.get(i), compEdges.get(i), pointList.get(i));
                        }
                    }
                    components = tempComp;
                    compEdges = tempEdges;
                    pointList = tempPoints;
                }

                /* Exit algorithm if no more trees can be made */
                if (!changed) {
                    break;
                }

                /* Reset graph and components, use subtrees as the initial components */
                if (currDepth < depth) {
                    pointList = new ArrayList<>();
                    partitions = copyGraph(originalParts);
                    components = finalComp;
                    compEdges = finalEdges;
                    for (int i = 0; i < components.size(); i++) {
                        pointList.add(targetFan);
                    }
                }
            }

            /* Exit algorithm if no more trees can be made */
            if (!changed) {
                break;
            }

            /* Add a new tree to overlay */
            treeComp.add(finalComp.get(0));
            treeEdges.add(finalEdges.get(0));
            originalParts = copyGraph(partitions);
        }

        /* Put tree overlays into a list and return */
        List<Graph<TopologyVertex, DefaultEdge>> topos = new ArrayList<>();
        for (int i = 0; i < treeComp.size(); i++) {
            topos.add(new SimpleGraph<>(DefaultEdge.class));
            for (TopologyVertex v : treeComp.get(i)) {
                topos.get(i).addVertex(v);
            }
            for (DefaultEdge e : treeEdges.get(i)) {
                topos.get(i).addEdge(graph.getEdgeSource(e), graph.getEdgeTarget(e));
            }
        }
        return topos;
    }
}
