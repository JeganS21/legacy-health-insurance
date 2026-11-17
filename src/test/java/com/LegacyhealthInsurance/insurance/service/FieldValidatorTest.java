package com.LegacyhealthInsurance.insurance.service;

import com.LegacyhealthInsurance.insurance.config.WorkflowDefinition;
import com.LegacyhealthInsurance.insurance.config.WorkflowDefinition.FieldDefinition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks that bad AI output is rejected before it reaches the database or Excel. No database needed. */
class FieldValidatorTest {

    private final FieldValidator validator = new FieldValidator();

    private final WorkflowDefinition workflow = new WorkflowDefinition("Policies", "test", List.of(
            new FieldDefinition("policyNumber", "text", true, ""),
            new FieldDefinition("sumInsured", "number", false, ""),
            new FieldDefinition("expiryDate", "date", false, "")));

    @Test
    void validInputIsCleaned() {
        var result = validator.validate(workflow,
                Map.of("policyNumber", "P-100", "sumInsured", "5,00,000", "expiryDate", "2027-03-31"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.cleanValues().get("sumInsured")).isEqualTo(new BigDecimal("500000"));
        assertThat(result.cleanValues().get("expiryDate")).isEqualTo(LocalDate.of(2027, 3, 31));
    }

    @Test
    void missingRequiredFieldIsRejected() {
        Map<String, Object> input = new HashMap<>();
        input.put("policyNumber", null);

        var result = validator.validate(workflow, input);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("policyNumber"));
    }

    @Test
    void wrongTypesAndUnknownFieldsAreRejected() {
        var result = validator.validate(workflow,
                Map.of("policyNumber", "P-1", "sumInsured", "five lakh", "expiryDate", "31/03/2027", "hacker", "x"));

        assertThat(result.errors()).hasSize(3);
    }
}
