package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.config.StudentProperties;
import com.cnsportiot.cloud.domain.entity.Account;
import com.cnsportiot.cloud.domain.entity.Student;
import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.cloud.repository.AccountRepository;
import com.cnsportiot.cloud.repository.StudentRepository;
import com.cnsportiot.cloud.service.StudentProvisioningService;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StudentProvisioningServiceImpl implements StudentProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(StudentProvisioningServiceImpl.class);

    private final AccountRepository accountRepository;
    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;
    private final StudentProperties studentProperties;

    @Override
    @Transactional
    public UUID provisionByStudentNo(String studentNo, String displayName) {
        Account account = Account.createStudent(
                studentNo,
                null, null,
                passwordEncoder.encode(studentProperties.initialPasswordFor(studentNo)));
        account.setDisplayName(displayName);
        // 建档账号一律待激活
        account.setStatus(AccountStatus.PENDING_ACTIVATION);

        try {
            Account saved = accountRepository.save(account);
            Student student = studentRepository.save(Student.create(saved.getId(), studentNo));

            log.info("学生建档 studentNo={} displayName={} studentId={}",
                    studentNo, displayName, student.getId());
            return student.getId();

        } catch (DataIntegrityViolationException e) {
            // 并发下两个请求同时为一个学号建档,后到的撞唯一约束
            throw new BusinessException(ErrorCode.DUPLICATE_IDENTIFIER,
                    "学号或用户名已存在: " + studentNo, null);
        }
    }
}

