package com.cnsportiot.cloud.ops.controller;

import com.cnsportiot.cloud.ops.dto.EdgeTelemetryAck;
import com.cnsportiot.cloud.ops.dto.EdgeTelemetryRequest;
import com.cnsportiot.cloud.ops.service.EdgeTelemetryService;
import com.cnsportiot.contracts.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingest/edge/telemetry")
@RequiredArgsConstructor
public class EdgeTelemetryController {

    private final EdgeTelemetryService telemetryService;

    @PostMapping
    public ApiResponse<EdgeTelemetryAck> ingest(@Valid @RequestBody EdgeTelemetryRequest request) {
        return ApiResponse.ok(telemetryService.ingest(request));
    }
}
