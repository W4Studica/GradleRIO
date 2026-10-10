package org.wpilib.gradlerio.deploy.vmx;

import javax.inject.Inject;

import org.wpilib.deployutils.deploy.artifact.AbstractArtifact;
import org.wpilib.deployutils.deploy.context.DeployContext;
import org.wpilib.gradlerio.deploy.DeployStage;

public class VmxProgramKillArtifact extends AbstractArtifact {

    private final VmxPi vmx;

    @Inject
    public VmxProgramKillArtifact(String name, VmxPi target) {
        super(name, target);
        vmx = target;

        target.setDeployStage(this, DeployStage.ProgramKill);
    }

    @Override
    public void deploy(DeployContext ctx) {
        ctx.execute("sudo systemctl stop " + vmx.getServiceName() + " 2> /dev/null");
    }
}
