package io.micronaut.data.nitrite.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable
public class NestedPojo {
    private String camelCaseField;
    private String snakeCaseField;

    public NestedPojo() {}

    public NestedPojo(String camelCaseField, String snakeCaseField) {
        this.camelCaseField = camelCaseField;
        this.snakeCaseField = snakeCaseField;
    }

    public String getCamelCaseField() {
        return camelCaseField;
    }

    public void setCamelCaseField(String camelCaseField) {
        this.camelCaseField = camelCaseField;
    }

    public String getSnake_case_field() {
        return snakeCaseField;
    }

    public void setSnake_case_field(String snakeCaseField) {
        this.snakeCaseField = snakeCaseField;
    }
}
