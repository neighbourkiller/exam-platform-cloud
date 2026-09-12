package com.ekusys.exam.runtime.api;

import java.util.List;

public record SubmittedPage(List<Long> submissionIds, long upperBound, long total) {}
