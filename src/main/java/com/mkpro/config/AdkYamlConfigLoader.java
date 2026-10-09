package com.mkpro.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mkpro.models.AdkAgentSpec;
import com.mkpro.models.AgentDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdkYamlConfigLoader {

    private final ObjectMapper yamlMapper;
    private static final Pattern INCLUDE_PATTERN = Pattern.compile("^(\\s*[\"']?[\\w_]+[\"']?\\s*:\\s*)!include\\s+(.+)$");

    public AdkYamlConfigLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public AdkAgentSpec load(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            throw new IOException("File does not exist: " + path);
        }
        String rawYaml = Files.readString(path, StandardCharsets.UTF_8);
        Path parentDir = path.getParent() != null ? path.getParent() : Path.of(".");
        String processedYaml = resolveIncludes(rawYaml, parentDir);
        return yamlMapper.readValue(processedYaml, AdkAgentSpec.class);
    }

    public AdkAgentSpec loadFromResource(String resourcePath) throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = getClass().getClassLoader();
        }
        try (InputStream is = cl.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            String rawYaml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Path resourceParent = Path.of(resourcePath).getParent();
            String parentStr = resourceParent != null ? resourceParent.toString().replace('\\', '/') : "";
            String processedYaml = resolveIncludesResource(rawYaml, parentStr, cl);
            return yamlMapper.readValue(processedYaml, AdkAgentSpec.class);
        }
    }

    public AdkAgentSpec loadModularTeam(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IOException("Not a valid directory: " + dir);
        }

        Path agentYaml = dir.resolve("agent.yaml");
        if (!Files.exists(agentYaml)) {
            agentYaml = dir.resolve("agent.yml");
        }
        if (!Files.exists(agentYaml)) {
            throw new IOException("No agent.yaml or agent.yml found in " + dir);
        }

        AdkAgentSpec rootSpec = load(agentYaml);

        // Discover sub-agents in sub_agents/ directory
        Path subAgentsDir = dir.resolve("sub_agents");
        if (Files.isDirectory(subAgentsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(subAgentsDir, "*.{yaml,yml}")) {
                for (Path subPath : stream) {
                    try {
                        AdkAgentSpec subSpec = load(subPath);
                        if (subSpec.getName() != null) {
                            rootSpec.getSubAgents().put(subSpec.getName(), subSpec);
                        } else {
                            String fileName = subPath.getFileName().toString();
                            String baseName = fileName.replaceAll("\\.(yaml|yml)$", "");
                            rootSpec.getSubAgents().put(baseName, subSpec);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // Discover skills in skills/ directory
        Path skillsDir = dir.resolve("skills");
        if (Files.isDirectory(skillsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
                for (Path skillPath : stream) {
                    if (Files.isDirectory(skillPath)) {
                        String skillName = skillPath.getFileName().toString();
                        if (!rootSpec.getSkills().contains(skillName)) {
                            rootSpec.getSkills().add(skillName);
                        }
                    }
                }
            }
        }

        return rootSpec;
    }

    public Map<String, AgentDefinition> loadAgentDefinitions(Path path) {
        Map<String, AgentDefinition> defs = new LinkedHashMap<>();
        if (path == null || !Files.exists(path)) {
            return defs;
        }

        try {
            if (Files.isDirectory(path)) {
                Path agentYaml = path.resolve("agent.yaml");
                Path agentYml = path.resolve("agent.yml");
                if (Files.exists(agentYaml) || Files.exists(agentYml)) {
                    AdkAgentSpec teamSpec = loadModularTeam(path);
                    AgentDefinition rootDef = toAgentDefinition(teamSpec);
                    if (rootDef != null && rootDef.getName() != null) {
                        defs.put(rootDef.getName(), rootDef);
                    }
                    for (AdkAgentSpec subSpec : teamSpec.getSubAgents().values()) {
                        AgentDefinition subDef = toAgentDefinition(subSpec);
                        if (subDef != null && subDef.getName() != null) {
                            defs.put(subDef.getName(), subDef);
                        }
                    }
                    return defs;
                }
            } else if (Files.isRegularFile(path)) {
                String fileName = path.getFileName().toString();
                if (fileName.equals("agent.yaml") || fileName.equals("agent.yml")) {
                    Path parentDir = path.getParent();
                    if (parentDir != null) {
                        return loadAgentDefinitions(parentDir);
                    }
                }

                // Try reading as AdkAgentSpec or legacy format
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                Path parentDir = path.getParent() != null ? path.getParent() : Path.of(".");
                String processed = resolveIncludes(raw, parentDir);

                // Check if it's a legacy YAML with "agents: [...]"
                JsonNode rootNode = yamlMapper.readTree(processed);
                if (rootNode != null && rootNode.has("agents")) {
                    JsonNode agentsNode = rootNode.get("agents");
                    if (agentsNode.isArray()) {
                        for (JsonNode node : agentsNode) {
                            AgentDefinition def = yamlMapper.treeToValue(node, AgentDefinition.class);
                            if (def != null && def.getName() != null) {
                                defs.put(def.getName(), def);
                            }
                        }
                        return defs;
                    }
                }

                // Check if it's a single AdkAgentSpec
                AdkAgentSpec spec = yamlMapper.readValue(processed, AdkAgentSpec.class);
                if (spec != null && spec.getName() != null) {
                    AgentDefinition rootDef = toAgentDefinition(spec);
                    defs.put(rootDef.getName(), rootDef);
                    for (AdkAgentSpec subSpec : spec.getSubAgents().values()) {
                        AgentDefinition subDef = toAgentDefinition(subSpec);
                        if (subDef != null && subDef.getName() != null) {
                            defs.put(subDef.getName(), subDef);
                        }
                    }
                    return defs;
                }
            }
        } catch (Exception e) {
            // Return whatever was collected or empty
        }

        return defs;
    }

    public AgentDefinition toAgentDefinition(AdkAgentSpec spec) {
        if (spec == null) {
            return null;
        }
        return spec.toAgentDefinition();
    }

    private String resolveIncludes(String rawYaml, Path baseDir) throws IOException {
        String[] lines = rawYaml.split("\r?\n", -1);
        StringBuilder sb = new StringBuilder();

        for (String line : lines) {
            Matcher m = INCLUDE_PATTERN.matcher(line);
            if (m.matches()) {
                String prefix = m.group(1);
                String relPath = m.group(2).trim();
                // strip quotes if present
                if ((relPath.startsWith("\"") && relPath.endsWith("\"")) ||
                    (relPath.startsWith("'") && relPath.endsWith("'"))) {
                    relPath = relPath.substring(1, relPath.length() - 1);
                }

                Path targetPath = baseDir.resolve(relPath).normalize();
                if (!Files.exists(targetPath)) {
                    throw new IOException("Included file not found: " + targetPath + " (resolved from " + relPath + ")");
                }

                int indent = getLeadingWhitespaceCount(prefix);
                String indentStr = " ".repeat(indent);

                if (relPath.endsWith(".yaml") || relPath.endsWith(".yml")) {
                    String includedContent = Files.readString(targetPath, StandardCharsets.UTF_8);
                    String nested = resolveIncludes(includedContent, targetPath.getParent());
                    sb.append(prefix.trim()).append("\n");
                    for (String nestedLine : nested.split("\r?\n", -1)) {
                        if (!nestedLine.isEmpty()) {
                            sb.append(indentStr).append("  ").append(nestedLine).append("\n");
                        } else {
                            sb.append("\n");
                        }
                    }
                } else {
                    // Treat as text/markdown block scalar
                    String content = Files.readString(targetPath, StandardCharsets.UTF_8);
                    sb.append(prefix).append("|\n");
                    for (String textLine : content.split("\r?\n", -1)) {
                        sb.append(indentStr).append("  ").append(textLine).append("\n");
                    }
                }
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private String resolveIncludesResource(String rawYaml, String baseResourceDir, ClassLoader cl) throws IOException {
        String[] lines = rawYaml.split("\r?\n", -1);
        StringBuilder sb = new StringBuilder();

        for (String line : lines) {
            Matcher m = INCLUDE_PATTERN.matcher(line);
            if (m.matches()) {
                String prefix = m.group(1);
                String relPath = m.group(2).trim();
                if ((relPath.startsWith("\"") && relPath.endsWith("\"")) ||
                    (relPath.startsWith("'") && relPath.endsWith("'"))) {
                    relPath = relPath.substring(1, relPath.length() - 1);
                }

                String targetResource;
                if (baseResourceDir == null || baseResourceDir.isEmpty()) {
                    targetResource = relPath;
                } else {
                    targetResource = Path.of(baseResourceDir).resolve(relPath).normalize().toString().replace('\\', '/');
                }

                try (InputStream is = cl.getResourceAsStream(targetResource)) {
                    if (is == null) {
                        throw new IOException("Included resource not found: " + targetResource);
                    }
                    String includedContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    int indent = getLeadingWhitespaceCount(prefix);
                    String indentStr = " ".repeat(indent);

                    if (relPath.endsWith(".yaml") || relPath.endsWith(".yml")) {
                        Path nextParent = Path.of(targetResource).getParent();
                        String nextParentStr = nextParent != null ? nextParent.toString().replace('\\', '/') : "";
                        String nested = resolveIncludesResource(includedContent, nextParentStr, cl);
                        sb.append(prefix.trim()).append("\n");
                        for (String nestedLine : nested.split("\r?\n", -1)) {
                            if (!nestedLine.isEmpty()) {
                                sb.append(indentStr).append("  ").append(nestedLine).append("\n");
                            } else {
                                sb.append("\n");
                            }
                        }
                    } else {
                        sb.append(prefix).append("|\n");
                        for (String textLine : includedContent.split("\r?\n", -1)) {
                            sb.append(indentStr).append("  ").append(textLine).append("\n");
                        }
                    }
                }
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private int getLeadingWhitespaceCount(String str) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (Character.isWhitespace(str.charAt(i))) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }
}
