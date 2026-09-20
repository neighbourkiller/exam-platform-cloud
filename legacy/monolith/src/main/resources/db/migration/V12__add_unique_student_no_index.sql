UPDATE student_profile
SET student_no = NULL
WHERE student_no IS NOT NULL
  AND TRIM(student_no) = '';

ALTER TABLE student_profile
ADD UNIQUE KEY uk_student_profile_student_no (student_no);
