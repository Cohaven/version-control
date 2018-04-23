package com.asigra.plugins.servlet;

import com.atlassian.jira.bc.JiraServiceContext;
import com.atlassian.jira.bc.JiraServiceContextImpl;
import com.atlassian.jira.bc.ServiceResult;
import com.atlassian.jira.bc.project.version.DeleteVersionWithReplacementsParameterBuilder;
import com.atlassian.jira.bc.project.version.VersionBuilder;
import com.atlassian.jira.bc.project.version.VersionService;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.project.version.Version;
import com.atlassian.jira.project.version.VersionManager;
import com.atlassian.jira.project.version.DeleteVersionWithCustomFieldParameters;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.ErrorCollection;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.message.I18nResolver;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.templaterenderer.TemplateRenderer;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

import com.asigra.plugins.versioncontrol.api.Message;

//import java.lang.Long;


//import com.atlassian.crowd.embedded.api.User;

//import com.atlassian.confluence.core.ConfluenceActionSupport;

@Component
public class VersionControl extends HttpServlet{
    @ComponentImport
    private TemplateRenderer templateRenderer;

    @ComponentImport
    private final I18nResolver i18nResolver;

    @ComponentImport
    private VersionManager versionManager;

    @ComponentImport
    private VersionService versionService;

    @ComponentImport
    private ProjectManager projectManager;

    @ComponentImport
    private JiraAuthenticationContext jiraAuthenticationContext;

//    @ComponentImport
//    private UserManager userManager;

//    @ComponentImport
//    private com.atlassian.jira.user.util.UserManager jiraUserManager;

    private static final String UI_TEMPLATE = "/templates/versioncontrol.vm";
    private static final String NULL_VERSION = "A version could not be found. It may have been manually deleted.";
    private static final String NULL_PROJECT = "A project could not be found. It may have been manually deleted.";
    private static final String ERROR_STYLE_CLASS = "aui-message-error";
    private static final String SUCCESS_STYLE_CLASS = "aui-message-success";

    private static final Logger log = LoggerFactory.getLogger(VersionControl.class);

    @Autowired
    public VersionControl(TemplateRenderer templateRenderer, I18nResolver i18nResolver, VersionManager versionManager,
                          VersionService versionService, ProjectManager projectManager, JiraAuthenticationContext jiraAuthenticationContext) { //UserManager userManager, com.atlassian.jira.user.util.UserManager jiraUserManager
        this.templateRenderer = templateRenderer;
        this.i18nResolver = i18nResolver;
        this.versionManager = versionManager;
        this.versionService = versionService;
//        this.userManager = userManager;
//        this.jiraUserManager = jiraUserManager;
        this.projectManager = projectManager;
        this.jiraAuthenticationContext = jiraAuthenticationContext;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

            // Create an empty context map to pass into the render method
            Map<String, Object> context = Maps.newHashMap();
            // Make sure to set the contentType otherwise bad things happen
            resp.setContentType("text/html;charset=utf-8");
            context = prepContext(context);
            // Render the velocity template. Since the template doesn't need to render any dynamic content, we just pass it an empty context
            templateRenderer.render(UI_TEMPLATE, context, resp.getWriter());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
//        Map params = req.getParameterMap();
        String[] versions = req.getParameterValues("versions");
        String[] projects = req.getParameterValues("projects");
        Map<String, Object> context = Maps.newHashMap();

        if(versions == null || versions.length == 0 || projects == null || projects.length == 0) {
            resp.setContentType("text/html;charset=utf-8");
            context = prepContext(context);
            ArrayList<String> error = new ArrayList<String>();
            error.add("Please select at least one version and product");
            context.put("errors", error);
            templateRenderer.render(UI_TEMPLATE, context, resp.getWriter());
            return;
        }

        ApplicationUser user = getCurrentUser(req);
        ArrayList<Message> errors = new ArrayList<Message>();
        String act = req.getParameter("action");

        if (act.equals("add")) {
            Collection<Version> versionsToAdd = new HashSet();
            for (String version : versions) {
                // get version object by id and put it into a collection or array
                versionsToAdd.add(versionManager.getVersion(Long.parseLong(version)));
            }
            for (String project : projects) {
                if(project != null) {
                    for (Version version : versionsToAdd) {
                        if (version != null) {
                            VersionBuilder versionBuilder = versionService.newVersionBuilder();
                            versionBuilder = setVersionBuilder(versionBuilder, version, project);

                            VersionService.VersionBuilderValidationResult result = versionService.validateCreate(user, versionBuilder);
                            if (result.getErrorCollection().hasAnyErrors()) {
                                // If the validation fails, we re-render the edit page with the errors in the context
                                errors.addAll(processErrors(result.getErrorCollection(), version.getName(), project));

                            } else {
                                // If the validation passes, we perform the update then redirect the user back to the
                                // page with the list of issues
                                versionService.create(user, result);
                                errors.add(createMessage("Version was created successfully.", version.getName(), project, SUCCESS_STYLE_CLASS));
                            }
                        } else {
                            errors.add(createMessage(NULL_VERSION, "unknown", project, ERROR_STYLE_CLASS));
                        }
                    }
                }
                else {
                    errors.add(createMessage(NULL_PROJECT, "unknown", null, ERROR_STYLE_CLASS));
                }
            }
        }
        if (act.equals("archive")) {
            for (String project : projects) {
                if(project != null) {
                    for (String version : versions) {
                        if (version != null) {
                            //get the corresponding version in the project
                            Version versionToArchive = versionManager.getVersion(Long.parseLong(project), versionManager.getVersion(Long.parseLong(version)).getName());
                            if (versionToArchive != null) {
                                VersionService.ArchiveVersionValidationResult result = versionService.validateArchiveVersion(user, versionToArchive);
                                if (result.getErrorCollection().hasAnyErrors()) {
                                    errors.addAll(processErrors(result.getErrorCollection(), versionToArchive.getName(), project));
                                } else {
                                    versionService.archiveVersion(result);
                                    errors.add(createMessage("Version was archived successfully.", versionToArchive.getName(), project, SUCCESS_STYLE_CLASS));
                                }
                            }
                        } else {
                            errors.add(createMessage(NULL_VERSION, "unknown", project, ERROR_STYLE_CLASS));
                        }
                    }
                }
                else {
                    errors.add(createMessage(NULL_PROJECT, "unknown", null, ERROR_STYLE_CLASS));
                }
            }
        }
        if (act.equals("unarchive")) {
            for (String project : projects) {
                if(project != null) {
                    for (String version : versions) {
                        if (version != null) {
                            //get the corresponding version in the project
                            Version versionToUnarchive = versionManager.getVersion(Long.parseLong(project), versionManager.getVersion(Long.parseLong(version)).getName());
                            if (versionToUnarchive != null) {
                                VersionService.ArchiveVersionValidationResult result = versionService.validateUnarchiveVersion(user, versionToUnarchive);
                                if (result.getErrorCollection().hasAnyErrors()) {
                                    errors.addAll(processErrors(result.getErrorCollection(), versionToUnarchive.getName(), project));
                                } else {
                                    versionService.unarchiveVersion(result);
                                    errors.add(createMessage("Version was unarchived successfully.", versionToUnarchive.getName(), project, SUCCESS_STYLE_CLASS));
                                }
                            }
                        } else {
                            errors.add(createMessage(NULL_VERSION, "unknown", project, ERROR_STYLE_CLASS));
                        }
                    }
                }
                else {
                    errors.add(createMessage(NULL_PROJECT, "unknown", null, ERROR_STYLE_CLASS));
                }
            }
        }
        if (act.equals("delete")) {
            Collection<Version> versionsToDelete = new HashSet();
            for (String version : versions) {
                // get version object by id and put it into a collection so that we know which versions to delete even when they get deleted
                versionsToDelete.add(versionManager.getVersion(Long.parseLong(version)));
            }
            for (String project : projects) {
                if(project != null) {
                    for (Version version : versionsToDelete) {
                        if (version != null) {
                            //get the corresponding version from the project
                            Version versionToDelete = versionManager.getVersion(Long.parseLong(project), version.getName());
                            //delete the version with validation
                            if (versionToDelete != null) {
                                JiraServiceContext jsc = new JiraServiceContextImpl(user);
                                DeleteVersionWithReplacementsParameterBuilder deleteVersionWithReplacementsParameterBuilder = versionService.createVersionDeletaAndReplaceParameters(versionToDelete);
                                DeleteVersionWithCustomFieldParameters deleteVersionWithCustomFieldParameters = deleteVersionWithReplacementsParameterBuilder.build();
                                ServiceResult result = versionService.deleteVersionAndSwap(jsc, deleteVersionWithCustomFieldParameters);
                                if (result.getErrorCollection().hasAnyErrors()) {
                                    errors.addAll(processErrors(result.getErrorCollection(), version.getName(), project));
                                } else {
                                    errors.add(createMessage("Version was deleted successfully.", version.getName(), project, SUCCESS_STYLE_CLASS));
                                }
                            }
                        } else {
                            errors.add(createMessage(NULL_VERSION, "unknown", project, ERROR_STYLE_CLASS));
                        }
                    }
                }
                else {
                    errors.add(createMessage(NULL_PROJECT, "unknown", null, ERROR_STYLE_CLASS));
                }
            }

        }
        if (!errors.isEmpty()) {
            // If the validation fails, we re-render the edit page with the errors in the context
            resp.setContentType("text/html;charset=utf-8");
            context = prepContext(context);
            context.put("errors", errors);
            templateRenderer.render(UI_TEMPLATE, context, resp.getWriter());
        } else {
            // If the validation passes, we perform the update then redirect the user back
            resp.sendRedirect("versioncontrol");
        }
    }

    //get unique versions from the global list
    private Collection<Version> getAllVersions() {
        Collection<Version> versions = versionManager.getAllVersions();
        Collection<Version> uniqueVersions = new HashSet();
        boolean found;
        //filter out the duplicates
        for (Version version : versions) {
            found = false;
            for (Version uniqueVersion : uniqueVersions) {
                if(version.getName().equals(uniqueVersion.getName())){
                    found = true;
                    break;
                }
            }
            //if version is not in uniqueVersion list, then add it to it
            if(!found){
                uniqueVersions.add(version);
            }
        }
        return uniqueVersions;
    }


    private ApplicationUser getCurrentUser(HttpServletRequest req) {
        // To get the current user, we first get the username from the session.
        // Then we pass that over to the jiraUserManager in order to get an
        // actual User object.
        return jiraAuthenticationContext.getLoggedInUser();
//        return jiraUserManager.getUserByName("admin");
//        return jiraUserManager.getUserByName(userManager.getRemoteUsername(req));
    }

    private Map<String, Object> prepContext(Map<String, Object> context){
        context.put("title", i18nResolver.getText("version-control.actionpage.title"));
        context.put("description", i18nResolver.getText("version-control.actionpage.description"));
        context.put("confirm_message", i18nResolver.getText("version-control.actionpage.confirm-message"));
        context.put("confirm_action", i18nResolver.getText("version-control.actionpage.confirm-action"));
        context.put("versions", getAllVersions());
        return context;
    }

    private VersionBuilder setVersionBuilder(VersionBuilder versionBuilder, Version version, String project){
        versionBuilder.projectId(Long.parseLong(project));
        versionBuilder.description(version.getDescription());
        versionBuilder.name(version.getName());
        versionBuilder.released(version.isReleased());
        versionBuilder.releaseDate(version.getReleaseDate());
        versionBuilder.sequence(version.getSequence());
        versionBuilder.startDate(version.getStartDate());
        versionBuilder.archived(version.isArchived());
        return versionBuilder;
    }

    private ArrayList<Message> processErrors(ErrorCollection errors, String versionId, String projectId){
        ArrayList<Message> result = new ArrayList<Message>();
        ArrayList<String> allErrors  = new ArrayList<String>();
        allErrors.addAll(errors.getErrors().values());
        allErrors.addAll(errors.getErrorMessages());

        result = createMessages(allErrors, versionId, projectId, ERROR_STYLE_CLASS);
        return result;
    }

    private ArrayList<Message> createMessages(ArrayList<String> messages, String versionName, String projectId, String styleClass){
        ArrayList<Message> result = new ArrayList<Message>();
        String text;
        String projectName = "unknown";

        if(projectId != null) {
            Project project = projectManager.getProjectObj(Long.parseLong(projectId));
            if(project != null) {
                projectName = project.getName();
            }
        }

        for (String message : messages) {
            text = "["+ projectName +" - "+ versionName +"]: "+ message;
            result.add(new Message(styleClass, text));
        }
        return result;
    }

    private Message createMessage(String message, String versionName, String projectId, String styleClass){
        String projectName = "unknown";

        if(projectId != null) {
            Project project = projectManager.getProjectObj(Long.parseLong(projectId));
            if(project != null) {
                projectName = project.getName();
            }
        }

        String text = "["+ projectName +" - "+ versionName +"]: "+ message;
        Message result = new Message(styleClass, text);
        return result;
    }

}