package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.config.StudentProperties;
import com.cnsportiot.cloud.domain.entity.Account;
import com.cnsportiot.cloud.domain.entity.Student;
import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.cloud.repository.AccountRepository;
import com.cnsportiot.cloud.repository.StudentRepository;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
/**
 * §6.1 / §5.7 学生建档：验证按学号创建 account + student 的事务流程，以及重复学号冲突时抛出业务异常。
 */
class StudentProvisioningServiceImplTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private StudentProperties studentProperties;

    @InjectMocks
    private StudentProvisioningServiceImpl service;

    // §5.7 / §6.1 建档：按学号创建学生账号，账号状态应为 PENDING_ACTIVATION，且同时写入 student 表。
    @Test
    void provisionByStudentNo_createsAccountAndStudent() {
        UUID studentId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String studentNo = "2024012345";

        given(studentProperties.initialPasswordFor(studentNo)).willReturn(studentNo);
        given(passwordEncoder.encode(studentNo)).willReturn("hashed-password");
        given(accountRepository.save(any(Account.class))).willAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            ReflectionTestUtils.setField(account, "id", accountId);
            return account;
        });
        given(studentRepository.save(any(Student.class))).willAnswer(invocation -> {
            Student student = invocation.getArgument(0);
            ReflectionTestUtils.setField(student, "id", studentId);
            return student;
        });

        UUID result = service.provisionByStudentNo(studentNo, "Alice");

        assertThat(result).isEqualTo(studentId);
        then(accountRepository).should().save(org.mockito.ArgumentMatchers.argThat(account ->
                account.getUsername().equals(studentNo)
                        && account.getDisplayName().equals("Alice")
                        && account.getStatus() == AccountStatus.PENDING_ACTIVATION
                        && "hashed-password".equals(account.getPasswordHash())));
        then(studentRepository).should().save(org.mockito.ArgumentMatchers.argThat(student ->
                student.getStudentNo().equals(studentNo)
                        && student.getAccountId().equals(accountId)));
    }

    // 唯一约束冲突：并发或重复建档时，必须转成 DUPLICATE_IDENTIFIER 业务异常，避免半成品数据。
    @Test
    void provisionByStudentNo_duplicateIdentifier_throwsBusinessException() {
        String studentNo = "2024012345";
        given(studentProperties.initialPasswordFor(studentNo)).willReturn(studentNo);
        given(passwordEncoder.encode(studentNo)).willReturn("hashed-password");
        given(accountRepository.save(any(Account.class))).willThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.provisionByStudentNo(studentNo, "Alice"))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.DUPLICATE_IDENTIFIER);
    }
}
