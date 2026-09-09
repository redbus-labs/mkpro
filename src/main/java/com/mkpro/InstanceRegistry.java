package com.mkpro;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class InstanceRegistry {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Path REGISTRY_PATH = Paths.get(System.getProperty("user.home"), ".mkpro", "instances.json");

    public static class InstanceInfo {
        public String name;
        public long pid;
        public int httpPort;
        public int wsPort;
        public String workingDir;
        public long startTime;
    }

    public static int findAvailablePort(int startPort) {
        for (int port = startPort; port < startPort + 100; port++) {
            try (ServerSocket ignored = new ServerSocket(port)) {
                return port;
            } catch (IOException ignored) {}
        }
        throw new RuntimeException("No available ports found starting from " + startPort);
    }

    public static void register(String name, int wsPort, int httpPort) {
        registerInstance(name, httpPort, wsPort);
    }

    public static void updatePorts(String name, int wsPort, int httpPort) {
        executeWithLock(root -> {
            boolean changed = false;
            for (JsonNode node : root) {
                ObjectNode obj = (ObjectNode) node;
                if (obj.get("name").asText().equals(name)) {
                    obj.put("wsPort", wsPort);
                    obj.put("httpPort", httpPort);
                    changed = true;
                }
            }
            return changed;
        }, true);
    }

    public static void registerInstance(String name, int httpPort, int wsPort) {
        executeWithLock(root -> {
            cleanupStaleEntriesInternal(root);

            ObjectNode instance = mapper.createObjectNode();
            instance.put("name", name);
            instance.put("pid", ProcessHandle.current().pid());
            instance.put("httpPort", httpPort);
            instance.put("wsPort", wsPort);
            instance.put("workingDir", System.getProperty("user.dir"));
            instance.put("startTime", System.currentTimeMillis());

            root.add(instance);
            return true;
        }, true);
    }

    public static void unregisterInstance(String name) {
        executeWithLock(root -> {
            long currentPid = ProcessHandle.current().pid();
            boolean removed = false;
            Iterator<JsonNode> it = root.elements();
            while (it.hasNext()) {
                ObjectNode node = (ObjectNode) it.next();
                if (node.get("pid").asLong() == currentPid) {
                    it.remove();
                    removed = true;
                }
            }
            return removed;
        }, true);
    }

    public static void cleanupStaleEntries() {
        executeWithLock(InstanceRegistry::cleanupStaleEntriesInternal, true);
    }

    private static boolean cleanupStaleEntriesInternal(ArrayNode root) {
        boolean changed = false;
        Iterator<JsonNode> it = root.elements();
        while (it.hasNext()) {
            JsonNode node = it.next();
            long pid = node.get("pid").asLong();
            if (ProcessHandle.of(pid).isEmpty()) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    public static List<InstanceInfo> getAllInstances() {
        List<InstanceInfo> result = executeWithLock(root -> {
            List<InstanceInfo> instances = mapper.convertValue(root, new TypeReference<List<InstanceInfo>>() {});
            instances.sort(Comparator.comparingLong((InstanceInfo i) -> i.startTime)
                    .thenComparingLong(i -> i.pid));
            return instances;
        }, false);
        return result != null ? result : new ArrayList<>();
    }

    public static InstanceInfo getMaster() {
        List<InstanceInfo> instances = getAllInstances();
        return instances.isEmpty() ? null : instances.get(0);
    }

    public static boolean isMaster() {
        InstanceInfo master = getMaster();
        return master != null && master.pid == ProcessHandle.current().pid();
    }

    private interface RegistryTask<T> {
        T run(ArrayNode root) throws IOException;
    }

    private static <T> T executeWithLock(RegistryTask<T> task, boolean writeBack) {
        try {
            Files.createDirectories(REGISTRY_PATH.getParent());
            try (FileChannel channel = FileChannel.open(REGISTRY_PATH,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {

                ArrayNode root;
                if (channel.size() > 0) {
                    try {
                        root = (ArrayNode) mapper.readTree(Channels.newInputStream(channel));
                    } catch (IOException e) {
                        root = mapper.createArrayNode();
                    }
                } else {
                    root = mapper.createArrayNode();
                }

                T result = task.run(root);

                if (writeBack) {
                    channel.truncate(0);
                    channel.position(0);
                    mapper.writerWithDefaultPrettyPrinter().writeValue(Channels.newOutputStream(channel), root);
                }

                return result;
            }
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            System.err.println("Registry lock error: " + errorMessage);
            return null;
        }
    }
}
