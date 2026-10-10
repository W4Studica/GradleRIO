package org.wpilib.gradlerio.deploy.vmx;

import javax.inject.Inject;

import org.wpilib.deployutils.deploy.artifact.AbstractArtifact;
import org.wpilib.deployutils.deploy.context.DeployContext;
import org.wpilib.gradlerio.deploy.DeployStage;

public class VmxProgramStartArtifact extends AbstractArtifact {

    private final VmxPi vmx;

    @Inject
    public VmxProgramStartArtifact(String name, VmxPi target) {
        super(name, target);
        vmx = target;

        target.setDeployStage(this, DeployStage.ProgramStart);
    }

    @Override
    public void deploy(DeployContext ctx) {
        String service = vmx.getServiceName();
        ctx.execute("sudo systemctl enable " + service + " 2> /dev/null");
        ctx.execute("sudo systemctl start " + service + " 2> /dev/null");
        ctx.execute("sudo sync");
    }
}
