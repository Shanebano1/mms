package org.openmbee.sdvc.permissions.delegation;

import org.openmbee.sdvc.core.builders.PermissionUpdateResponseBuilder;
import org.openmbee.sdvc.core.builders.PermissionUpdatesResponseBuilder;
import org.openmbee.sdvc.core.config.AuthorizationConstants;
import org.openmbee.sdvc.core.objects.PermissionResponse;
import org.openmbee.sdvc.core.objects.PermissionUpdateRequest;
import org.openmbee.sdvc.core.objects.PermissionUpdateResponse;
import org.openmbee.sdvc.core.objects.PermissionUpdatesResponse;
import org.openmbee.sdvc.data.domains.global.*;
import org.openmbee.sdvc.permissions.exceptions.PermissionException;
import org.openmbee.sdvc.rdb.repositories.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DefaultProjectPermissionsDelegate extends AbstractDefaultPermissionsDelegate {

    private ProjectUserPermRepository projectUserPermRepo;
    private ProjectGroupPermRepository projectGroupPermRepo;
    private ProjectRepository projectRepo;
    private OrgGroupPermRepository orgGroupPermRepo;
    private OrgUserPermRepository orgUserPermRepo;

    private Project project;

    public DefaultProjectPermissionsDelegate(Project project) {
        this.project = project;
    }

    @Autowired
    public void setProjectUserPermRepo(ProjectUserPermRepository projectUserPermRepo) {
        this.projectUserPermRepo = projectUserPermRepo;
    }

    @Autowired
    public void setProjectGroupPermRepo(ProjectGroupPermRepository projectGroupPermRepo) {
        this.projectGroupPermRepo = projectGroupPermRepo;
    }

    @Autowired
    public void setProjectRepo(ProjectRepository projectRepo) {
        this.projectRepo = projectRepo;
    }

    @Autowired
    public void setOrgGroupPermRepo(OrgGroupPermRepository orgGroupPermRepo) {
        this.orgGroupPermRepo = orgGroupPermRepo;
    }

    @Autowired
    public void setOrgUserPermRepo(OrgUserPermRepository orgUserPermRepo) {
        this.orgUserPermRepo = orgUserPermRepo;
    }

    @Override
    public boolean hasPermission(String user, Set<String> groups, String privilege) {

        Optional<Privilege> priv = getPrivRepo().findByName(privilege);
        if (!priv.isPresent()) {
            throw new PermissionException(HttpStatus.BAD_REQUEST, "No such privilege");
        }

        Set<Role> roles = priv.get().getRoles();

        if (projectUserPermRepo.existsByProjectAndUser_UsernameAndRoleIn(project, user, roles)) {
            return true;
        }

        if (!groups.isEmpty() && projectGroupPermRepo.existsByProjectAndGroup_NameInAndRoleIn(project, groups, roles)) {
            return true;
        }
        return false;
    }

    @Override
    public void initializePermissions(String creator) {
        initializePermissions(creator, false);
    }

    @Override
    public void initializePermissions(String creator, boolean inherit) {
        Optional<User> user = getUserRepo().findByUsername(creator);
        Optional<Role> role = getRoleRepo().findByName("ADMIN");

        if (!user.isPresent()) {
            throw new PermissionException(HttpStatus.NOT_FOUND, "User not found");
        } else if (!role.isPresent()) {
            throw new PermissionException(HttpStatus.NOT_FOUND, "Role not found");
        }

        ProjectUserPerm perm = new ProjectUserPerm(project, user.get(), role.get(), false);
        projectUserPermRepo.save(perm);
        project.setInherit(inherit);
        projectRepo.save(project);
    }

    @Override
    public boolean setInherit(boolean isInherit) {
        if (project.isInherit() != isInherit) {
            project.setInherit(isInherit);
            projectRepo.save(project);
            return true;
        }
        return false;
    }

    @Override
    public void setPublic(boolean isPublic) {
        project.setPublic(isPublic);
        projectRepo.save(project);
    }

    @Override
    public PermissionUpdateResponse updateUserPermissions(PermissionUpdateRequest req) {
        PermissionUpdateResponseBuilder responseBuilder = new PermissionUpdateResponseBuilder();

        switch(req.getAction()) {
            case MODIFY:
                for (PermissionUpdateRequest.Permission p: req.getPermissions()) {
                    Optional<User> user = getUserRepo().findByUsername(p.getName());
                    Optional<Role> role = getRoleRepo().findByName(p.getRole());
                    if (!user.isPresent() || !role.isPresent()) {
                        //throw exception or skip
                        continue;
                    }
                    Optional<ProjectUserPerm> exist = projectUserPermRepo.findByProjectAndUserAndInheritedIsFalse(project, user.get());
                    ProjectUserPerm p1;
                    if (exist.isPresent()) {
                        p1 = exist.get();
                        if (!role.get().equals(p1.getRole())) {
                            responseBuilder.insertPermissionUpdate(PermissionUpdateResponse.Action.REMOVE, p1);
                            p1.setRole(role.get());
                            projectUserPermRepo.save(p1);
                        }
                    } else {
                        p1 = new ProjectUserPerm(project, user.get(), role.get(), false);
                        projectUserPermRepo.save(p1);
                    }
                    responseBuilder.insertPermissionUpdate(PermissionUpdateResponse.Action.ADD, p1);
                }
                break;
            case REPLACE:
                responseBuilder.insertPermissionUpdates_ProjectUserPerm(PermissionUpdateResponse.Action.REMOVE,
                    projectUserPermRepo.findAllByProjectAndInherited(project, false));
                projectUserPermRepo.deleteByProjectAndInherited(project, false);
                for (PermissionUpdateRequest.Permission p: req.getPermissions()) {
                    Optional<User> user = getUserRepo().findByUsername(p.getName());
                    Optional<Role> role = getRoleRepo().findByName(p.getRole());
                    if (!user.isPresent() || !role.isPresent()) {
                        //throw exception or skip
                        continue;
                    }
                    ProjectUserPerm p2 = new ProjectUserPerm(project, user.get(), role.get(), false);
                    projectUserPermRepo.save(p2);
                    responseBuilder.insertPermissionUpdate(PermissionUpdateResponse.Action.ADD, p2);
                }
                break;
            case REMOVE:
                Set<String> users = new HashSet<>();
                req.getPermissions().forEach(p -> {
                    Optional<User> user = getUserRepo().findByUsername(p.getName());
                    if(! user.isPresent()) {
                        //throw or skip;
                        return;
                    }

                    users.add(p.getName());
                    responseBuilder.insertPermissionUpdate(PermissionUpdateResponse.Action.REMOVE,
                        projectUserPermRepo.findByProjectAndUserAndInheritedIsFalse(project, user.get()).orElse(null));
                });
                projectUserPermRepo.deleteByProjectAndUser_UsernameInAndInheritedIsFalse(project, users);
                break;
        }
        return responseBuilder.getPermissionUpdateResponse();
    }

    @Override
    public PermissionUpdateResponse updateGroupPermissions(PermissionUpdateRequest req) {
        PermissionUpdateResponseBuilder responseBuilder = new PermissionUpdateResponseBuilder();

        switch(req.getAction()) {
            case MODIFY:
                for (PermissionUpdateRequest.Permission p: req.getPermissions()) {
                    Pair<Group, Role> pair = getGroupAndRole(p);
                    if (pair.getFirst() == null || pair.getSecond() == null) {
                        continue;
                    }
                    Optional<ProjectGroupPerm> exist = projectGroupPermRepo.findByProjectAndGroupAndInheritedIsFalse(project, pair.getFirst());
                    ProjectGroupPerm p1;
                    if (exist.isPresent()) {
                        p1 = exist.get();
                        if (!pair.getSecond().equals(p1.getRole())) {
                            responseBuilder.insertPermissionUpdate(PermissionUpdateResponse.Action.REMOVE, p1);
                            p1.setRole(pair.getSecond());
                            projectGroupPermRepo.save(p1);
                        } else {
                            continue;
                        }
                    } else {
                        p1 = new ProjectGroupPerm(project, pair.getFirst(), pair.getSecond(), false);
                        projectGroupPermRepo.save(p1);
                    }
                    responseBuilder.insertPermissionUpdate(PermissionUpdateResponse.Action.ADD, p1);
                }
                break;
            case REPLACE:
                responseBuilder.insertPermissionUpdates_ProjectGroupPerm(PermissionUpdateResponse.Action.REMOVE,
                    projectGroupPermRepo.findAllByProjectAndInherited(project, false));
                projectGroupPermRepo.deleteByProjectAndInherited(project, false);
                for (PermissionUpdateRequest.Permission p: req.getPermissions()) {
                    Pair<Group, Role> pair = getGroupAndRole(p);
                    if (pair.getFirst() == null || pair.getSecond() == null) {
                        continue;
                    }
                    ProjectGroupPerm p1 = new ProjectGroupPerm(project, pair.getFirst(), pair.getSecond(), false);
                    projectGroupPermRepo.save(p1);
                    responseBuilder.insertPermissionUpdate(PermissionUpdateResponse.Action.ADD, p1);
                }
                break;
            case REMOVE:
                Set<String> groups = new HashSet<>();
                req.getPermissions().forEach(p -> {
                    Optional<Group> group = getGroupRepo().findByName(p.getName());
                    if(! group.isPresent()) {
                        //throw or skip
                        return;
                    }

                    groups.add(p.getName());
                    responseBuilder.insertPermissionUpdate(PermissionUpdateResponse.Action.REMOVE,
                        projectGroupPermRepo.findByProjectAndGroupAndInheritedIsFalse(project, group.get()).orElse(null));
                });
                projectGroupPermRepo.deleteByProjectAndGroup_NameInAndInheritedIsFalse(project, groups);
                break;
        }
        return responseBuilder.getPermissionUpdateResponse();
    }

    @Override
    public PermissionResponse getUserRoles() {
        PermissionResponse res = PermissionResponse.getDefaultResponse();
        for (ProjectUserPerm perm: projectUserPermRepo.findAllByProject_ProjectId(project.getProjectId())) {
            res.getPermissions().add(new PermissionResponse.Permission(
                perm.getUser().getUsername(),
                perm.getRole().getName(),
                perm.isInherited()
            ));
        }
        return res;
    }

    @Override
    public PermissionResponse getGroupRoles() {
        PermissionResponse res = PermissionResponse.getDefaultResponse();
        for (ProjectGroupPerm perm: projectGroupPermRepo.findAllByProject_ProjectId(project.getProjectId())) {
            res.getPermissions().add(new PermissionResponse.Permission(
                perm.getGroup().getName(),
                perm.getRole().getName(),
                perm.isInherited()
            ));
        }
        return res;
    }

    @Override
    public PermissionUpdatesResponse recalculateInheritedPerms() {
        PermissionUpdatesResponseBuilder responseBuilder = new PermissionUpdatesResponseBuilder();

        List<ProjectUserPerm> projectUserPermList = projectUserPermRepo.findAllByProjectAndInherited(project, true);
        projectUserPermRepo.deleteAll(projectUserPermList);
        responseBuilder.getUsers().insertPermissionUpdates_ProjectUserPerm(PermissionUpdateResponse.Action.REMOVE, projectUserPermList);

        List<ProjectGroupPerm> projectGroupPermList = projectGroupPermRepo.findAllByProjectAndInherited(project, true);
        projectGroupPermRepo.deleteAll(projectGroupPermList);
        responseBuilder.getGroups().insertPermissionUpdates_ProjectGroupPerm(PermissionUpdateResponse.Action.REMOVE, projectGroupPermList);

        if (project.isInherit()) {
            for (OrgUserPerm p: orgUserPermRepo.findAllByOrganization(project.getOrganization())) {
                ProjectUserPerm perm = new ProjectUserPerm(project, p.getUser(), p.getRole(), true);
                projectUserPermRepo.save(perm);
                responseBuilder.getUsers().insertPermissionUpdate(PermissionUpdateResponse.Action.ADD, perm);
            }
            for (OrgGroupPerm p: orgGroupPermRepo.findAllByOrganization(project.getOrganization())) {
                ProjectGroupPerm perm = new ProjectGroupPerm(project, p.getGroup(), p.getRole(), true);
                projectGroupPermRepo.save(perm);
                responseBuilder.getGroups().insertPermissionUpdate(PermissionUpdateResponse.Action.ADD, perm);
            }
        } else {
            for (OrgUserPerm p: orgUserPermRepo.findAllByOrganizationAndRole_Name(project.getOrganization(), AuthorizationConstants.ADMIN)) {
                ProjectUserPerm perm = new ProjectUserPerm(project, p.getUser(), p.getRole(), true);
                projectUserPermRepo.save(perm);
                responseBuilder.getUsers().insertPermissionUpdate(PermissionUpdateResponse.Action.ADD, perm);
            }
            for (OrgGroupPerm p: orgGroupPermRepo.findAllByOrganizationAndRole_Name(project.getOrganization(), AuthorizationConstants.ADMIN)) {
                ProjectGroupPerm perm = new ProjectGroupPerm(project, p.getGroup(), p.getRole(), true);
                projectGroupPermRepo.save(perm);
                responseBuilder.getGroups().insertPermissionUpdate(PermissionUpdateResponse.Action.ADD, perm);
            }
        }
        return responseBuilder.getPermissionUpdatesReponse();
    }
}
