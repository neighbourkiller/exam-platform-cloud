package com.ekusys.exam.common.event;

public final class EventTypes {
    public static final String USER_CHANGED = "UserChanged";
    public static final String ACADEMIC_ROSTER_CHANGED = "AcademicRosterChanged";
    public static final String PAPER_SNAPSHOT_CREATED = "PaperSnapshotCreated";
    public static final String EXAM_PUBLISHED = "ExamPublished";
    public static final String EXAM_TERMINATED = "ExamTerminated";
    public static final String SUBMISSION_ACCEPTED = "SubmissionAccepted";
    public static final String GRADE_COMPLETED = "GradeCompleted";
    public static final String PROCTORING_EVENT_RECORDED = "ProctoringEventRecorded";
    public static final String AUDIT_OPERATION_RECORDED = "AuditOperationRecorded";

    private EventTypes() {
    }
}
