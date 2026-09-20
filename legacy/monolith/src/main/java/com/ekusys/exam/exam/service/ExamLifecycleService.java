package com.ekusys.exam.exam.service;

import com.ekusys.exam.common.enums.ExamStatus;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.ExamCreateRequest;
import com.ekusys.exam.exam.dto.ProctoringPolicyView;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamTargetClass;
import com.ekusys.exam.repository.entity.Paper;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.ekusys.exam.repository.mapper.PaperMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ExamLifecycleService.class);
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_TEACHER = "TEACHER";

    private final PaperMapper paperMapper;
    private final ExamMapper examMapper;
    private final ExamTargetClassMapper examTargetClassMapper;
    private final TeachingClassMapper teachingClassMapper;
    private final ExamAccessService examAccessService;
    private final ExamProctoringPolicyService proctoringPolicyService;

    public ExamLifecycleService(PaperMapper paperMapper,
                                ExamMapper examMapper,
                                ExamTargetClassMapper examTargetClassMapper,
                                TeachingClassMapper teachingClassMapper,
                                ExamAccessService examAccessService,
                                ExamProctoringPolicyService proctoringPolicyService) {
        this.paperMapper = paperMapper;
        this.examMapper = examMapper;
        this.examTargetClassMapper = examTargetClassMapper;
        this.teachingClassMapper = teachingClassMapper;
        this.examAccessService = examAccessService;
        this.proctoringPolicyService = proctoringPolicyService;
    }

    @Transactional
    public Long createExam(ExamCreateRequest request) {
        Paper paper = paperMapper.selectById(request.getPaperId());
        if (paper == null) {
            throw new BusinessException("试卷不存在");
        }
        Long paperSubjectId = paper.getSubjectId();
        if (paperSubjectId == null) {
            throw new BusinessException("试卷未绑定课程，无法发布考试");
        }

        Set<Long> targetClassIds = request.getTargetClassIds().stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (targetClassIds.isEmpty()) {
            throw new BusinessException("目标教学班不能为空");
        }

        var teachingClasses = teachingClassMapper.selectBatchIds(targetClassIds);
        if (teachingClasses.size() != targetClassIds.size()) {
            throw new BusinessException("存在无效教学班ID");
        }
        Map<Long, TeachingClass> teachingClassMap = teachingClasses.stream()
            .collect(Collectors.toMap(TeachingClass::getId, item -> item, (a, b) -> a));

        Long publisherId = examAccessService.getCurrentUserId();
        Set<String> publisherRoleCodes = examAccessService.getCurrentRoleCodes();
        for (Long classId : targetClassIds) {
            TeachingClass teachingClass = teachingClassMap.get(classId);
            if (teachingClass == null) {
                throw new BusinessException("存在无效教学班ID");
            }
            if (!Objects.equals(paperSubjectId, teachingClass.getSubjectId())) {
                throw new BusinessException("目标教学班课程与试卷课程不一致");
            }
            examAccessService.validateTeachingClassTeacher(teachingClass);
            if (!publisherRoleCodes.contains(ROLE_ADMIN)
                && publisherRoleCodes.contains(ROLE_TEACHER)
                && !Objects.equals(publisherId, teachingClass.getTeacherId())) {
                throw new BusinessException("仅可为自己授课的教学班发布考试");
            }
        }

        Exam exam = new Exam();
        ProctoringPolicyView proctoringPolicy = proctoringPolicyService.normalizeForCreate(
            request.getProctoringLevel(),
            request.getProctoringPolicy()
        );
        exam.setName(request.getName());
        exam.setPaperId(request.getPaperId());
        exam.setStartTime(request.getStartTime());
        exam.setEndTime(request.getEndTime());
        exam.setDurationMinutes(request.getDurationMinutes());
        exam.setPassScore(request.getPassScore());
        exam.setStatus(ExamStatus.DRAFT.name());
        exam.setPublisherId(publisherId);
        exam.setProctoringLevel(proctoringPolicy.getLevel());
        exam.setProctoringConfigJson(proctoringPolicyService.toJson(proctoringPolicy));
        examMapper.insert(exam);

        for (Long classId : targetClassIds) {
            ExamTargetClass target = new ExamTargetClass();
            target.setExamId(exam.getId());
            target.setClassId(classId);
            examTargetClassMapper.insert(target);
        }
        log.info("Exam created: examId={}, publisherId={}, paperId={}, targetClassCount={}",
            exam.getId(), publisherId, request.getPaperId(), targetClassIds.size());
        return exam.getId();
    }

    @Transactional
    public void publishExam(Long examId) {
        Exam exam = examAccessService.ensureExam(examId);
        examAccessService.ensureExamManagePermission(exam);
        if (ExamStatus.TERMINATED.name().equals(exam.getStatus())) {
            throw new BusinessException("考试已终止，不能再次发布");
        }
        if (ExamStatus.FINISHED.name().equals(exam.getStatus())) {
            throw new BusinessException("考试已结束，不能再次发布");
        }
        exam.setStatus(ExamStatus.PUBLISHED.name());
        examMapper.updateById(exam);
        log.info("Exam published: examId={}, publisherId={}", examId, examAccessService.getCurrentUserId());
    }

    @Transactional
    public void terminateExam(Long examId) {
        Exam exam = examAccessService.ensureExam(examId);
        examAccessService.ensureExamManagePermission(exam);
        if (ExamStatus.TERMINATED.name().equals(exam.getStatus())) {
            return;
        }
        exam.setEndTime(LocalDateTime.now());
        exam.setStatus(ExamStatus.TERMINATED.name());
        examMapper.updateById(exam);
        log.info("Exam terminated: examId={}, operatorId={}", examId, examAccessService.getCurrentUserId());
    }
}
