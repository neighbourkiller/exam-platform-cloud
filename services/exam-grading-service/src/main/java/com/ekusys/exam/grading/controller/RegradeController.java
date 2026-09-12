package com.ekusys.exam.grading.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.audit.AuditOperation;
import com.ekusys.exam.grading.dto.RegradeRequest;
import com.ekusys.exam.grading.service.RegradeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/grading/exams/{examId}")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class RegradeController {
    private final RegradeService service;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public RegradeController(RegradeService service, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.service = service; this.mapper = mapper;
    }
    private ApiResponse<com.fasterxml.jackson.databind.JsonNode> safe(Object value) {
        return ApiResponse.ok(safeNode(mapper.valueToTree(value)));
    }
    private com.fasterxml.jackson.databind.JsonNode safeNode(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isIntegralNumber() && node.bigIntegerValue().abs().compareTo(java.math.BigInteger.valueOf(9007199254740991L)) > 0) {
            return mapper.getNodeFactory().textNode(node.asText());
        }
        if (node.isObject()) {
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            var names = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) object.set(name, safeNode(object.get(name)));
        } else if (node.isArray()) {
            var array = (com.fasterxml.jackson.databind.node.ArrayNode) node;
            for (int i = 0; i < array.size(); i++) array.set(i, safeNode(array.get(i)));
        }
        return node;
    }
    @GetMapping("/answer-key")
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> key(@PathVariable Long examId) { return safe(service.answerKey(examId)); }
    @PostMapping("/regrades")
    @AuditOperation(action="EXAM_REGRADE", targetType="EXAM", targetId="#examId")
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> create(@PathVariable Long examId, @Valid @RequestBody RegradeRequest request) {
        return safe(Map.of("jobId", service.create(examId, request, null)));
    }
    @PostMapping("/answer-key/versions/{version}/restore")
    @AuditOperation(action="EXAM_REGRADE_RESTORE", targetType="EXAM", targetId="#examId")
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> restore(@PathVariable Long examId, @PathVariable Long version,
                                                  @Valid @RequestBody RegradeRequest request) {
        return safe(Map.of("jobId", service.create(examId, request, version)));
    }
    @GetMapping("/regrades")
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> history(@PathVariable Long examId, @RequestParam(defaultValue="1") int page) {
        return safe(service.history(examId, page));
    }
    @GetMapping("/regrades/{jobId}")
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> detail(@PathVariable Long examId, @PathVariable Long jobId,
                                                  @RequestParam(defaultValue="1") int page) {
        return safe(service.detail(examId, jobId, page));
    }
    @PostMapping("/regrades/{jobId}/retry")
    public ApiResponse<Void> retry(@PathVariable Long examId, @PathVariable Long jobId) {
        service.retry(examId, jobId); return ApiResponse.ok(null);
    }
    @GetMapping("/regrades/{jobId}/bank-sync/{syncId}/context")
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> bankContext(@PathVariable Long examId, @PathVariable Long jobId, @PathVariable Long syncId) {
        return safe(service.bankContext(examId, jobId, syncId));
    }
    public record BankRetry(@NotBlank @Size(max=64) String fingerprint) {}
    @PostMapping("/regrades/{jobId}/bank-sync/{syncId}/retry")
    @AuditOperation(action="REGRADE_BANK_RETRY", targetType="EXAM", targetId="#examId")
    public ApiResponse<Void> retryBank(@PathVariable Long examId, @PathVariable Long jobId, @PathVariable Long syncId,
                                       @Valid @RequestBody BankRetry request) {
        service.retryBank(examId, jobId, syncId, request.fingerprint()); return ApiResponse.ok(null);
    }
}
