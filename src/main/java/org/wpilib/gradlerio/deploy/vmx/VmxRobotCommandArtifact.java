package org.wpilib.gradlerio.deploy.vmx;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.inject.Inject;

import org.wpilib.deployutils.PathUtils;
import org.wpilib.deployutils.deploy.artifact.CommandArtifact;
import org.wpilib.deployutils.deploy.context.DeployContext;
import org.wpilib.gradlerio.deploy.DeployStage;
import org.wpilib.gradlerio.deploy.StagedDeployTarget;

/**
 * Writes the robotCommand (and optional robotCommand.args) that robot_manager
 * runs, as root. Unlike the SystemCore version, no chown to a robot user is done.
 */
public class VmxRobotCommandArtifact extends CommandArtifact {

    public static final String ROBOT_COMMAND_FILE = "robotCommand";
    public static final String ARG_FILE = "robotCommand.args";

    private Function<DeployContext, String> robotCommandFunc;
    private Function<DeployContext, String> argFileFunc;

    @Inject
    public VmxRobotCommandArtifact(String name, StagedDeployTarget target) {
        super(name, target);

        target.setDeployStage(this, DeployStage.FileDeploy);
    }

    public Function<DeployContext, String> getRobotCommandFunc() {
        return robotCommandFunc;
    }

    public void setRobotCommandFunc(Function<DeployContext, String> robotCommandFunc) {
        this.robotCommandFunc = robotCommandFunc;
    }

    public Function<DeployContext, String> getArgFileFunc() {
        return argFileFunc;
    }

    public void setArgFileFunc(Function<DeployContext, String> argFileFunc) {
        this.argFileFunc = argFileFunc;
    }

    @Override
    public void deploy(DeployContext ctx) {
        String robotCommandContent = robotCommandFunc.apply(ctx);

        var robotCommandPath = PathUtils.combine(ctx.getWorkingDir(), ROBOT_COMMAND_FILE);
        var argsPath = PathUtils.combine(ctx.getWorkingDir(), ARG_FILE);

        Map<String, String> files = new HashMap<>();
        files.put(robotCommandPath, robotCommandContent);

        if (argFileFunc != null) {
            files.put(argsPath, argFileFunc.apply(ctx));
        }

        ctx.putStringFiles(files);

        ctx.execute("chmod +x " + robotCommandPath);
        if (argFileFunc != null) {
            ctx.execute("chmod +x " + argsPath);
        }
    }
}
