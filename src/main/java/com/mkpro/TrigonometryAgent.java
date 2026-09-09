package com.mkpro;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BedrockBaseLM;
import com.google.adk.models.Gemini;
import com.google.adk.models.OllamaBaseLM;
import com.google.adk.models.RedbusADG;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.Scanner;
import java.lang.Math;

public class TrigonometryAgent {

    private static String USER_ID = "student";
    private static String NAME = "trig_calculator_agent";

    public static BaseAgent initAgent(BaseLlm model) {
        return LlmAgent.builder()
                .name(NAME)
                .model(model)
                .description("Agent to calculate trigonometric functions (sine, cosine, tangent) for given angles.")
                .instruction(
                        "You are a helpful agent who can calculate trigonometric functions (sine, cosine, and"
                        + " tangent). Use the provided tools to perform these calculations."
                        + " When the user provides an angle, identify the value and the unit (degrees or radians)."
                        + " Call the appropriate tool based on the requested function (sin, cos, tan) and provide the angle value and unit."
                        + " Ensure the angle unit is explicitly passed to the tool as 'degrees' or 'radians'.")
                .tools(
                        FunctionTool.create(TrigonometryAgent.class, "calculateSine"),
                        FunctionTool.create(TrigonometryAgent.class, "calculateCosine"),
                        FunctionTool.create(TrigonometryAgent.class, "calculateTangent")
                )
                .build();
    }

    public static Map<String, Object> calculateSine(
        @Schema(description = "The numeric value of the angle") double angleValue,
        @Schema(description = "The unit of the angle, either 'degrees' or 'radians'") String unit) {

        double angleInRadians;
        if ("degrees".equalsIgnoreCase(unit)) {
            angleInRadians = Math.toRadians(angleValue);
        } else if ("radians".equalsIgnoreCase(unit)) {
            angleInRadians = angleValue;
        } else {
             return Map.of("status", "error", "report", "Invalid unit provided.");
        }
        double result = Math.sin(angleInRadians);
        return Map.of("status", "success", "result", result, "report", String.format("The sine of %.4f %s is %.6f", angleValue, unit, result));
    }

    public static Map<String, Object> calculateCosine(
        @Schema(description = "The numeric value of the angle") double angleValue,
        @Schema(description = "The unit of the angle, either 'degrees' or 'radians'") String unit) {

        double angleInRadians;
        if ("degrees".equalsIgnoreCase(unit)) {
            angleInRadians = Math.toRadians(angleValue);
        } else if ("radians".equalsIgnoreCase(unit)) {
            angleInRadians = angleValue;
        } else {
             return Map.of("status", "error", "report", "Invalid unit provided.");
        }
        double result = Math.cos(angleInRadians);
        return Map.of("status", "success", "result", result, "report", String.format("The cosine of %.4f %s is %.6f", angleValue, unit, result));
    }

    public static Map<String, Object> calculateTangent(
        @Schema(description = "The numeric value of the angle") double angleValue,
        @Schema(description = "The unit of the angle, either 'degrees' or 'radians'") String unit) {

        double angleInRadians;
        if ("degrees".equalsIgnoreCase(unit)) {
            double normalizedDegrees = angleValue % 180;
             if (Math.abs(normalizedDegrees - 90) < 1e-9 || Math.abs(normalizedDegrees + 90) < 1e-9) {
                 return Map.of("status", "error", "report", "Tangent is undefined.");
             }
            angleInRadians = Math.toRadians(angleValue);
        } else if ("radians".equalsIgnoreCase(unit)) {
             double normalizedRadians = angleValue % Math.PI;
             if (Math.abs(normalizedRadians - Math.PI/2) < 1e-9 || Math.abs(normalizedRadians + Math.PI/2) < 1e-9) {
                  return Map.of("status", "error", "report", "Tangent is undefined.");
             }
            angleInRadians = angleValue;
        } else {
             return Map.of("status", "error", "report", "Invalid unit provided.");
        }
        double result = Math.tan(angleInRadians);
        return Map.of("status", "success", "result", result, "report", String.format("The tangent of %.4f %s is %.6f", angleValue, unit, result));
    }

    private static BaseLlm selectLlmModel(Scanner scanner) {
        System.out.println("Select a BaseLM model (press Enter for default):");
        System.out.println("1: Bedrock (default)");
        System.out.println("2: Ollama");
        System.out.println("3: RedbusADG");
        System.out.println("4: Gemini");
        System.out.print("Enter your choice: ");
        String choiceStr = scanner.nextLine();
        int choice = choiceStr.isBlank() ? 1 : Integer.parseInt(choiceStr);

        switch (choice) {
            case 2: return new OllamaBaseLM("gpt-oss", "http://10.120.15.116:11434");
            case 3: return new RedbusADG("40");
            case 4: 
                String apiKey = System.getenv("GEMINI_API_KEY");
                return new Gemini("gemini-3.1-pro-preview", apiKey);
            default: return new BedrockBaseLM("anthropic.claude-3-sonnet-20240229-v1:0");
        }
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        BaseLlm model = selectLlmModel(scanner);
        BaseAgent agent = initAgent(model);

        Runner runner = Runner.builder()
                .agent(agent)
                .appName("TrigApp")
                .sessionService(new com.google.adk.sessions.InMemorySessionService())
                .artifactService(new com.google.adk.artifacts.InMemoryArtifactService())
                .memoryService(new com.google.adk.memory.InMemoryMemoryService())
                .build();
        Session session = runner.sessionService().createSession("mkpro", USER_ID).blockingGet();

        System.out.println("Trigonometry Agent is ready. Type your questions (e.g., 'What is the sine of 30 degrees?'). Type 'exit' to quit.");
        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine();
            if ("exit".equalsIgnoreCase(input)) break;

            Content content = Content.builder()
                    .role("user")
                    .parts(java.util.Collections.singletonList(Part.fromText(input)))
                    .build();

            runner.runAsync(session.sessionKey(), content, com.google.adk.agents.RunConfig.builder().build())
                    .doOnNext(event -> {
                        if (event.content().isPresent()) {
                            String text = event.stringifyContent();
                            if (!text.isEmpty()) {
                                System.out.println(text);
                            }
                        }
                    })
                    .blockingSubscribe();
        }
        System.out.println("Goodbye!");
    }
}
