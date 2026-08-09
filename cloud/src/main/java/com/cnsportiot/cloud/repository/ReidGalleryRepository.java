package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.ReidGallery;
import com.cnsportiot.contracts.enums.GalleryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReidGalleryRepository extends JpaRepository<ReidGallery, UUID> {

    Optional<ReidGallery> findByStudentIdAndStatus(UUID studentId, GalleryStatus status);

    List<ReidGallery> findByStudentIdInAndStatus(List<UUID> studentIds, GalleryStatus status);
}
