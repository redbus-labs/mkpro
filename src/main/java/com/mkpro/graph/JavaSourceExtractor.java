package com.mkpro.graph;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JavaSourceExtractor implements SourceExtractor {

    @Override
    public ExtractionResult extract(Path sourcePath) {
        List<Entity> entities = new ArrayList<>();
        List<Relationship> relationships = new ArrayList<>();

        try {
            Files.walk(sourcePath)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            CompilationUnit cu = StaticJavaParser.parse(path);
                            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cid -> {
                                String className = cid.getNameAsString();
                                Entity classEntity = new Entity(className, className, EntityType.CLASS, new HashMap<>());
                                entities.add(classEntity);

                                cid.findAll(MethodDeclaration.class).forEach(md -> {
                                    String methodName = md.getNameAsString();
                                    String methodId = className + "#" + methodName;
                                    Entity methodEntity = new Entity(methodId, methodName, EntityType.METHOD, new HashMap<>());
                                    entities.add(methodEntity);

                                    relationships.add(new Relationship(
                                            className + "->" + methodId,
                                            className,
                                            methodId,
                                            RelType.CONTAINS,
                                            Map.of("weight", 1.0)
                                    ));
                                });
                            });
                        } catch (IOException e) {
                            // Skip or log
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan source path: " + sourcePath, e);
        }

        return new ExtractionResult(entities, relationships);
    }
}
