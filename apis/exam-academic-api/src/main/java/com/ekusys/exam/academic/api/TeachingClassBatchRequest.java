package com.ekusys.exam.academic.api;

import java.util.List;

public record TeachingClassBatchRequest(List<Long> classIds) {
}
