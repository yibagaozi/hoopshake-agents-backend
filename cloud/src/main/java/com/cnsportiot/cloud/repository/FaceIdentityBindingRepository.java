package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.FaceIdentityBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FaceIdentityBindingRepository extends JpaRepository<FaceIdentityBinding, UUID> {

    Optional<FaceIdentityBinding> findByGlobalId(String globalId);

    Optional<FaceIdentityBinding> findByStudentId(UUID studentId);

    List<FaceIdentityBinding> findByStudentIdIn(Collection<UUID> studentIds);
}
