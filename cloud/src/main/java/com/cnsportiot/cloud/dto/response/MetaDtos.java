package com.cnsportiot.cloud.dto.response;

import java.util.List;
import java.util.Map;

public final class MetaDtos {
    private MetaDtos() {}

    /** §12.1 GET /api/meta/vocabulary。 */
    public record VocabularyResponse(
            String version,
            List<ActionVocab> actions,
            List<CheckpointVocab> checkpoints,
            PhasesVocab phases) {}

    public record ActionVocab(String id, String label, List<String> cameras) {}

    public record CheckpointVocab(String id, String label, boolean safety, List<String> actionTypes) {}

    public record PhasesVocab(Map<String, List<String>> byActionType, Map<String, String> labels) {}
}
