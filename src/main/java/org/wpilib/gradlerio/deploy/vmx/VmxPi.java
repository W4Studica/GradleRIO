package org.wpilib.gradlerio.deploy.vmx;

import java.io.File;

import javax.inject.Inject;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.wpilib.deployutils.deploy.DeployExtension;
import org.wpilib.deployutils.deploy.target.location.SshDeployLocation;
import org.wpilib.gradlerio.deploy.WPILibExtension;
import org.wpilib.gradlerio.deploy.WPIRemoteTarget;
import org.wpilib.toolchain.NativePlatforms;

/**
 * Studica VMX-pi (Raspberry Pi, Linux aarch64).
 *
 * Unlike SystemCore, the VMX-pi runs robot code on the desktop (simulation) HAL
 * with a hardware extension (halsim_vmx), so the target platform is
 * {@link NativePlatforms#linuxarm64}, not {@link NativePlatforms#systemcore}.
 *
 * No credentials, host names or paths are assumed. Set {@code username} and
 * {@code password} first, then {@link #addAddress(String)}.
 */
public class VmxPi extends WPIRemoteTarget {

    /** Name of the systemd service (robot_manager) that starts the robot program. */
    public static final String DEFAULT_SERVICE_NAME = "robot_manager";

    private String username;
    private String password;
    private String serviceName = DEFAULT_SERVICE_NAME;

    private final RobotProgramKillArtifact programKillArtifact;
    private final RobotProgramStartArtifact programStartArtifact;

    @Inject
    public VmxPi(String name, Project project, DeployExtension de, WPILibExtension firstExtension) {
        super(name, project, de, firstExtension);

        setMaxChannels(4);
        setTimeout(7);

        programKillArtifact = project.getObjects().newInstance(RobotProgramKillArtifact.class, "programKill" + name, this);
        programStartArtifact = project.getObjects().newInstance(RobotProgramStartArtifact.class, "programStart" + name, this);

        getTargetPlatform().set(NativePlatforms.linuxarm64);

        getArtifacts().add(programKillArtifact);
        getArtifacts().add(programStartArtifact);

        // Copies what only the robot has (VMXPi.h, libvmxpi_hal_cpp.so) so C++ cross-builds.
        project.getTasks().register("fetchVmxSdk" + name, FetchVmxSdkTask.class, this).configure(task -> {
            task.getSdkDirectory().set(project.getLayout().getBuildDirectory().dir("vmxsdk" + name));
            task.getRemotePaths().convention(DEFAULT_SDK_PATHS);
            task.getPort().convention(22);
        });
    }

    /**
     * What only the robot has and a C++ build needs: the VMX headers and the VMX HAL library, both from the OS image.
     * The halsim_vmx extension and its Studica backend plugin are compiled on the PC, not copied from the robot.
     */
    public static final java.util.List<String> DEFAULT_SDK_PATHS = java.util.List.of(
            "/usr/local/include/vmxpi",
            "/usr/local/lib/vmxpi/libvmxpi_hal_cpp.so");

    /**
     * Location of a robot file after {@code fetchVmxSdk<name>}: {@code sdkPath("/usr/local/include/vmxpi")}
     * is the directory to pass as an include path.
     */
    public File sdkPath(String remotePath) {
        return new File(getProject().getLayout().getBuildDirectory().dir("vmxsdk" + getName()).get().getAsFile(),
                remotePath.startsWith("/") ? remotePath.substring(1) : remotePath);
    }

    public RobotProgramKillArtifact getProgramKillArtifact() {
        return programKillArtifact;
    }

    public RobotProgramStartArtifact getProgramStartArtifact() {
        return programStartArtifact;
    }

    public String getUsername() {
        return username;
    }

    /** Also sets the deploy directory to /home/&lt;username&gt;. Override with setDirectory afterwards. */
    public void setUsername(String username) {
        this.username = username;
        setDirectory("/home/" + username);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    /** Directory the robot command, classpath and libraries are deployed under. */
    public String getHomeDirectory() {
        if (username == null) {
            throw new GradleException("VmxPi target '" + getName() + "': username is not set");
        }
        return getDirectory();
    }

    public String getClasspathDirectory() {
        return getHomeDirectory() + "/wpilib/classpath";
    }

    public String getLibraryDirectory() {
        return getHomeDirectory() + "/wpilib/third-party/lib";
    }

    /**
     * Adds an SSH address (host name or IP). username and password must be set before this call.
     */
    public void addAddress(String address) {
        if (username == null || password == null) {
            throw new GradleException("VmxPi target '" + getName()
                    + "': set username and password before addAddress(\"" + address + "\")");
        }
        getLocations().create(address, SshDeployLocation.class, loc -> {
            loc.setAddress(address);
            loc.setIpv6(false);
            loc.setUser(username);
            loc.setPassword(password);
        });
    }

    @Override
    public String toString() {
        return "VmxPi[" + getName() + "]";
    }
}
