package com.ekusys.exam.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.academic.api.ClassRosterView;
import com.ekusys.exam.academic.api.SubjectSummary;
import com.ekusys.exam.common.enums.ExamStatus;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.content.api.PaperSnapshotView;
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
  if(!request.getStartTime().isBefore(request.getEndTime())) throw new BusinessException("考试开始时间必须早于结束时间");
  Exam exam=new Exam(); exam.setName(request.getName()); exam.setPaperId(request.getPaperId()); exam.setStartTime(request.getStartTime());
  exam.setEndTime(request.getEndTime()); exam.setDurationMinutes(request.getDurationMinutes()); exam.setPassScore(request.getPassScore());
  exam.setStatus(ExamStatus.DRAFT.name()); exam.setPublisherId(SecurityUtils.getCurrentUserId());
  exam.setProctoringLevel(request.getProctoringLevel()==null?"STANDARD":request.getProctoringLevel());
  try { exam.setProctoringConfigJson(request.getProctoringPolicy()==null?null:mapper.writeValueAsString(request.getProctoringPolicy())); }
  catch(Exception ex){throw new BusinessException("监考策略格式错误");}
  examMapper.insert(exam); for(Long classId:request.getTargetClassIds()){ if(academic.roster(classId).getData()==null) throw new BusinessException("教学班不存在: "+classId); ExamTargetClass link=new ExamTargetClass();link.setExamId(exam.getId());link.setClassId(classId);targetMapper.insert(link); }
  return exam.getId();
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
