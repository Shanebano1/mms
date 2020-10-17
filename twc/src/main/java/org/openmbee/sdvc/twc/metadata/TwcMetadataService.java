package org.openmbee.sdvc.twc.metadata;

import org.openmbee.sdvc.core.config.ContextHolder;
import org.openmbee.sdvc.core.dao.ProjectIndex;
import org.openmbee.sdvc.core.exceptions.InternalErrorException;
import org.openmbee.sdvc.data.domains.global.Metadata;
import org.openmbee.sdvc.data.domains.global.Project;
import org.openmbee.sdvc.json.ProjectJson;
import org.openmbee.sdvc.twc.constants.TwcConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class TwcMetadataService {

    private static final Logger logger = LoggerFactory.getLogger(TwcMetadataService.class);
    private ProjectIndex projectIndex;

    @Autowired
    public void setProjectIndex(ProjectIndex projectIndex) {
        this.projectIndex = projectIndex;
    }

    public void updateTwcMetadata(Project project, TwcMetadata metadata) {
        ContextHolder.setContext(project.getProjectId());
        ProjectJson projectJson = getProjectJson(project);
        Map<String, String> metadataMap = metadata.toMap();
        metadataMap.put(TwcConstants.ENABLED_KEY, "true");
        projectJson.put(TwcConstants.FOREIGN_PROJECT, metadataMap);
        projectIndex.update(projectJson);
    }

    public TwcMetadata getTwcMetadata(Project project) {
        ContextHolder.setContext(project.getProjectId());
        ProjectJson projectJson = getProjectJson(project);
        Map<String, Object> metadata = (Map)projectJson.get(TwcConstants.FOREIGN_PROJECT);
        if(metadata == null) {
            return null;
        }

        TwcMetadata twcMetadata = new TwcMetadata();
        twcMetadata.setHost(String.valueOf(metadata.get(TwcConstants.HOST_KEY)));
        twcMetadata.setWorkspaceId(String.valueOf(metadata.get(TwcConstants.WORKSPACE_ID_KEY)));
        twcMetadata.setResourceId(String.valueOf(metadata.get(TwcConstants.RESOURCE_ID_KEY)));
        return twcMetadata;
    }

    public void deleteTwcMetadata(Project project) {
        ContextHolder.setContext(project.getProjectId());
        ProjectJson projectJson = getProjectJson(project);
        Map<String, Object> metadata = (Map)projectJson.get(TwcConstants.FOREIGN_PROJECT);
        if(metadata == null) {
            return;
        }
        metadata.put(TwcConstants.ENABLED_KEY, "false");
        projectJson.put(TwcConstants.FOREIGN_PROJECT, metadata);
        projectIndex.update(projectJson);
    }

    private ProjectJson getProjectJson(Project project) {
        ProjectJson projectJson = projectIndex.findById(project.getDocId()).orElse(null);
        if(projectJson == null) {
            logger.error("Could not locate project in project index: " + project.getDocId());
            throw new InternalErrorException("Could not locate project in project index");
        }
        return projectJson;
    }
}
