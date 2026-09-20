package com.ekusys.exam.admin.service;

import com.ekusys.exam.admin.dto.CourseCreateRequest;
import com.ekusys.exam.admin.dto.CourseUpdateRequest;
import com.ekusys.exam.admin.dto.CourseView;
import com.ekusys.exam.admin.dto.RoleCreateRequest;
import com.ekusys.exam.admin.dto.RoleView;
import com.ekusys.exam.admin.dto.TeachingClassCreateRequest;
import com.ekusys.exam.admin.dto.TeachingClassUpdateRequest;
import com.ekusys.exam.admin.dto.TeachingClassView;
import com.ekusys.exam.admin.dto.UserCreateRequest;
import com.ekusys.exam.admin.dto.UserQueryRequest;
import com.ekusys.exam.admin.dto.UserUpdateRequest;
import com.ekusys.exam.admin.dto.UserView;
import com.ekusys.exam.common.api.PageResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AdminService {

    private final UserAdminService userAdminService;
    private final RoleAdminService roleAdminService;
    private final SubjectAdminService subjectAdminService;
    private final TeachingClassAdminService teachingClassAdminService;

    public AdminService(UserAdminService userAdminService,
                        RoleAdminService roleAdminService,
                        SubjectAdminService subjectAdminService,
                        TeachingClassAdminService teachingClassAdminService) {
        this.userAdminService = userAdminService;
        this.roleAdminService = roleAdminService;
        this.subjectAdminService = subjectAdminService;
        this.teachingClassAdminService = teachingClassAdminService;
    }

    public PageResponse<UserView> queryUsers(UserQueryRequest request) {
        return userAdminService.queryUsers(request);
    }

    public Long createUser(UserCreateRequest request) {
        return userAdminService.createUser(request);
    }

    public void updateUser(Long userId, UserUpdateRequest request) {
        userAdminService.updateUser(userId, request);
    }

    public void deleteUser(Long userId) {
        userAdminService.deleteUser(userId);
    }

    public void resetPassword(Long userId, String password) {
        userAdminService.resetPassword(userId, password);
    }

    public void assignRoles(Long userId, List<Long> roleIds) {
        roleAdminService.assignRoles(userId, roleIds);
    }

    public List<RoleView> listRoles() {
        return roleAdminService.listRoles();
    }

    public Long createRole(RoleCreateRequest request) {
        return roleAdminService.createRole(request);
    }

    public List<CourseView> listCourses() {
        return subjectAdminService.listCourses();
    }

    public Long createCourse(CourseCreateRequest request) {
        return subjectAdminService.createCourse(request);
    }

    public void updateCourse(Long courseId, CourseUpdateRequest request) {
        subjectAdminService.updateCourse(courseId, request);
    }

    public List<TeachingClassView> listTeachingClasses() {
        return teachingClassAdminService.listTeachingClasses();
    }

    public Long createTeachingClass(TeachingClassCreateRequest request) {
        return teachingClassAdminService.createTeachingClass(request);
    }

    public void updateTeachingClass(Long id, TeachingClassUpdateRequest request) {
        teachingClassAdminService.updateTeachingClass(id, request);
    }
}
