package com.mkpro.graph;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Implementation of ProjectScanner using JavaParser and SymbolSolver.
 */
public class JavaParserScanner implements ProjectScanner {
    private static final Logger logger = LoggerFactory.getLogger(JavaParserScanner.class);

    @Override
    public ExtractionResult scan(Path projectRoot) {
        logger.info("Starting recursive scan at: {}", projectRoot);

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());

        // 1. Find all directories that end with 'src/main/java'.
        // 2. Add all these directories to a 'CombinedTypeSolver' (using 'JavaParserTypeSolver').
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isDirectory)
                 .filter(p -> p.toString().replace("\\", "/").endsWith("src/main/java"))
                 .forEach(p -> {
                     logger.info("Found source root: {}", p);
                     typeSolver.add(new JavaParserTypeSolver(p));
                 });
        } catch (Exception e) {
            logger.error("Error finding source roots: {}", e.getMessage());
        }

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);

        List<Entity> entities = new ArrayList<>();
        List<Relationship> relationships = new ArrayList<>();
        ASTVisitor visitor = new ASTVisitor(entities, relationships);

        // 3. Find all '.java' files.
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(file -> {
                     try {
                         // 4. Parse it using 'StaticJavaParser.parse(file)'.
                         logger.debug("Parsing file: {}", file);
                         CompilationUnit cu = StaticJavaParser.parse(file);
                         
                         // Use the 'ASTVisitor' to extract entities and relationships.
                         cu.accept(visitor, null);
                     } catch (ParseProblemException e) {
                         // Handle 'ParseProblemException' gracefully (log and continue).
                         logger.warn("Failed to parse file {}: {}", file, e.getMessage());
                     } catch (Exception e) {
                         logger.error("Error processing file {}: {}", file, e.getMessage());
                     }
                 });
        } catch (Exception e) {
            logger.error("Error during file walking: {}", e.getMessage());
        }

        // 5. Aggregate all results into a single 'ExtractionResult'.
        logger.info("Scan completed. Found {} entities and {} relationships.", entities.size(), relationships.size());
        return new ExtractionResult(entities, relationships);
    }

    private static class ASTVisitor extends VoidVisitorAdapter<Void> {
        private final List<Entity> entities;
        private final List<Relationship> relationships;

        public ASTVisitor(List<Entity> entities, List<Relationship> relationships) {
            this.entities = entities;
            this.relationships = relationships;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
            super.visit(n, arg);
            String name = n.getFullyQualifiedName().orElse(n.getNameAsString());
            EntityType type = n.isInterface() ? EntityType.INTERFACE : EntityType.CLASS;

            entities.add(new Entity(name, name, type, Collections.emptyMap()));

            n.getExtendedTypes().forEach(et -> {
                String target = et.getNameAsString();
                relationships.add(new Relationship(name + "->EXTENDS->" + target, name, target, RelType.EXTENDS, Map.of("weight", 1.0)));
            });

            n.getImplementedTypes().forEach(it -> {
                String target = it.getNameAsString();
                relationships.add(new Relationship(name + "->IMPLEMENTS->" + target, name, target, RelType.IMPLEMENTS, Map.of("weight", 1.0)));
            });
        }

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            super.visit(n, arg);
            Optional<ClassOrInterfaceDeclaration> parentClass = n.findAncestor(ClassOrInterfaceDeclaration.class);
            if (parentClass.isPresent()) {
                String className = parentClass.get().getFullyQualifiedName().orElse(parentClass.get().getNameAsString());
                String methodName = className + "." + n.getNameAsString();
                
                entities.add(new Entity(methodName, n.getNameAsString(), EntityType.METHOD, Collections.emptyMap()));
                relationships.add(new Relationship(className + "->CONTAINS->" + methodName, className, methodName, RelType.CONTAINS, Map.of("weight", 1.0)));
            }
        }

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            super.visit(n, arg);
            try {
                var resolved = n.resolve();
                String targetMethod = resolved.getQualifiedName();

                n.findAncestor(MethodDeclaration.class).ifPresent(md -> {
                    n.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(cid -> {
                        String className = cid.getFullyQualifiedName().orElse(cid.getNameAsString());
                        String callerMethod = className + "." + md.getNameAsString();
                        relationships.add(new Relationship(callerMethod + "->CALLS->" + targetMethod, callerMethod, targetMethod, RelType.CALLS, Map.of("weight", 1.0)));
                    });
                });
            } catch (Exception e) {
                logger.trace("Could not resolve method call: {}", n);
            }
        }
    }
}
