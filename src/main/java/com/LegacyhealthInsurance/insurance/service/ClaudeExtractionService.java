package com.LegacyhealthInsurance.insurance.service;

import com.LegacyhealthInsurance.insurance.config.WorkflowDefinition;
import com.LegacyhealthInsurance.insurance.config.WorkflowDefinition.FieldDefinition;
import com.LegacyhealthInsurance.insurance.config.WorkflowLoader;
import com.LegacyhealthInsurance.insurance.dto.DocumentInfo;
import com.LegacyhealthInsurance.insurance.dto.ExtractResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * "Extract" button path: the server itself asks Claude to extract the fields, then saves them through the SAME
 * ExtractionService the MCP tool uses - so validation, MySQL, Excel and logging are identical.
 *
 * Two modes (insurance.claude.mode):
 *   cli - runs Claude Code headless ("claude -p") signed in with YOUR Claude subscription. No API key, no extra cost;
 *         usage counts against your plan limits. Good for a personal tool / POC.
 *   api - calls the Anthropic Messages API with ANTHROPIC_API_KEY (billed on the Claude Console). Use for production.
 */
@Service
public class ClaudeExtractionService {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final String TOOL_NAME = "save_extracted_fields";
    private static final int MAX_TEXT = 150_000;
    private static final int MAX_ATTEMPTS = 2;   // first try + one retry if the validator rejects it
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    private final String mode;
    private final String apiKey;
    private final String apiModel;
    private final String cliCommand;
    private final String cliModel;
    private final int cliTimeoutSeconds;
    private final ExtractionService extractionService;
    private final WorkflowLoader workflowLoader;
    private final AuditLogService audit;
    private final JsonMapper jsonMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private volatile String cliVersion;        // cached result of "claude --version"
    private volatile String cliProblem;

    public ClaudeExtractionService(@Value("${insurance.claude.mode:cli}") String mode,
                                   @Value("${insurance.anthropic.api-key:}") String apiKey,
                                   @Value("${insurance.anthropic.model:claude-sonnet-5-5}") String apiModel,
                                   @Value("${insurance.claude.cli-command:claude}") String cliCommand,
                                   @Value("${insurance.claude.cli-model:sonnet}") String cliModel,
                                   @Value("${insurance.claude.cli-timeout-seconds:180}") int cliTimeoutSeconds,
                                   ExtractionService extractionService, WorkflowLoader workflowLoader,
                                   AuditLogService audit, JsonMapper jsonMapper) {
        this.mode = mode.trim().toLowerCase();
        this.apiKey = apiKey;
        this.apiModel = apiModel;
        this.cliCommand = cliCommand;
        this.cliModel = cliModel;
        this.cliTimeoutSeconds = cliTimeoutSeconds;
        this.extractionService = extractionService;
        this.workflowLoader = workflowLoader;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
    }

    private boolean cliMode() {
        return !"api".equals(mode);
    }

    private String source() {
        return cliMode() ? "CLAUDE_CLI" : "CLAUDE_API";
    }

    public boolean isConfigured() {
        if (cliMode()) {
            checkCli();
            return cliVersion != null;
        }
        return apiKey != null && !apiKey.isBlank();
    }

    /** Shown on the dashboard next to the Extract buttons. */
    public String model() {
        return cliMode() ? "Claude Code " + (cliVersion == null ? "" : cliVersion + " ") + "(" + cliModel
                + ", your subscription)" : apiModel + " (API key)";
    }

    /** Why the buttons are disabled, or null when they work. */
    public String problem() {
        if (isConfigured()) {
            return null;
        }
        return cliMode() ? cliProblem
                : "Anthropic API key is not set. Set the ANTHROPIC_API_KEY environment variable and restart the "
                  + "server, or set insurance.claude.mode=cli to use your Claude subscription.";
    }

    /** Extract and save one document. */
    public ExtractResult extract(Long documentId) {
        if (!isConfigured()) {
            throw new IllegalStateException(problem());
        }
        String text = extractionService.documentText(documentId);   // throws IllegalArgumentException -> 404
        if (text == null || text.isBlank()) {
            return new ExtractResult(documentId, false, "Document has no text to extract.");
        }
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }

        WorkflowDefinition workflow = workflowLoader.load();
        audit.info(source(), documentId, "Extract button pressed - sending document to " + model());

        String feedback = null;
        String result = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Map<String, Object> fields;
            try {
                fields = cliMode() ? askCli(workflow, text, feedback) : askApi(workflow, text, feedback);
            } catch (IllegalStateException e) {
                audit.error(source(), documentId, "Claude call failed: " + e.getMessage());
                throw e;
            }
            result = extractionService.saveExtractedFields(documentId, fields, source());
            if (!result.startsWith("REJECTED")) {
                return new ExtractResult(documentId, result.startsWith("SAVED"), result);
            }
            feedback = "Your previous answer was " + jsonMapper.writeValueAsString(fields)
                    + "\nThe server rejected it: " + result + "\nFix these problems and answer again.";
        }
        return new ExtractResult(documentId, false, result);
    }

    /** "Extract all pending" button: one document at a time, a failure doesn't stop the rest. */
    public List<ExtractResult> extractAllPending() {
        List<ExtractResult> results = new ArrayList<>();
        for (DocumentInfo doc : extractionService.pendingDocuments()) {
            try {
                results.add(extract(doc.documentId()));
            } catch (RuntimeException e) {
                results.add(new ExtractResult(doc.documentId(), false, e.getMessage()));
            }
        }
        return results;
    }

    // ------------------------------------------------------------------ mode "cli": claude -p (subscription)

    @SuppressWarnings("unchecked")
    private Map<String, Object> askCli(WorkflowDefinition workflow, String documentText, String feedback) {
        // The whole prompt goes through stdin, so no document text ever lands on the command line.
        String prompt = instructions(workflow)
                + "\nDo not use any tools. Reply with ONLY one JSON object (no markdown, no explanation) whose keys "
                + "are exactly: " + workflow.fieldNames() + ". Use null for values that are not in the document.\n\n"
                + "<document>\n" + documentText + "\n</document>"
                + (feedback == null ? "" : "\n\n" + feedback);

        List<String> args = new ArrayList<>(List.of("-p", "--output-format", "json"));
        if (cliModel != null && !cliModel.isBlank()) {
            args.addAll(List.of("--model", cliModel));
        }
        CliOutput out = runCli(args, prompt, cliTimeoutSeconds);
        if (out.exitCode != 0 && out.stdout.isBlank()) {
            throw new IllegalStateException("claude -p exited with code " + out.exitCode + ": "
                    + abbreviate(out.stderr));
        }

        String answer;
        try {
            Map<String, Object> envelope = jsonMapper.readValue(out.stdout, new TypeReference<>() { });
            if (Boolean.TRUE.equals(envelope.get("is_error"))) {
                throw new IllegalStateException("Claude Code reported an error: " + abbreviate(
                        String.valueOf(envelope.getOrDefault("result", envelope.get("subtype")))));
            }
            answer = String.valueOf(envelope.getOrDefault("result", ""));
        } catch (JacksonException e) {
            answer = out.stdout;   // older CLI or plain-text output: try to find the JSON in it anyway
        }

        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("Claude did not return a JSON object: " + abbreviate(answer));
        }
        try {
            return jsonMapper.readValue(answer.substring(start, end + 1), new TypeReference<>() { });
        } catch (JacksonException e) {
            throw new IllegalStateException("Claude returned invalid JSON: " + abbreviate(answer), e);
        }
    }

    /** Runs "claude --version" once and caches the answer, so the page can enable/disable the buttons. */
    private void checkCli() {
        if (cliVersion != null) {
            return;
        }
        try {
            CliOutput out = runCli(List.of("--version"), "", 30);
            if (out.exitCode == 0 && !out.stdout.isBlank()) {
                cliVersion = out.stdout.trim().split("\\s+")[0];
                cliProblem = null;
            } else {
                cliProblem = "'" + cliCommand + " --version' failed (exit " + out.exitCode + "): "
                        + abbreviate(out.stderr.isBlank() ? out.stdout : out.stderr);
            }
        } catch (IllegalStateException e) {
            cliProblem = "Claude Code CLI not found. Install it, run 'claude' once to sign in, then restart the "
                    + "server (or set insurance.claude.cli-command to the full path). Details: " + e.getMessage();
        }
    }

    private record CliOutput(int exitCode, String stdout, String stderr) { }

    private CliOutput runCli(List<String> args, String stdin, int timeoutSeconds) {
        List<String> command = new ArrayList<>();
        if (WINDOWS) {
            // npm installs claude.cmd, which Windows can only start through cmd.exe. Arguments here are fixed
            // flags only (the prompt goes through stdin), so there is nothing for cmd.exe to misinterpret.
            command.addAll(List.of("cmd.exe", "/c"));
        }
        command.add(cliCommand);
        command.addAll(args);

        Path workDir;
        try {
            // Empty folder: Claude Code must not pick up this project's files or a CLAUDE.md.
            workDir = Files.createTempDirectory("claude-extract");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create a temp folder for Claude Code: " + e.getMessage(), e);
        }

        ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile());
        // Without an API key in the environment, Claude Code uses the signed-in Claude subscription.
        pb.environment().remove("ANTHROPIC_API_KEY");
        pb.environment().remove("ANTHROPIC_AUTH_TOKEN");

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start '" + cliCommand + "': " + e.getMessage(), e);
        }

        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        try (OutputStream in = process.getOutputStream()) {
            in.write(stdin.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // The process may have exited early; its stderr explains why.
        }

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Claude Code did not answer within " + timeoutSeconds + " seconds");
            }
            return new CliOutput(process.exitValue(), stdout.get(10, TimeUnit.SECONDS),
                    stderr.get(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Interrupted while waiting for Claude Code", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Could not read Claude Code output: " + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(workDir);
            } catch (IOException ignored) {
                // leftover empty temp folder - harmless
            }
        }
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    // ------------------------------------------------------------------ mode "api": Anthropic Messages API

    @SuppressWarnings("unchecked")
    private Map<String, Object> askApi(WorkflowDefinition workflow, String documentText, String feedback) {
        String userMessage = "Extract the fields from this insurance document by calling " + TOOL_NAME + ".\n\n"
                + "<document>\n" + documentText + "\n</document>"
                + (feedback == null ? "" : "\n\n" + feedback);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", apiModel);
        body.put("max_tokens", 2048);
        body.put("system", instructions(workflow));
        body.put("tools", List.of(Map.of(
                "name", TOOL_NAME,
                "description", "Save the fields extracted from the insurance document.",
                "input_schema", inputSchema(workflow))));
        body.put("tool_choice", Map.of("type", "tool", "name", TOOL_NAME));
        body.put("messages", List.of(Map.of("role", "user", "content", userMessage)));

        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(90))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach the Anthropic API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the Anthropic API", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Anthropic API returned HTTP " + response.statusCode() + ": "
                    + abbreviate(response.body()));
        }

        Map<String, Object> root = jsonMapper.readValue(response.body(), new TypeReference<>() { });
        for (Object block : (List<Object>) root.getOrDefault("content", List.of())) {
            Map<String, Object> b = (Map<String, Object>) block;
            if ("tool_use".equals(b.get("type")) && b.get("input") instanceof Map<?, ?> input) {
                return new LinkedHashMap<>((Map<String, Object>) input);
            }
        }
        throw new IllegalStateException("Claude did not return the extracted fields (stop_reason="
                + root.get("stop_reason") + ")");
    }

    /** JSON schema built from workflow.yaml, so new fields need no code change. */
    private Map<String, Object> inputSchema(WorkflowDefinition workflow) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (FieldDefinition f : workflow.fields()) {
            String jsonType = switch (f.type()) {
                case "number" -> "number";
                case "boolean" -> "boolean";
                default -> "string";
            };
            String description = f.description() + ("date".equals(f.type()) ? " (YYYY-MM-DD)" : "");
            properties.put(f.name(), Map.of(
                    // null is always allowed, so Claude never has to invent a value; FieldValidator
                    // still rejects a missing required field.
                    "type", List.of(jsonType, "null"),
                    "description", description));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", workflow.fieldNames());
        schema.put("additionalProperties", false);
        return schema;
    }

    // ------------------------------------------------------------------ shared

    /** Instructions from workflow.yaml plus the rules learned while testing. */
    private String instructions(WorkflowDefinition workflow) {
        StringBuilder sb = new StringBuilder(workflow.instructions()).append("\n\n")
                .append("Rules:\n")
                .append("- Amounts: plain numbers only. Indian grouping too: 'Rs. 10,00,000' -> 1000000.\n")
                .append("- Dates: YYYY-MM-DD (01-Apr-2026 -> 2026-04-01). 'Policy Period A to B' gives "
                        + "startDate A and expiryDate B.\n")
                .append("- policyHolderName: drop labels such as '(fictional)' or '(Self)'.\n")
                .append("- If the co-pay depends on a condition, use the highest percentage and explain "
                        + "the condition in summary.\n")
                .append("- Use null for any value that is not in the document. Never guess.\n\n")
                .append("Fields:\n");
        for (FieldDefinition f : workflow.fields()) {
            sb.append("- ").append(f.name()).append(" (").append(f.type())
                    .append(f.required() ? ", required" : "").append("): ").append(f.description()).append('\n');
        }
        return sb.toString();
    }

    private static String abbreviate(String s) {
        return s == null ? "" : (s.length() <= 500 ? s : s.substring(0, 500) + "...");
    }
}
