package org.jenkinsci.plugins.envinject;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixRun;
import hudson.model.*;
import hudson.model.listeners.RunListener;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import org.jenkinsci.lib.envinject.EnvInjectException;
import org.jenkinsci.lib.envinject.EnvInjectLogger;
import org.jenkinsci.plugins.envinject.model.EnvInjectJobPropertyContributor;
import org.jenkinsci.plugins.envinject.service.*;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * @author Gregory Boissinot
 */
@Extension
public class EnvInjectListener extends RunListener<Run> implements Serializable {

    @Override
    public Environment setUpEnvironment(@Nonnull AbstractBuild build, @Nonnull Launcher launcher, 
            @Nonnull BuildListener listener) throws IOException, InterruptedException {
        if (isEligibleJobType(build)) {
            EnvInjectLogger logger = new EnvInjectLogger(listener);
            try {

                //Process environment variables at node level
                Node buildNode = build.getBuiltOn();
                if (buildNode != null) {
                    loadEnvironmentVariablesNode(build, buildNode, logger);
                }

                //Load job envinject job property
                if (isEnvInjectJobPropertyActive(build)) {
                    return setUpEnvironmentJobPropertyObject(build, launcher, listener, logger);
                } else {
                    return setUpEnvironmentWithoutJobPropertyObject(build, launcher, listener);
                }

            } catch (Run.RunnerAbortedException rre) {
                logger.info("Fail the build.");
                throw new Run.RunnerAbortedException();
            } catch (EnvInjectException e) {
                e.printStackTrace(listener.error("SEVERE ERROR occurs"));
                throw new Run.RunnerAbortedException();
            }
        }

        return new Environment() {
        };
    }

    private boolean isEligibleJobType(@Nonnull AbstractBuild build) {
        final Job job;
        if (build instanceof MatrixRun) {
            job = ((MatrixRun) build).getParentBuild().getParent();
        } else {
            job = build.getParent();
        }

        return job instanceof BuildableItemWithBuildWrappers;

    }

    private void loadEnvironmentVariablesNode(@Nonnull AbstractBuild build, @Nonnull Node buildNode, @Nonnull EnvInjectLogger logger) throws EnvInjectException {

        EnvironmentVariablesNodeLoader environmentVariablesNodeLoader = new EnvironmentVariablesNodeLoader();
        Map<String, String> configNodeEnvVars = environmentVariablesNodeLoader.gatherEnvironmentVariablesNode(build, buildNode, logger);
        EnvInjectActionSetter envInjectActionSetter = new EnvInjectActionSetter(buildNode.getRootPath());
        try {
            envInjectActionSetter.addEnvVarsToEnvInjectBuildAction(build, configNodeEnvVars);

        } catch (IOException ioe) {
            throw new EnvInjectException(ioe);
        } catch (InterruptedException ie) {
            throw new EnvInjectException(ie);
        }
    }


    private boolean isEnvInjectJobPropertyActive(@Nonnull AbstractBuild build) {
        EnvInjectVariableGetter variableGetter = new EnvInjectVariableGetter();
        EnvInjectJobProperty envInjectJobProperty = variableGetter.getEnvInjectJobProperty(build);
        return envInjectJobProperty != null;
    }

    @Extension
    public static class JobSetupEnvironmentWorkspaceListener extends WorkspaceListener {

        @Override
        public void beforeUse(AbstractBuild build, FilePath ws, BuildListener listener) {

            EnvInjectVariableGetter variableGetter = new EnvInjectVariableGetter();
            EnvInjectJobProperty envInjectJobProperty = variableGetter.getEnvInjectJobProperty(build);
            if (envInjectJobProperty == null) return;

            EnvInjectLogger envInjectLogger = new EnvInjectLogger(listener);

            if (envInjectJobProperty.isKeepBuildVariables()) {
                try {
                    //Get previous
                    Map<String, String> previousEnvVars = variableGetter.getEnvVarsPreviousSteps(build, envInjectLogger);

                    //Add workspace
                    if (previousEnvVars.get("WORKSPACE") == null) {
                        previousEnvVars.put("WORKSPACE", ws.getRemote());
                    }

                    //Resolve variables each other and with WORKSPACE
                    EnvInjectEnvVars envInjectEnvVars = new EnvInjectEnvVars(envInjectLogger);
                    envInjectEnvVars.resolveVars(previousEnvVars, previousEnvVars);

                    //Remove unused variables
                    Map<String, String> cleanVariables = envInjectEnvVars.removeUnsetVars(previousEnvVars);

                    //Set new env vars
                    final Node builtOn = build.getBuiltOn();
                    new EnvInjectActionSetter(builtOn != null ? builtOn.getRootPath() : null)
                            .addEnvVarsToEnvInjectBuildAction(build, cleanVariables);

                } catch (EnvInjectException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private Environment setUpEnvironmentJobPropertyObject(@Nonnull AbstractBuild build, 
            @Nonnull Launcher launcher, @Nonnull BuildListener listener, @Nonnull EnvInjectLogger logger) 
            throws IOException, InterruptedException, EnvInjectException {

        logger.info("Preparing an environment for the build.");

        EnvInjectVariableGetter variableGetter = new EnvInjectVariableGetter();
        EnvInjectJobProperty envInjectJobProperty = variableGetter.getEnvInjectJobProperty(build);
        assert envInjectJobProperty != null;
        EnvInjectJobPropertyInfo info = envInjectJobProperty.getInfo();
        assert envInjectJobProperty.isOn();

        //Init infra env vars
        Map<String, String> previousEnvVars = variableGetter.getEnvVarsPreviousSteps(build, logger);
        Map<String, String> infraEnvVarsNode = new LinkedHashMap<String, String>(previousEnvVars);
        Map<String, String> infraEnvVarsMaster = new LinkedHashMap<String, String>(previousEnvVars);

        //Add Jenkins System variables
        if (envInjectJobProperty.isKeepJenkinsSystemVariables()) {
            logger.info("Keeping Jenkins system variables.");
            infraEnvVarsMaster.putAll(variableGetter.getJenkinsSystemVariables(true));
            infraEnvVarsNode.putAll(variableGetter.getJenkinsSystemVariables(false));
        }

        //Add build variables
        if (envInjectJobProperty.isKeepBuildVariables()) {
            logger.info("Keeping Jenkins build variables.");
            Map<String, String> buildVariables = variableGetter.getBuildVariables(build, logger);
            infraEnvVarsMaster.putAll(buildVariables);
            infraEnvVarsNode.putAll(buildVariables);
        }

        final FilePath rootPath = getNodeRootPath();
        if (rootPath != null && info != null) {

            final EnvInjectEnvVars envInjectEnvVarsService = new EnvInjectEnvVars(logger);

            //Execute script
            int resultCode = envInjectEnvVarsService.executeScript(info.isLoadFilesFromMaster(),
                    info.getScriptContent(),
                    rootPath, info.getScriptFilePath(), infraEnvVarsMaster, infraEnvVarsNode, launcher, listener);
            if (resultCode != 0) {
                build.setResult(Result.FAILURE);
                throw new Run.RunnerAbortedException();
            }

            //Evaluate Groovy script
            Map<String, String> groovyMapEnvVars = envInjectEnvVarsService.executeAndGetMapGroovyScript(logger, info.getGroovyScriptContent(), infraEnvVarsNode);

            final Map<String, String> propertiesVariables = envInjectEnvVarsService.getEnvVarsPropertiesJobProperty(rootPath,
                    logger, info.isLoadFilesFromMaster(),
                    info.getPropertiesFilePath(), info.getPropertiesContentMap(previousEnvVars),
                    infraEnvVarsMaster, infraEnvVarsNode);

            //Get variables get by contribution
            Map<String, String> contributionVariables = getEnvVarsByContribution(build, envInjectJobProperty, logger, listener);

            final Map<String, String> mergedVariables = envInjectEnvVarsService.getMergedVariables(
                    infraEnvVarsNode,
                    propertiesVariables,
                    groovyMapEnvVars,
                    contributionVariables);

            //Add an action to share injected environment variables
            new EnvInjectActionSetter(rootPath).addEnvVarsToEnvInjectBuildAction(build, mergedVariables);


            return new Environment() {
                @Override
                public void buildEnvVars(Map<String, String> env) {
                    envInjectEnvVarsService.resolveVars(mergedVariables, mergedVariables); //resolve variables each other
                    //however, here preCheckout of EnvBuildWrapper is not yet performed
                    env.putAll(mergedVariables);
                }
            };
        } else {
            logger.info(rootPath != null ? "Cannot retrieve info from the EnvInject job property. It may be missing, hence skipping injection."
                                         : "Node root path is not available. Likely node is offline. Skipping injection");
        }

        return new Environment() {
        };
    }

    @Nonnull
    private Environment setUpEnvironmentWithoutJobPropertyObject(@Nonnull AbstractBuild build, 
            @Nonnull Launcher launcher, @Nonnull BuildListener listener) throws IOException, InterruptedException, EnvInjectException {

        final Map<String, String> resultVariables = new HashMap<String, String>();

        EnvInjectVariableGetter variableGetter = new EnvInjectVariableGetter();
        EnvInjectLogger logger = new EnvInjectLogger(listener);
        Map<String, String> previousEnvVars = variableGetter.getEnvVarsPreviousSteps(build, logger);
        resultVariables.putAll(previousEnvVars);

        resultVariables.putAll(variableGetter.getJenkinsSystemVariables(false));
        resultVariables.putAll(variableGetter.getBuildVariables(build, logger));

        final FilePath rootPath = getNodeRootPath();
        if (rootPath != null) {
            new EnvInjectActionSetter(rootPath).addEnvVarsToEnvInjectBuildAction(build, resultVariables);
        }

        return new Environment() {
            @Override
            public void buildEnvVars(Map<String, String> env) {
                env.putAll(resultVariables);
            }
        };
    }

    @CheckForNull
    private Node getNode() {
        Computer computer = Computer.currentComputer();
        if (computer == null) {
            return null;
        }
        return computer.getNode();
    }

    @CheckForNull
    private FilePath getNodeRootPath() {
        Node node = getNode();
        if (node != null) {
            return node.getRootPath();
        }
        return null;
    }

    @Nonnull
    private Map<String, String> getEnvVarsByContribution(@Nonnull AbstractBuild build, 
            @Nonnull EnvInjectJobProperty envInjectJobProperty, @Nonnull EnvInjectLogger logger, 
            @Nonnull BuildListener listener) throws EnvInjectException {
        
        Map<String, String> contributionVariables = new HashMap<String, String>();

        EnvInjectJobPropertyContributor[] contributors = envInjectJobProperty.getContributors();
        if (contributors != null) {
            logger.info("Injecting contributions.");
            for (EnvInjectJobPropertyContributor contributor : contributors) {
                contributionVariables.putAll(contributor.getEnvVars(build, listener));
            }
        }
        return contributionVariables;
    }

    @Override
    public void onCompleted(Run run, TaskListener listener) {

        if (!(run instanceof AbstractBuild)) {
            return;
        }

        AbstractBuild build = (AbstractBuild) run;
        if (!isEligibleJobType(build)) {
            return;
        }

        //Mask passwords
        EnvVars envVars = new EnvVars();
        EnvInjectLogger logger = new EnvInjectLogger(listener);
        EnvInjectPasswordsMasker passwordsMasker = new EnvInjectPasswordsMasker();
        passwordsMasker.maskPasswordsIfAny(build, logger, envVars);

        if (!(build instanceof MatrixBuild)) {

            EnvInjectPluginAction envInjectAction = run.getAction(EnvInjectPluginAction.class);
            if (envInjectAction == null) {
                try {
                    envVars.putAll(build.getEnvironment(listener));
                } catch (IOException e) {
                    logger.error("SEVERE ERROR occurs: " + e.getMessage());
                    throw new Run.RunnerAbortedException();
                } catch (InterruptedException e) {
                    logger.error("SEVERE ERROR occurs: " + e.getMessage());
                    throw new Run.RunnerAbortedException();
                }
            }
        }

        //Add or override EnvInject Action
        EnvInjectActionSetter envInjectActionSetter = new EnvInjectActionSetter(getNodeRootPath());
        try {
            envInjectActionSetter.addEnvVarsToEnvInjectBuildAction((AbstractBuild<?, ?>) run, envVars);
        } catch (EnvInjectException e) {
            logger.error("SEVERE ERROR occurs: " + e.getMessage());
            throw new Run.RunnerAbortedException();
        } catch (IOException e) {
            logger.error("SEVERE ERROR occurs: " + e.getMessage());
            throw new Run.RunnerAbortedException();
        } catch (InterruptedException e) {
            logger.error("SEVERE ERROR occurs: " + e.getMessage());
            throw new Run.RunnerAbortedException();
        }

    }

}
