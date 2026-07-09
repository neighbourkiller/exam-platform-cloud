package com.ekusys.exam.importing;

public class RowImportException extends RuntimeException {
    private final String field;
    private final String rawValue;

    public RowImportException(String field, String message, String rawValue) {
        super(message);
        this.field = field;
        this.rawValue = rawValue;
    }

    public String getField() {
        return field;
    }

    public String getRawValue() {
        return rawValue;
    }
}
