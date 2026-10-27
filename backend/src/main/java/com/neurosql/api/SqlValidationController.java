package com.neurosql.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sql")
@ConditionalOnProperty(prefix = "neurosql.sql-validator", name = "enabled", havingValue = "true")
public class SqlValidationController {
    private final ReadOnlySqlValidator validator;

    public SqlValidationController(ReadOnlySqlValidator validator) {
        this.validator = validator;
    }

    @PostMapping(path = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ReadOnlySqlValidator.ValidationResponse validate(@Valid @RequestBody ValidationRequest request) {
        return validator.validate(request.sql());
    }

    public record ValidationRequest(
            @NotBlank(message = "SQL is required.")
            @Size(max = 10_000, message = "SQL must be 10000 characters or fewer.")
            String sql) {}
}
