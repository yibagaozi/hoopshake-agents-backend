package com.cnsportiot.cloud.dto.response;

import java.util.List;

public final class MetaDtos {
    private MetaDtos() {}

    /** §12.1 GET /api/meta/vocabulary。 */
    public record VocabularyResponse(
            String version,
            List<ActionVocab> actions,
            List<CheckpointVocab> checkpoints) {}

    public record ActionVocab(String id, String label, List<String> cameras) {}

    public record CheckpointVocab(String id, String label, boolean safety, List<String> actionTypes) {}
}
