package com.LegacyhealthInsurance.insurance.config;

import java.util.List;

/** The workflow.yaml file as Java objects. */
public record WorkflowDefinition(String sheetName, String instructions, List<FieldDefinition> fields) {

    public record FieldDefinition(String name, String type, boolean required, String description) {
    }

    public List<String> fieldNames() {
        return fields.stream().map(FieldDefinition::name).toList();
    }
}
