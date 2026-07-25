package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.AuditableEntity;
import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.cloud.domain.enums.Role;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "account")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Account extends AuditableEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(unique = true, length = 128)
    private String email;

    @Column(unique = true, length = 32)
    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(name = "staff_no", unique = true, length = 32)
    private String staffNo;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private AccountStatus status = AccountStatus.ACTIVE;

    /** 工厂方法:创建学生账号 */
    public static Account createStudent(String username, String email, String phone, String passwordHash) {
        return Account.builder()
            .username(username).email(email).phone(phone)
            .passwordHash(passwordHash).role(Role.STUDENT).build();
    }

    /** 工厂方法:创建教师账号 */
    public static Account createTeacher(String username, String staffNo, String passwordHash) {
        return Account.builder()
            .username(username).staffNo(staffNo)
            .passwordHash(passwordHash).role(Role.TEACHER).build();
    }
}