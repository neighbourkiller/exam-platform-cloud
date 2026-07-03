package com.ekusys.exam.iam.api;

import java.util.List;

public record UserBatchRequest(List<Long> userIds) {
}
