import http from './http'

export const loginApi = (data) => http.post('/auth/login', data)
export const logoutApi = () => http.post('/auth/logout')
export const meApi = () => http.get('/auth/me')
export const changePasswordApi = (data) => http.post('/auth/change-password', data)
export const healthPingApi = () => http.get('/health/ping', { silent: true, timeout: 3000 })

export const queryQuestionsApi = (data) => http.post('/questions/query', data)
export const listQuestionSubjectsApi = () => http.get('/questions/subjects')
export const getQuestionDetailApi = (id) => http.get(`/questions/${id}`)
export const createQuestionApi = (data) => http.post('/questions', data)
export const uploadQuestionImageApi = (formData) => http.post('/questions/images/upload', formData, {
  headers: { 'Content-Type': 'multipart/form-data' }
})
export const updateQuestionApi = (id, data = {}) => http.put(`/questions/${id}`, data)
export const deleteQuestionApi = (id) => http.delete(`/questions/${id}`)

export const createManualPaperApi = (data) => http.post('/papers/manual', data)
export const createAutoPaperApi = (data) => http.post('/papers/auto-generate', data)
export const queryPapersApi = (data) => http.post('/papers/query', data)
export const paperDetailApi = (id) => http.get(`/papers/${id}`)
export const updatePaperApi = (id, data) => http.put(`/papers/${id}`, data)
export const deletePaperApi = (id) => http.delete(`/papers/${id}`)

export const createExamApi = (data) => http.post('/exams', data)
export const publishExamApi = (id) => http.post(`/exams/${id}/publish`)
export const terminateExamApi = (id) => http.post(`/exams/${id}/terminate`)
export const teacherExamsApi = () => http.get('/exams/teacher')
export const studentExamsApi = () => http.get('/exams/student')
export const studentExamResultsApi = () => http.get('/exams/student/results')
export const proctoringOverviewApi = (examId) => http.get(`/exams/${examId}/proctoring/overview`)
export const proctoringStudentsApi = (examId) => http.get(`/exams/${examId}/proctoring/students`)
export const proctoringTimelineApi = (examId, studentId) => http.get(`/exams/${examId}/proctoring/students/${studentId}/timeline`)
export const updateProctoringDispositionApi = (examId, studentId, data) =>
  http.put(`/exams/${examId}/proctoring/students/${studentId}/disposition`, data)
export const startExamApi = (id, data = {}) => http.post(`/exams/${id}/start`, data)
export const clientHeartbeatApi = (id, data, config = {}) => http.post(`/exams/${id}/client-heartbeat`, data, config)
export const snapshotApi = (id, data, config = {}) => http.post(`/exams/${id}/snapshot`, data, config)
export const submitExamApi = (id, data, config = {}) => http.post(`/exams/${id}/submit`, data, config)
export const submissionStatusApi = (id, config = {}) => http.get(`/exams/${id}/submission-status`, config)
export const antiCheatApi = (id, data, config = {}) => http.post(`/exams/${id}/anti-cheat-events`, data, config)
export const uploadAntiCheatEvidenceApi = (id, formData) => http.post(`/exams/${id}/anti-cheat-evidence`, formData, {
  headers: { 'Content-Type': 'multipart/form-data' }
})

export const gradingPendingApi = () => http.get('/grading/pending')
export const gradingScoreApi = (submissionId, data) => http.post(`/grading/${submissionId}/subjective-score`, data)
export const gradingPendingQuestionsApi = () => http.get('/grading/pending/questions')
export const gradingPendingQuestionAnswersApi = (questionId, examId) =>
  http.get(`/grading/pending/questions/${questionId}/answers?examId=${examId}`)
export const gradingQuestionBatchScoreApi = (questionId, data) => http.post(`/grading/pending/questions/${questionId}/score`, data)

export const analyticsOverviewApi = (examId) => http.get(`/analytics/exams/${examId}/overview`)
export const scoreDistributionApi = (examId) => http.get(`/analytics/exams/${examId}/score-distribution`)
export const classTrendApi = (examId) => http.get(`/analytics/exams/${examId}/class-trend`)
export const wrongTopicsApi = (examId, topN = 10) => http.get(`/analytics/exams/${examId}/wrong-topics?topN=${topN}`)
export const studentScoresApi = (examId) => http.get(`/analytics/exams/${examId}/student-scores`)

export const queryUsersApi = (data) => http.post('/admin/users/query', data)
export const createUserApi = (data) => http.post('/admin/users', data)
export const updateUserApi = (id, data) => http.put(`/admin/users/${id}`, data)
export const deleteUserApi = (id) => http.delete(`/admin/users/${id}`)
export const resetPasswordApi = (id, data) => http.post(`/admin/users/${id}/reset-password`, data)
export const assignRolesApi = (id, data) => http.put(`/admin/users/${id}/roles`, data)
export const rolesApi = () => http.get('/admin/roles')
export const createRoleApi = (data) => http.post('/admin/roles', data)

export const listCoursesApi = () => http.get('/admin/courses')
export const createCourseApi = (data) => http.post('/admin/courses', data)
export const updateCourseApi = (id, data) => http.put(`/admin/courses/${id}`, data)
export const importCoursesApi = (dryRun, formData) =>
  http.post(`/admin/courses/import?dryRun=${dryRun}`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
export const listTeachingClassesApi = () => http.get('/admin/teaching-classes')
export const createTeachingClassApi = (data) => http.post('/admin/teaching-classes', data)
export const updateTeachingClassApi = (id, data) => http.put(`/admin/teaching-classes/${id}`, data)
export const importUsersApi = (role, dryRun, formData) =>
  http.post(`/admin/users/import?role=${encodeURIComponent(role)}&dryRun=${dryRun}`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
export const importTeachingClassesApi = (dryRun, formData) =>
  http.post(`/admin/teaching-classes/import?dryRun=${dryRun}`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
export const importExamSchedulesApi = (dryRun, formData) =>
  http.post(`/admin/exam-schedules/import?dryRun=${dryRun}`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
export const batchUsersApi = (data) => http.post('/admin/users/batch', data)
export const batchTeachingClassesApi = (data) => http.post('/admin/teaching-classes/batch', data)
export const batchExamsApi = (data) => http.post('/admin/exams/batch', data)
export const adminExamMonitorSummaryApi = (params = {}) => http.get('/admin/exam-monitor/summary', { params })
export const adminExamMonitorStudentsApi = (examId) => http.get(`/admin/exam-monitor/exams/${examId}/students`)
export const auditLogsApi = (params = {}) => http.get('/admin/audit-logs', { params })

export const examTeachingClassesApi = () => http.get('/exams/teaching-classes')

export const teacherClassesApi = () => http.get('/teacher/classes')
export const teacherClassStudentsApi = (classId) => http.get(`/teacher/classes/${classId}/students`)
export const teacherClassAddStudentsApi = (classId, data) => http.post(`/teacher/classes/${classId}/students`, data)
export const teacherClassRemoveStudentApi = (classId, studentId) =>
  http.delete(`/teacher/classes/${classId}/students/${studentId}`)
export const teacherClassStudentCandidatesApi = (classId, data) =>
  http.post(`/teacher/classes/${classId}/student-candidates/query`, data)
