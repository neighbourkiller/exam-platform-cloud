package com.ekusys.exam.grading.controller;
import com.ekusys.exam.common.api.ApiResponse;import com.ekusys.exam.grading.service.GradingService;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/internal/v1/grading") public class InternalGradingController{private final GradingService service;public InternalGradingController(GradingService service){this.service=service;}@PostMapping("/submissions/{id}/process") public ApiResponse<Void> process(@PathVariable Long id){service.processSubmission(id);return ApiResponse.ok(null);}}
