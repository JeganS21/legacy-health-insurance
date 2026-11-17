package com.LegacyhealthInsurance.insurance.service;

import com.LegacyhealthInsurance.insurance.config.WorkflowDefinition;
import com.LegacyhealthInsurance.insurance.config.WorkflowDefinition.FieldDefinition;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks Claude's answer against workflow.yaml BEFORE anything is saved.
 * The server never trusts the AI blindly - errors are sent back so Claude can fix them.
 */
@Component
public class FieldValidator {

    public record Result(Map<String, Object> cleanValues, List<String> errors) {
        public boolean isValid() { return errors.isEmpty(); }
    }

    public Result validate(WorkflowDefinition workflow, Map<String, Object> input) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> clean = new LinkedHashMap<>();
        Set<String> known = Set.copyOf(workflow.fieldNames());

        for (String key : input.keySet()) {
            if (!known.contains(key)) {
                errors.add("Unknown field '" + key + "'. Allowed fields: " + workflow.fieldNames());
            }
        }

        for (FieldDefinition field : workflow.fields()) {
            Object raw = input.get(field.name());
            boolean empty = raw == null || raw.toString().isBlank() || "null".equalsIgnoreCase(raw.toString());

            if (empty) {
                if (field.required()) {
                    errors.add("Required field '" + field.name() + "' is missing");
                }
                clean.put(field.name(), null);
                continue;
            }

            String text = raw.toString().trim();
            try {
                switch (field.type()) {
                    case "number" -> clean.put(field.name(),
                            new BigDecimal(text.replace(",", "").replace("$", "").replace("₹", "").trim()));
                    case "date" -> clean.put(field.name(), LocalDate.parse(text));
                    case "boolean" -> clean.put(field.name(), Boolean.parseBoolean(text));
                    default -> clean.put(field.name(), text);
                }
            } catch (NumberFormatException e) {
                errors.add("Field '" + field.name() + "' must be a number, got '" + text + "'");
            } catch (DateTimeParseException e) {
                errors.add("Field '" + field.name() + "' must be a date in YYYY-MM-DD format, got '" + text + "'");
            }
        }
        return new Result(clean, errors);
    }
}
