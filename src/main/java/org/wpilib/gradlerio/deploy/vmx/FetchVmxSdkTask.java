package org.wpilib.gradlerio.deploy.vmx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.wpilib.deployutils.deploy.sessions.AcceptAllLoggedServerKeyVerifier;
import org.wpilib.deployutils.deploy.target.location.DeployLocation;
import org.wpilib.deployutils.deploy.target.location.SshDeployLocation;

/**
 * Copies the files a VMX-pi build needs but only the robot has (VMXPi.h, libvmxpi_hal_cpp.so) from the robot into the SDK directory, over the same SSH account and address the deploy uses. After that a
 * C++ program cross-compiles on the PC. Files keep their place relative to the filesystem root: a remote
 * /usr/local/lib/vmxpi/libvmxpi_hal_cpp.so lands in {@code <sdk>/usr/local/lib/vmxpi/libvmxpi_hal_cpp.so}; see
 * {@link VmxPi#sdkPath(String)}.
 */
public abstract class FetchVmxSdkTask extends DefaultTask {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final VmxPi target;

    @Inject
    public FetchVmxSdkTask(VmxPi target) {
        this.target = target;
        setGroup("vmx");
        setDescription("Copies the VMX headers and libraries from the robot into the SDK directory");
        // The files live on the robot; there is nothing local to compare, and fetching is cheap.
        getOutputs().upToDateWhen(t -> false);
    }

    /** Remote files or directories (directories are copied recursively). */
    @Input
    public abstract ListProperty<String> getRemotePaths();

    /** SSH port; the deploy locations carry none, so this is 22 unless set. */
    @Input
    public abstract Property<Integer> getPort();

    @OutputDirectory
    public abstract DirectoryProperty getSdkDirectory();

    @Internal
    public VmxPi getTarget() {
        return target;
    }

    @TaskAction
    public void fetch() throws IOException {
        List<SshDeployLocation> locations = findLocations();
        Path root = getSdkDirectory().get().getAsFile().toPath();
        Files.createDirectories(root);

        // The target may have several addresses (the wired port and the Wi-Fi access point of a team): use the first that answers.
        List<String> failures = new ArrayList<>();
        for (SshDeployLocation location : locations) {
            try {
                fetchFrom(location, root);
                return;
            } catch (IOException e) {
                failures.add(location.getAddress() + ": " + e.getMessage());
                getLogger().lifecycle("{} did not answer ({}); trying the next address", location.getAddress(), e.getMessage());
            }
        }
        throw new GradleException("Could not reach the robot on any of its addresses:\n  " + String.join("\n  ", failures));
    }

    private void fetchFrom(SshDeployLocation location, Path root) throws IOException {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(new AcceptAllLoggedServerKeyVerifier(getLogger()));
        client.start();
        try (ClientSession session = client.connect(location.getUser(), location.getAddress(), getPort().get())
                .verify(TIMEOUT).getSession()) {
            session.addPasswordIdentity(location.getPassword());
            session.auth().verify(TIMEOUT);
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                for (String remote : getRemotePaths().get()) {
                    copy(sftp, remote, root);
                }
            }
        } finally {
            client.stop();
        }
    }

    private List<SshDeployLocation> findLocations() {
        List<SshDeployLocation> found = new ArrayList<>();
        List<String> tried = new ArrayList<>();
        for (DeployLocation loc : target.getLocations()) {
            if (loc instanceof SshDeployLocation ssh) {
                found.add(ssh);
            } else {
                tried.add(loc.toString());
            }
        }
        if (found.isEmpty()) {
            throw new GradleException("VmxPi target '" + target.getName() + "' has no SSH address (set team or call addAddress). Found: "
                    + tried);
        }
        return found;
    }

    private void copy(SftpClient sftp, String remote, Path root) throws IOException {
        if (!remote.startsWith("/")) {
            throw new GradleException("Remote path must be absolute: " + remote);
        }
        SftpClient.Attributes attrs;
        try {
            attrs = sftp.stat(remote);
        } catch (IOException e) {
            throw new GradleException("Cannot read " + remote + " on the robot: " + e.getMessage(), e);
        }
        Path local = root.resolve(remote.substring(1));
        if (attrs.isDirectory()) {
            Files.createDirectories(local);
            for (SftpClient.DirEntry entry : sftp.readDir(remote)) {
                String name = entry.getFilename();
                if (name.equals(".") || name.equals("..")) {
                    continue;
                }
                copy(sftp, remote.endsWith("/") ? remote + name : remote + "/" + name, root);
            }
        } else {
            Files.createDirectories(local.getParent());
            try (InputStream in = sftp.read(remote)) {
                Files.copy(in, local, StandardCopyOption.REPLACE_EXISTING);
            }
            getLogger().lifecycle("fetched {}", remote);
        }
    }
}
