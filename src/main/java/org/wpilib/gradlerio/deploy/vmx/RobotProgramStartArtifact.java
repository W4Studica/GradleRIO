package org.wpilib.gradlerio.deploy.vmx;

import javax.inject.Inject;

import org.wpilib.deployutils.deploy.artifact.AbstractArtifact;
import org.wpilib.deployutils.deploy.context.DeployContext;
import org.wpilib.gradlerio.deploy.DeployStage;

public class RobotProgramStartArtifact extends AbstractArtifact {

    private final VmxPi vmx;

    @Inject
    public RobotProgramStartArtifact(String name, VmxPi target) {
        super(name, target);
        vmx = target;

        target.setDeployStage(this, DeployStage.ProgramStart);
    }

    @Override
    public void deploy(DeployContext ctx) {
        String service = vmx.getServiceName();
        ctx.execute("sudo systemctl enable " + service + " 2> /dev/null");
        ctx.execute("sudo systemctl start " + service + " 2> /dev/null");
        // Deploy completes before VS Code opens its standard RioLog view. Wait for
        // the VMX NetConsole-compatible listener so that view can connect at once.
        String portHex = String.format("%04X", VmxPi.RIOLOG_PORT);
        ctx.execute("i=0; while [ \"$i\" -lt 100 ]; do "
                + "if grep -qi ':" + portHex + " .* 0A ' /proc/net/tcp; then exit 0; fi; "
                + "i=$((i+1)); sleep 0.1; done; "
                + "echo 'robot_manager RioLog listener did not open TCP port " + VmxPi.RIOLOG_PORT
                + " within 10 seconds; robot program is running but RioLog may be unavailable' >&2; exit 0");
        ctx.execute("sudo sync");
    }
}
