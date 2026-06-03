// Test comment for CodeEditor verification
package com.mkpro.graph;

import org.jgrapht.Graph;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Command-line interface for Graphify.
 */
@Command(name = "graphify", mixinStandardHelpOptions = true, version = "graphify 1.0",
        description = "Analyzes Java projects and generates graph-based reports.")
public class GraphifyCli implements Callable<Integer> {

    @Parameters(index = "0", description = "The project directory to scan.")
    private Path projectDir;

    @Option(names = {"-o", "--output"}, description = "Path for reports.", defaultValue = ".")
    private Path outputPath;

    @Override
    public Integer call() throws Exception {
        System.out.println(">>> Graphify: Starting Analysis of " + projectDir.toAbsolutePath());
        
        System.out.println("[1/4] Resolving index cache...");
        String commitHash = GitUtil.getCommitHashOrFallback(projectDir);
        String dbPath = MapDbGraphRepository.resolveDatabasePath(projectDir);
        ExtractionResult extraction = null;

        try (MapDbGraphRepository repository = new MapDbGraphRepository(dbPath)) {
            if (repository.hasExtraction(commitHash)) {
                System.out.println("  ✓ Found pre-computed index cache for commit: " + commitHash);
                System.out.println("  ✓ Loaded extraction result instantly from disk.");
                extraction = repository.loadExtraction(commitHash).orElse(null);
            }
        } catch (Exception e) {
            System.out.println("  ⚠ Could not read index cache: " + e.getMessage());
        }

        if (extraction == null) {
            System.out.println("  ✓ (Cache Miss) Scanning project with JavaParser (Cold start)...");
            JavaParserScanner scanner = new JavaParserScanner();
            extraction = scanner.scan(projectDir);

            // Save to cache
            try (MapDbGraphRepository repository = new MapDbGraphRepository(dbPath)) {
                repository.saveExtraction(commitHash, extraction);
                System.out.println("  ✓ Cached extraction result to disk for commit: " + commitHash);
            } catch (Exception e) {
                System.out.println("  ⚠ Could not write index cache: " + e.getMessage());
            }
        }
        
        System.out.println("[2/4] Building JGraphT model...");
        JGraphTBuilder builder = new JGraphTBuilder();
        Graph<Entity, RelationshipEdge> graph = builder.buildGraph(extraction);
        
        System.out.println("[3/4] Running Graph Analysis (PageRank & Communities)...");
        DefaultGraphAnalyzer analyzer = new DefaultGraphAnalyzer();
        AnalysisResult analysis = analyzer.analyze(graph);
        
        System.out.println("[4/4] Exporting Reports...");
        // Ensure output directory exists
        if (!outputPath.toFile().exists()) {
            outputPath.toFile().mkdirs();
        }
        
        new JsonGraphExporter().export(extraction, analysis, outputPath.resolve("graph_data.json"));
        new MarkdownReportExporter().export(extraction, analysis, outputPath);
        
        System.out.println(">>> Done! Reports generated in: " + outputPath.toAbsolutePath());
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new GraphifyCli()).execute(args));
    }
}
