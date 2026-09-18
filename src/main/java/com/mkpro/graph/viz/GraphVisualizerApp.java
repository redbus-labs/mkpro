package com.mkpro.graph.viz;

import com.mkpro.graph.*;
import org.jocl.*;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.jocl.CL.*;

public class GraphVisualizerApp {
    private final LayoutKernelProvider kernelProvider;
    private final MainFrame frame;
    private final List<Entity> entities;
    private final List<Relationship> relationships;
    private final Map<String, Integer> entityIdToIndex;
    
    private boolean useHierarchy = false;
    private float[] hostPositions;
    private int[] hostLevels;
    private cl_mem memPositions;
    private cl_mem memEdges;
    private cl_mem memLevels;

    public GraphVisualizerApp(List<Entity> entities, List<Relationship> relationships) {
        this.entities = entities;
        this.relationships = relationships;
        this.entityIdToIndex = new HashMap<>();
        for (int i = 0; i < entities.size(); i++) {
            entityIdToIndex.put(entities.get(i).id(), i);
        }
        this.kernelProvider = new LayoutKernelProvider();
        this.frame = new MainFrame();
        
        // Handle UI wiring
        frame.getHierarchicalToggle().addActionListener(e -> {
            this.useHierarchy = frame.getHierarchicalToggle().isSelected();
        });

        frame.getEntityTree().addTreeSelectionListener(e -> {
            DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) frame.getEntityTree().getLastSelectedPathComponent();
            if (selectedNode != null && selectedNode.getUserObject() instanceof Entity entity) {
                frame.updateProperties(entity);
                frame.getCanvas().setSelectedEntityId(entity.id());
            } else {
                frame.clearProperties();
                frame.getCanvas().setSelectedEntityId(null);
            }
        });

        initOpenCLBuffers();
        javax.swing.Timer timer = new javax.swing.Timer(16, e -> runLayoutStep());
        timer.start();
    }

    private void initOpenCLBuffers() {
        int numNodes = entities.size();
        hostPositions = new float[numNodes * 2];
        Random rnd = new Random();
        for (int i = 0; i < hostPositions.length; i++) {
            hostPositions[i] = (rnd.nextFloat() - 0.5f) * 1000;
        }
        memPositions = clCreateBuffer(kernelProvider.getContext(), 
            CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR, 
            Sizeof.cl_float * hostPositions.length, Pointer.to(hostPositions), null);

        // Compute levels via BFS
        hostLevels = new int[numNodes];
        Arrays.fill(hostLevels, -1);
        
        @SuppressWarnings("unchecked")
        List<Integer>[] adj = (List<Integer>[]) new List[numNodes];
        for (int i = 0; i < numNodes; i++) adj[i] = new ArrayList<>();
        for (Relationship r : relationships) {
            Integer src = entityIdToIndex.get(r.sourceId());
            Integer tgt = entityIdToIndex.get(r.targetId());
            if (src != null && tgt != null) {
                adj[src].add(tgt);
                adj[tgt].add(src);
            }
        }

        int startNode = -1;
        for (int i = 0; i < numNodes; i++) {
            if (!adj[i].isEmpty()) {
                startNode = i;
                break;
            }
        }

        if (startNode != -1) {
            Queue<Integer> queue = new LinkedList<>();
            queue.add(startNode);
            hostLevels[startNode] = 0;
            while (!queue.isEmpty()) {
                int u = queue.poll();
                for (int v : adj[u]) {
                    if (hostLevels[v] == -1) {
                        hostLevels[v] = hostLevels[u] + 1;
                        queue.add(v);
                    }
                }
            }
        }
        for (int i = 0; i < numNodes; i++) if (hostLevels[i] == -1) hostLevels[i] = 0;

        memLevels = clCreateBuffer(kernelProvider.getContext(), 
            CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, 
            Sizeof.cl_int * hostLevels.length, Pointer.to(hostLevels), null);

        int[] hostEdges = new int[relationships.size() * 2];
        for (int i = 0; i < relationships.size(); i++) {
            Relationship r = relationships.get(i);
            hostEdges[i * 2] = entityIdToIndex.getOrDefault(r.sourceId(), 0);
            hostEdges[i * 2 + 1] = entityIdToIndex.getOrDefault(r.targetId(), 0);
        }
        memEdges = clCreateBuffer(kernelProvider.getContext(), 
            CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, 
            Sizeof.cl_int * hostEdges.length, Pointer.to(hostEdges), null);
    }

    private void runLayoutStep() {
        cl_kernel kernel = kernelProvider.getKernel();
        int numNodes = entities.size();
        int numEdges = relationships.size();
        float k = 80.0f;
        float dt = 0.05f;
        
        clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(memPositions));
        clSetKernelArg(kernel, 1, Sizeof.cl_mem, Pointer.to(memEdges));
        clSetKernelArg(kernel, 2, Sizeof.cl_mem, Pointer.to(memLevels));
        clSetKernelArg(kernel, 3, Sizeof.cl_int, Pointer.to(new int[]{numNodes}));
        clSetKernelArg(kernel, 4, Sizeof.cl_int, Pointer.to(new int[]{numEdges}));
        clSetKernelArg(kernel, 5, Sizeof.cl_float, Pointer.to(new float[]{k}));
        clSetKernelArg(kernel, 6, Sizeof.cl_float, Pointer.to(new float[]{dt}));
        clSetKernelArg(kernel, 7, Sizeof.cl_int, Pointer.to(new int[]{useHierarchy ? 1 : 0}));
        
        clEnqueueNDRangeKernel(kernelProvider.getCommandQueue(), kernel, 1, null, new long[]{numNodes}, null, 0, null, null);
        clEnqueueReadBuffer(kernelProvider.getCommandQueue(), memPositions, CL_TRUE, 0, Sizeof.cl_float * hostPositions.length, Pointer.to(hostPositions), 0, null, null);

        // Safety check for NaN or Infinity
        boolean invalid = false;
        for (float f : hostPositions) {
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                invalid = true;
                break;
            }
        }
        if (invalid) {
            System.err.println("[Simulation Warning] Simulation exploded (NaN/Inf detected)! Resetting positions...");
            Random rnd = new Random();
            for (int i = 0; i < hostPositions.length; i++) {
                hostPositions[i] = (rnd.nextFloat() - 0.5f) * 1000;
            }
            clEnqueueWriteBuffer(kernelProvider.getCommandQueue(), memPositions, CL_TRUE, 0, Sizeof.cl_float * hostPositions.length, Pointer.to(hostPositions), 0, null, null);
        }

        frame.updateGraph(entities, relationships, hostPositions, entityIdToIndex);
    }

    public void show() {
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        String dbPath;
        String storageKey;
        
        if (args.length >= 2) {
            dbPath = args[0];
            storageKey = args[1];
        } else {
            Path projectPath = Paths.get(".");
            dbPath = MapDbGraphRepository.resolveDatabasePath(projectPath);
            storageKey = GitUtil.getCommitHashOrFallback(projectPath);
        }
        
        ExtractionResult result = null;

        try (MapDbGraphRepository repository = new MapDbGraphRepository(dbPath)) {
            if (repository.hasExtraction(storageKey)) {
                System.out.println("[Warm Start] Loaded pre-computed index from disk for key: " + storageKey);
                result = repository.loadExtraction(storageKey).orElse(null);
            }
        } catch (Exception e) {
            System.out.println("[Cache Error] " + e.getMessage());
        }

        if (result == null) {
            System.out.println("[Cold Start] Scanning project with JavaParser...");
            JavaParserScanner scanner = new JavaParserScanner();
            try {
                result = scanner.scan(Paths.get("."));
                try (MapDbGraphRepository repository = new MapDbGraphRepository(dbPath)) {
                    repository.saveExtraction(storageKey, result);
                    System.out.println("✓ Cached extraction result to disk.");
                }
            } catch (Exception e) {
                System.err.println("Failed to scan project: " + e.getMessage());
                e.printStackTrace();
                return;
            }
        }

        List<Entity> entities = result.entities();
        List<Relationship> relationships = result.relationships();

        SwingUtilities.invokeLater(() -> {
            try {
                new GraphVisualizerApp(entities, relationships).show();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
