import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BedrockBaseLM;
import com.google.adk.models.Claude;
import com.google.adk.models.Gemini;
import com.google.adk.models.OllamaBaseLM;
import com.google.adk.models.RedbusADG;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.MapDbRunner;
import com.google.adk.runner.Runner;
import com.google.adk.runner.PostgresRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
// Removed unused imports (Normalizer, ZoneId, ZonedDateTime, DateTimeFormatter)
import java.util.Map;
import java.util.Scanner;
import java.lang.Math; // Import Math class for trig functions

public class TrigonometryAgent {

    private static String USER_ID = "student";
    private static String NAME = "trig_calculator_agent"; // Changed agent name

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

    /**
     * Calculates the sine of an angle.
     * @param angleValue The numeric value of the angle.
     * @param unit The unit of the angle ("degrees" or "radians").
     * @return A map containing the status and result.
     */
    public static Map<String, Object> calculateSine(
        @Schema(description = "The numeric value of the angle") double angleValue,
        @Schema(description = "The unit of the angle, either 'degrees' or 'radians'") String unit) {

        double angleInRadians;

        if ("degrees".equalsIgnoreCase(unit)) {
            angleInRadians = Math.toRadians(angleValue);
        } else if ("radians".equalsIgnoreCase(unit)) {
            angleInRadians = angleValue;
        } else {
             return Map.of(
                "status", "error",
                "report", "Invalid unit provided. Please specify 'degrees' or 'radians'.",
                "inputAngleValue", angleValue,
                "inputUnit", unit
            );
        }

        double result = Math.sin(angleInRadians);

        return Map.of(
            "status", "success",
            "report", String.format("The sine of %.4f %s is %.6f", angleValue, unit, result),
            "inputAngleValue", angleValue,
            "inputUnit", unit,
            "function", "sine",
            "result", result
        );
    }

    /**
     * Calculates the cosine of an angle.
     * @param angleValue The numeric value of the angle.
     * @param unit The unit of the angle ("degrees" or "radians").
     * @return A map containing the status and result.
     */
    public static Map<String, Object> calculateCosine(
        @Schema(description = "The numeric value of the angle") double angleValue,
        @Schema(description = "The unit of the angle, either 'degrees' or 'radians'") String unit) {

        double angleInRadians;

        if ("degrees".equalsIgnoreCase(unit)) {
            angleInRadians = Math.toRadians(angleValue);
        } else if ("radians".equalsIgnoreCase(unit)) {
            angleInRadians = angleValue;
        } else {
             return Map.of(
                "status", "error",
                "report", "Invalid unit provided. Please specify 'degrees' or 'radians'.",
                "inputAngleValue", angleValue,
                "inputUnit", unit
            );
        }

        double result = Math.cos(angleInRadians);

        return Map.of(
            "status", "success",
            "report", String.format("The cosine of %.4f %s is %.6f", angleValue, unit, result),
            "inputAngleValue", angleValue,
            "inputUnit", unit,
            "function", "cosine",
            "result", result
        );
    }

    /**
     * Calculates the tangent of an angle.
     * Handles potential division by zero for tan(90 degrees), etc.
     * @param angleValue The numeric value of the angle.
     * @param unit The unit of the angle ("degrees" or "radians").
     * @return A map containing the status and result.
     */
    public static Map<String, Object> calculateTangent(
        @Schema(description = "The numeric value of the angle") double angleValue,
        @Schema(description = "The unit of the angle, either 'degrees' or 'radians'") String unit) {

        double angleInRadians;

        if ("degrees".equalsIgnoreCase(unit)) {
            // Check for angles where tangent is undefined (90 + 180*n degrees)
            double normalizedDegrees = angleValue % 180;
             if (Math.abs(normalizedDegrees - 90) < 1e-9 || Math.abs(normalizedDegrees + 90) < 1e-9) {
                 return Map.of(
                    "status", "error",
                    "report", String.format("The tangent of %.4f degrees is undefined.", angleValue),
                    "inputAngleValue", angleValue,
                    "inputUnit", unit,
                     "function", "tangent"
                 );
             }
            angleInRadians = Math.toRadians(angleValue);
        } else if ("radians".equalsIgnoreCase(unit)) {
             // Check for angles where tangent is undefined (pi/2 + pi*n radians)
             double normalizedRadians = angleValue % Math.PI;
             if (Math.abs(normalizedRadians - Math.PI/2) < 1e-9 || Math.abs(normalizedRadians + Math.PI/2) < 1e-9) {
                  return Map.of(
                    "status", "error",
                    "report", String.format("The tangent of %.4f radians is undefined.", angleValue),
                    "inputAngleValue", angleValue,
                    "inputUnit", unit,
                    "function", "tangent"
                 );
             }
            angleInRadians = angleValue;
        } else {
             return Map.of(
                "status", "error",
                "report", "Invalid unit provided. Please specify 'degrees' or 'radians'.",
                "inputAngleValue", angleValue,
                "inputUnit", unit
            );
        }

        double result = Math.tan(angleInRadians);

         return Map.of(
            "status", "success",
            "report", String.format("The tangent of %.4f %s is %.6f", angleValue, unit, result),
            "inputAngleValue", angleValue,
            "inputUnit", unit,
            "function", "tangent",
            "result", result
        );
    }

    private static BaseLlm selectLlmModel(Scanner scanner) {
        System.out.println("Select a BaseLM model (press Enter for default):");
        System.out.println("1: Bedrock (default)");
        System.out.println("2: Ollama");
        System.out.println("3: RedbusADG");
        System.out.println("4: Gemini");
        System.out.print("Enter your choice: ");
        String choiceStr = scanner.nextLine();
        int choice;
        try {
            choice = Integer.parseInt(choiceStr);
        } catch (NumberFormatException e) {
            choice = 1; // Default choice
        }

        switch (choice) {
            case 2:
                System.out.print("Enter Ollama Model Name (default: gpt-oss): ");
                String ollamaModelName = scanner.nextLine();
                if (ollamaModelName.isBlank()) {
                    ollamaModelName = "gpt-oss";
                }
                System.out.print("Enter Ollama URL (default: http://10.120.15.116:11434): ");
                String ollamaUrl = scanner.nextLine();
                if (ollamaUrl.isBlank()) {
                    ollamaUrl = "http://10.120.15.116:11434";
                }
                return new OllamaBaseLM(ollamaModelName, ollamaUrl);
            case 3:
                System.out.print("Enter RedbusADG Model ID (default: 40): ");
                String adgModelId = scanner.nextLine();
                if (adgModelId.isBlank()) {
                    adgModelId = "40";
                }
                return new RedbusADG(adgModelId);
            case 4:
                System.out.println("Using Gemini model.");
                System.out.print("Enter your Gemini API Key (or press Enter to use GEMINI_API_KEY environment variable): ");
                String apiKey = scanner.nextLine();
                if (apiKey.isBlank()) {
                    apiKey = System.getenv("GEMINI_API_KEY");
                }
                return new Gemini("gemini-1.5-flash", apiKey);
            case 1:
            default:
                System.out.println("Using default Bedrock model.");
                System.out.print("Enter Bedrock Model ID (default: openai.gpt-oss-20b-1:0): ");
                String bedrockModelId = scanner.nextLine();
                if (bedrockModelId.isBlank()) {
                    bedrockModelId = "openai.gpt-oss-20b-1:0";
                }
                System.out.print("Enter Bedrock URL (default: https://bedrock-runtime.us-west-2.amazonaws.com/model/openai.gpt-oss-20b-1:0/converse): ");
                String bedrockUrl = scanner.nextLine();
                if (bedrockUrl.isBlank()) {
                    bedrockUrl = "https://bedrock-runtime.us-west-2.amazonaws.com/model/openai.gpt-oss-20b-1:0/converse";
                }
                return new BedrockBaseLM(bedrockModelId, bedrockUrl);
        }
    }

    private static Runner selectRunner(Scanner scanner, BaseAgent agent) throws IOException, SQLException {
        System.out.println("\nSelect a Runner (press Enter for default):");
        System.out.println("1: InMemoryRunner (default)");
        System.out.println("2: MapDbRunner");
        System.out.println("3: PostgresRunner");
        System.out.print("Enter your choice: ");
        String choiceStr = scanner.nextLine();
        int choice;
        try {
            choice = Integer.parseInt(choiceStr);
        } catch (NumberFormatException e) {
            choice = 1; // Default choice
        }

        switch (choice) {
            case 2:
                System.out.println("Using MapDbRunner.");
                return new MapDbRunner(agent);
            case 3:
                System.out.println("Using PostgresRunner.");
                return new PostgresRunner(agent);
            case 1:
            default:
                System.out.println("Using default InMemoryRunner.");
                return new InMemoryRunner(agent);
        }
    }

    public static void main(String[] args) throws Exception, IOException, SQLException {
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);

        // 1. Select the LLM Model
        BaseLlm selectedModel = selectLlmModel(scanner);

        // 2. Initialize the Agent with the selected model
        BaseAgent agent = initAgent(selectedModel);

        // 3. Select the Runner
        Runner runner = selectRunner(scanner, agent);

        System.out.println("\nStarting chat with " + NAME + ". Type 'quit' to exit.");
        System.out.print("< ADK @ redBus >\n");

        Session session = runner.sessionService().createSession(NAME, USER_ID).blockingGet();

        while (true) {
            System.out.print("\nYou > ");
            String userInput = scanner.nextLine();

            if ("quit".equalsIgnoreCase(userInput)) {
                break;
            }

            Content userMsg = Content.fromParts(Part.fromText(userInput));

            System.out.print("\nAgent > ");
            LoadingAnimation loadingAnimation = new LoadingAnimation();
            Thread loadingThread = new Thread(loadingAnimation);
            loadingThread.start();

            Flowable<Event> events = runner.runAsync(USER_ID, session.id(), userMsg);
            events.blockingForEach(event -> {
                loadingAnimation.stop();
                try {
                    loadingThread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.print(event.stringifyContent());
            });
            System.out.println(); // for a new line after agent response
        }
        scanner.close();
        System.out.println("Chat session ended.");
    }

    static class LoadingAnimation implements Runnable {
        private volatile boolean running = true;
        private final char[] spinner = new char[]{'|', '/', '-', '\\'};

        public void stop() {
            running = false;
        }

        @Override
        public void run() {
            int i = 0;
            while (running) {
                System.out.print("\r" + spinner[i % spinner.length]);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                i++;
            }
            System.out.print("\r"); // Clear the spinner
        }
    }
}