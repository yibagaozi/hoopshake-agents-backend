package com.cnsportiot.edge.domain;

import java.util.UUID;

/** 参课名单条目,来自云端的本地缓存 */
public record RosterEntry(
        UUID studentId,
        String studentNo,
        String displayName,
        String dominantHand,
        UUID galleryId,
        Integer galleryVersion,
        String galleryUri,
        String faceModel,
        String bodyModel) {

    public boolean galleryReady() {
        return galleryUri != null;
    }
}
