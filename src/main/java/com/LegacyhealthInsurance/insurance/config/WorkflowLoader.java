package com.LegacyhealthInsurance.insurance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads workflow.yaml every time it is needed, so you can change the
 * instructions or fields without restarting the server.
 */
@Component
public class WorkflowLoader {

    private final Path workflowPath;

    public WorkflowLoader(@Value("${insurance.workflow-path}") String workflowPath) {
        this.workflowPath = Path.of(workflowPath);
    }

    @SuppressWarnings("unchecked")
    public WorkflowDefinition load() {
        try (InputStream in = Files.newInputStream(workflowPath)) {
            Map<String, Object> root = new Yaml().load(in);

            List<WorkflowDefinition.FieldDefinition> fields = new ArrayList<>();
            for (Map<String, Object> f : (List<Map<String, Object>>) root.get("fields")) {
                fields.add(new WorkflowDefinition.FieldDefinition(
                        String.valueOf(f.get("name")),
                        String.valueOf(f.getOrDefault("type", "text")).toLowerCase(),
                        Boolean.TRUE.equals(f.get("required")),
                        String.valueOf(f.getOrDefault("description", ""))));
            }
            return new WorkflowDefinition(
                    String.valueOf(root.getOrDefault("sheetName", "Policies")),
                    String.valueOf(root.getOrDefault("instructions", "")).trim(),
                    fields);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read workflow file " + workflowPath.toAbsolutePath(), e);
        }
    }
}
