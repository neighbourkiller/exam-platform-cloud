package com.ekusys.exam.importing;

public record BulkImportRowErrorView(Integer rowNumber, String field, String message, String rawValue) {
}
