package com.ekusys.exam.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.academic.api.ClassRosterView;
import com.ekusys.exam.academic.api.SubjectSummary;
import com.ekusys.exam.academic.api.TeachingClassBatchRequest;
import com.ekusys.exam.academic.api.TeachingClassSummary;
import com.ekusys.exam.common.enums.ExamStatus;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.content.api.PaperSnapshotView;
import com.ekusys.exam.content.api.PaperSummary;
import com.ekusys.exam.exam.dto.ExamCreateRequest;
import com.ekusys.exam.exam.dto.TeacherExamView;
import com.ekusys.exam.management.api.RuntimeExamSnapshot;
import com.ekusys.exam.management.client.AcademicRosterClient;
import com.ekusys.exam.management.client.ContentSnapshotClient;
import com.ekusys.exam.management.messaging.ManagementOutboxService;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamTargetClass;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamManagementService {
 private final ExamMapper examMapper; private final ExamTargetClassMapper targetMapper; private final JdbcTemplate jdbc;
 private final AcademicRosterClient academic; private final ContentSnapshotClient content; private final ObjectMapper mapper;
 private final ManagementOutboxService outbox;
 public ExamManagementService(ExamMapper examMapper, ExamTargetClassMapper targetMapper, JdbcTemplate jdbc,
  AcademicRosterClient academic, ContentSnapshotClient content, ObjectMapper mapper, ManagementOutboxService outbox){this.examMapper=examMapper;this.targetMapper=targetMapper;this.jdbc=jdbc;this.academic=academic;this.content=content;this.mapper=mapper;this.outbox=outbox;}

 @Transactional public Long create(ExamCreateRequest request){
  validateCreate(request);
  Exam exam=new Exam(); exam.setName(request.getName()); exam.setPaperId(request.getPaperId()); exam.setStartTime(request.getStartTime());
  exam.setEndTime(request.getEndTime()); exam.setDurationMinutes(request.getDurationMinutes()); exam.setPassScore(request.getPassScore());
  exam.setStatus(ExamStatus.DRAFT.name()); exam.setPublisherId(SecurityUtils.getCurrentUserId());
  exam.setProctoringLevel(request.getProctoringLevel()==null?"STANDARD":request.getProctoringLevel());
  try { exam.setProctoringConfigJson(request.getProctoringPolicy()==null?null:mapper.writeValueAsString(request.getProctoringPolicy())); }
  catch(Exception ex){throw new BusinessException("监考策略格式错误");}
  examMapper.insert(exam); for(Long classId:request.getTargetClassIds().stream().distinct().toList()){ ExamTargetClass link=new ExamTargetClass();link.setExamId(exam.getId());link.setClassId(classId);targetMapper.insert(link); }
  return exam.getId();
 }

 @Transactional public Long createAndOptionallyPublish(ExamCreateRequest request, boolean autoPublish){
  Long id=create(request); if(autoPublish) publish(id); return id;
 }

 public void validateCreate(ExamCreateRequest request){
  if(request.getStartTime()==null||request.getEndTime()==null||!request.getStartTime().isBefore(request.getEndTime())) throw new BusinessException("考试开始时间必须早于结束时间");
  if(request.getDurationMinutes()==null||request.getDurationMinutes()<1) throw new BusinessException("考试时长必须大于0");
  if(request.getPassScore()==null||request.getPassScore()<0) throw new BusinessException("及格分不能小于0");
  List<Long> classIds=request.getTargetClassIds()==null?List.of():request.getTargetClassIds().stream().filter(Objects::nonNull).distinct().toList();
  if(classIds.isEmpty()) throw new BusinessException("目标教学班不能为空");
  PaperSummary paper=content.summary(request.getPaperId()).getData();
  if(paper==null) throw new BusinessException("试卷不存在");
  if(paper.subjectId()==null) throw new BusinessException("试卷未绑定课程，无法发布考试");
  List<TeachingClassSummary> classes=academic.classSummaries(new TeachingClassBatchRequest(classIds)).getData();
  if(classes==null||classes.size()!=classIds.size()) throw new BusinessException("存在无效教学班ID");
  for(TeachingClassSummary value:classes) if(!Objects.equals(paper.subjectId(),value.subjectId())) throw new BusinessException("目标教学班课程与试卷课程不一致");
 }

 @Transactional public void publish(Long id){
  Exam exam=requireManage(id); if(!ExamStatus.DRAFT.name().equals(exam.getStatus())) throw new BusinessException("只有草稿考试可以发布");
  PaperSnapshotView snapshot=content.create(exam.getPaperId()).getData(); jdbc.update("delete from exam_candidate where exam_id=?",id);
  Map<Long, String> classNames = new LinkedHashMap<>();
  List<ExamTargetClass> targets=targetMapper.selectList(new LambdaQueryWrapper<ExamTargetClass>().eq(ExamTargetClass::getExamId,id));
  for(ExamTargetClass target:targets){ ClassRosterView roster=academic.roster(target.getClassId()).getData(); classNames.put(target.getClassId(), roster.className()); for(Long studentId:roster.studentIds()) jdbc.update(
   "insert ignore into exam_candidate(id,exam_id,student_id,class_id,roster_version,created_at) values(?,?,?,?,?,current_timestamp(3))",IdWorker.getId(),id,studentId,target.getClassId(),roster.version()); }
  jdbc.update("insert into exam_paper_ref(id,exam_id,paper_snapshot_id,snapshot_version,created_at) values(?,?,?,?,current_timestamp(3)) on duplicate key update paper_snapshot_id=values(paper_snapshot_id),snapshot_version=values(snapshot_version)",IdWorker.getId(),id,snapshot.snapshotId(),snapshot.version());
  exam.setStatus(ExamStatus.PUBLISHED.name()); examMapper.updateById(exam);
  SubjectSummary subject = academic.subject(snapshot.subjectId()).getData();
  outbox.examPublished(exam, snapshot, subject, classNames);
 }
 @Transactional public void terminate(Long id){Exam exam=requireManage(id);exam.setStatus(ExamStatus.TERMINATED.name());examMapper.updateById(exam);outbox.examTerminated(exam);}
 public List<TeacherExamView> listTeacher(){ LambdaQueryWrapper<Exam> q=new LambdaQueryWrapper<Exam>().orderByDesc(Exam::getCreateTime); if(!SecurityUtils.getCurrentRoles().contains("ADMIN"))q.eq(Exam::getPublisherId,SecurityUtils.getCurrentUserId()); return examMapper.selectList(q).stream().map(this::view).toList(); }
 public boolean isPaperUsed(Long paperId){return examMapper.selectCount(new LambdaQueryWrapper<Exam>().eq(Exam::getPaperId,paperId))>0;}
 public RuntimeExamSnapshot runtimeSnapshot(Long id){Exam e=examMapper.selectById(id);if(e==null)throw new BusinessException("考试不存在");Long sid=jdbc.queryForObject("select paper_snapshot_id from exam_paper_ref where exam_id=?",Long.class,id);List<Long> candidates=jdbc.queryForList("select student_id from exam_candidate where exam_id=? order by student_id",Long.class,id);return new RuntimeExamSnapshot(id,e.getName(),e.getStartTime(),e.getEndTime(),e.getDurationMinutes(),e.getPassScore(),e.getStatus(),e.getPublisherId(),e.getProctoringLevel(),e.getProctoringConfigJson(),candidates,content.delivery(sid).getData());}
 public List<RuntimeExamSnapshot> studentSnapshots(Long studentId){return jdbc.queryForList("select exam_id from exam_candidate where student_id=? order by exam_id desc",Long.class,studentId).stream().map(this::runtimeSnapshot).toList();}
 private Exam requireManage(Long id){Exam e=examMapper.selectById(id);if(e==null)throw new BusinessException("考试不存在");if(!SecurityUtils.getCurrentRoles().contains("ADMIN")&&!java.util.Objects.equals(e.getPublisherId(),SecurityUtils.getCurrentUserId()))throw new BusinessException("无权限管理该考试");return e;}
 private TeacherExamView view(Exam e){return TeacherExamView.builder().examId(e.getId()).name(e.getName()).startTime(e.getStartTime()).endTime(e.getEndTime()).durationMinutes(e.getDurationMinutes()).passScore(e.getPassScore()).status(e.getStatus()).proctoringLevel(e.getProctoringLevel()).build();}
}
