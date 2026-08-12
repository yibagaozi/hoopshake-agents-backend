package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.dto.request.IngestRequests.*;
import com.cnsportiot.cloud.dto.response.IngestDtos.*;
import com.cnsportiot.cloud.service.IngestService;
import com.cnsportiot.contracts.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/ingest")
@RequiredArgsConstructor
public class IngestController {

    private final IngestService ingestService;

    @PutMapping("/sessions/{sessionId}")
    public ApiResponse<IngestAckResponse> upsertSession(
            @PathVariable UUID sessionId,
            @Valid @RequestBody UpsertSessionRequest request) {
        return ApiResponse.ok(ingestService.upsertSession(sessionId, request));
    }

    @PostMapping("/action-clips")
    public ApiResponse<BatchAckResponse> ingestActionClips(
            @Valid @RequestBody ActionClipBatchRequest request) {
        return ApiResponse.ok(ingestService.ingestActionClips(request));
    }

    @PostMapping("/feedback")
    public ApiResponse<BatchAckResponse> ingestFeedback(
            @Valid @RequestBody InstantFeedbackBatchRequest request) {
        return ApiResponse.ok(ingestService.ingestFeedback(request));
    }

    @PostMapping("/gallery/register")
    public ApiResponse<GalleryRegisteredResponse> registerGallery(
            @Valid @RequestBody RegisterGalleryRequest request) {
        return ApiResponse.ok(ingestService.registerGallery(request));
    }

    @GetMapping("/gallery/pull")
    public ApiResponse<GalleryPullResponse> pullGallery(@RequestParam UUID lessonId) {
        return ApiResponse.ok(ingestService.pullGallery(lessonId));
    }
}


