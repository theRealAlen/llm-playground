package dev.llmplayground;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Main {
    private static final String TEXT =
            "The service was excellent, the staff were kind, and everything worked perfectly.";
    private static final List<String> LABELS = List.of("positive", "neutral", "negative");
    private static final List<PromptVariant> PROMPTS = List.of(
            new PromptVariant("no_examples", "No examples",
                    "Classify the sentiment of the text as positive, neutral, or negative. "
                            + "Return exactly one label and nothing else.\nText: " + TEXT),
            new PromptVariant("with_examples", "With examples",
                    "Classify sentiment as positive, neutral, or negative. Return exactly one label.\n"
                            + "Text: The meal was wonderful and the service was warm.\nLabel: positive\n"
                            + "Text: The food was awful and arrived cold.\nLabel: negative\n"
                            + "Text: The restaurant opens at 9 a.m.\nLabel: neutral\n"
                            + "Text: " + TEXT + "\nLabel:"),
            new PromptVariant("xml_structure", "XML structure",
                    "<task>Classify the sentiment of the text.</task>\n"
                            + "<allowed_labels>positive, neutral, negative</allowed_labels>\n"
                            + "<output>Return exactly one label and nothing else.</output>\n"
                            + "<text>" + TEXT + "</text>"));

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 2) {
            throw new IllegalArgumentException("Usage: java ... Main [output.json] [runs-per-prompt]");
        }

        Path outputPath = Path.of(args.length >= 1 ? args[0] : "results.json");
        int runCount = args.length == 2 ? Integer.parseInt(args[1]) : 20;
        if (runCount < 1) {
            throw new IllegalArgumentException("runs-per-prompt must be at least 1");
        }

        String apiKey = requiredEnvironment("LLM_API_KEY");
        String apiUrl = environmentOrDefault(
                "LLM_API_URL", "https://api.openai.com/v1/chat/completions");
        String model = environmentOrDefault("LLM_MODEL", "gpt-4o-mini");
        double temperature = Double.parseDouble(environmentOrDefault("LLM_TEMPERATURE", "0.7"));
        if (temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("LLM_TEMPERATURE must be between 0 and 2");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("startedAt", Instant.now().toString());
        document.put("model", model);
        document.put("temperature", temperature);
        document.put("targetText", TEXT);
        document.put("runsPerPrompt", runCount);
        List<Map<String, Object>> experiments = new ArrayList<>();
        document.put("experiments", experiments);

        for (PromptVariant variant : PROMPTS) {
            Map<String, Object> experiment = new LinkedHashMap<>();
            experiment.put("id", variant.id());
            experiment.put("name", variant.name());
            experiment.put("prompt", variant.prompt());
            List<Map<String, Object>> runs = new ArrayList<>();
            experiment.put("runs", runs);
            experiments.add(experiment);

            for (int i = 1; i <= runCount; i++) {
                String rawOutput = callModel(client, apiUrl, apiKey, model, temperature, variant.prompt());
                String normalizedOutput = normalize(rawOutput);
                Map<String, Object> run = new LinkedHashMap<>();
                run.put("run", i);
                run.put("timestamp", Instant.now().toString());
                run.put("rawOutput", rawOutput);
                run.put("normalizedOutput", normalizedOutput);
                run.put("validLabel", LABELS.contains(normalizedOutput));
                runs.add(run);

                experiment.put("summary", summarize(runs));
                document.put("updatedAt", Instant.now().toString());
                writeJson(outputPath, document);
                System.out.printf("%s: %d/%d (%s)%n", variant.id(), i, runCount, normalizedOutput);
            }
        }
        document.put("completedAt", Instant.now().toString());
        writeJson(outputPath, document);
        System.out.println("Saved experiment results to " + outputPath.toAbsolutePath());
    }

    private static String callModel(
            HttpClient client, String apiUrl, String apiKey, String model,
            double temperature, String prompt) throws IOException, InterruptedException {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("temperature", temperature);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(requestBody)))
                .build();
        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body();
            if (body.length() > 1000) {
                body = body.substring(0, 1000) + "...";
            }
            throw new IOException("LLM API returned HTTP " + response.statusCode() + ": " + body);
        }

        Object parsed = Json.parse(response.body());
        Map<?, ?> root = asMap(parsed, "response");
        List<?> choices = asList(root.get("choices"), "response.choices");
        if (choices.isEmpty()) {
            throw new IOException("LLM API response has no choices");
        }
        Map<?, ?> choice = asMap(choices.get(0), "response.choices[0]");
        Map<?, ?> message = asMap(choice.get("message"), "response.choices[0].message");
        Object content = message.get("content");
        if (!(content instanceof String text)) {
            throw new IOException("LLM API response content is not a string");
        }
        return text;
    }

    private static Map<?, ?> asMap(Object value, String path) throws IOException {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IOException("LLM API response field " + path + " is missing or invalid");
    }

    private static List<?> asList(Object value, String path) throws IOException {
        if (value instanceof List<?> list) {
            return list;
        }
        throw new IOException("LLM API response field " + path + " is missing or invalid");
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "");
    }

    private static Map<String, Object> summarize(List<Map<String, Object>> runs) {
        Map<String, Integer> outputCounts = new LinkedHashMap<>();
        Map<String, Integer> labelCounts = new LinkedHashMap<>();
        int validCount = 0;
        for (Map<String, Object> run : runs) {
            String output = (String) run.get("normalizedOutput");
            outputCounts.merge(output, 1, Integer::sum);
            if (LABELS.contains(output)) {
                labelCounts.merge(output, 1, Integer::sum);
                validCount++;
            }
        }

        int mostCommonCount = outputCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("completedRuns", runs.size());
        summary.put("distinctOutputs", outputCounts.size());
        summary.put("outputCounts", outputCounts);
        summary.put("labelCounts", labelCounts);
        summary.put("validLabelRate", (double) validCount / runs.size());
        summary.put("consistencyRate", (double) mostCommonCount / runs.size());
        return summary;
    }

    private static void writeJson(Path outputPath, Map<String, Object> document) throws IOException {
        Path absolutePath = outputPath.toAbsolutePath();
        Path parent = absolutePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(absolutePath, Json.stringify(document) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set the " + name + " environment variable");
        }
        return value;
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record PromptVariant(String id, String name, String prompt) {
    }
}
