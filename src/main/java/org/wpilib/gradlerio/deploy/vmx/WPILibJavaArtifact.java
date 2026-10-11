package org.wpilib.gradlerio.deploy.vmx;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.plugins.internal.JavaPluginHelper;
import org.gradle.api.plugins.jvm.internal.JvmFeatureInternal;
import org.gradle.api.provider.Property;
import org.wpilib.deployutils.PathUtils;
import org.wpilib.deployutils.deploy.context.DeployContext;
import org.wpilib.gradlerio.deploy.DebuggableJavaArtifact;
import org.wpilib.gradlerio.deploy.DeployStage;
import org.wpilib.gradlerio.deploy.systemcore.GarbageCollectorType;

/**
 * Deploys a Java robot program to a {@link VmxPi}.
 *
 * The program runs on the desktop (simulation) HAL for linuxarm64 and loads the
 * hardware extension(s) listed in {@link #getHalsimExtensions()} through
 * HALSIM_EXTENSIONS.
 */
public class WPILibJavaArtifact extends DebuggableJavaArtifact {

    private final RobotCommandArtifact robotCommandArtifact;
    private final WPILibJNILibraryArtifact nativeZipArtifact;

    private final List<String> jvmArgs = new ArrayList<>();
    private final List<String> arguments = new ArrayList<>();
    /** Extension libraries, relative to the library directory unless absolute. */
    private final List<String> halsimExtensions = new ArrayList<>(List.of("libhalsim_vmx.so"));
    /** Extra environment variables for the robot program (for example HALSIMVMX_DIO_MAP). */
    private final Map<String, String> environment = new LinkedHashMap<>();

    private final VmxPi vmx;

    private final Property<String> mainClass;

    private GarbageCollectorType gcType = GarbageCollectorType.G1_Base;

    private String javaCommand = "/usr/bin/java";

    private final Property<Boolean> debugJni;

    public Property<Boolean> getDebugJni() {
        return debugJni;
    }

    @Inject
    public WPILibJavaArtifact(String name, VmxPi target) {
        super(name, target);
        vmx = target;
        debugJni = target.getProject().getObjects().property(Boolean.class);
        debugJni.set(false);

        jvmArgs.add("--add-opens");
        jvmArgs.add("java.base/jdk.internal.vm=ALL-UNNAMED");
        jvmArgs.add("--add-opens");
        jvmArgs.add("java.base/java.lang=ALL-UNNAMED");
        jvmArgs.add("--enable-native-access=ALL-UNNAMED");

        var debugConfiguration = target.getProject().getConfigurations().create("vmxDebug");
        var releaseConfiguration = target.getProject().getConfigurations().create("vmxRelease");

        this.mainClass = target.getProject().getObjects().property(String.class);

        // Lazy: the username (and so the directory) is only known once the target is configured.
        this.getDirectory().set(target.getProject().provider(vmx::getClasspathDirectory));
        this.getDeleteOldFiles().set(true);

        robotCommandArtifact = target.getArtifacts().create("robotCommand" + name, RobotCommandArtifact.class, art -> {
            art.setRobotCommandFunc(this::generateStartCommand);
            art.setArgFileFunc(this::generateArgFile);
            art.dependsOn(getJarProvider());
            art.dependsOn(getConfigurationProvider());
            art.dependsOn(this.getDeployTask());
        });

        nativeZipArtifact = target.getArtifacts().create("nativeZips" + name, WPILibJNILibraryArtifact.class, artifact -> {
            target.setDeployStage(artifact, DeployStage.FileDeploy);

            var cbl = target.getProject().getProviders().provider(() -> {
                return getDebugJni().get() ? debugConfiguration : releaseConfiguration;
            });

            artifact.getConfiguration().set(cbl);
            artifact.setZipped(true);
            artifact.getFilter().include("**/*.so*");
            artifact.getFilter().include("**/*.so");
            artifact.getFilter().getExcludes().add("**/*.so.debug");
            artifact.getFilter().getExcludes().add("**/*.so.*.debug");
        });

        target.setDeployStage(this, DeployStage.FileDeploy);
    }

    public String getJavaCommand() {
        return javaCommand;
    }

    public void setJavaCommand(String javaCommand) {
        this.javaCommand = javaCommand;
    }

    public GarbageCollectorType getGcType() {
        return gcType;
    }

    public void setGcType(GarbageCollectorType gcType) {
        this.gcType = gcType;
    }

    public void configureApplication(JavaApplication javaApplication) {
        JvmFeatureInternal mainFeature = JavaPluginHelper.getJavaComponent(getTarget().getProject()).getMainFeature();
        setConfiguration(mainFeature.getRuntimeClasspathConfiguration());
        setJar(mainFeature.getJarTask().get());
        this.mainClass.set(javaApplication.getMainClass());
    }

    public RobotCommandArtifact getRobotCommandArtifact() {
        return robotCommandArtifact;
    }

    public WPILibJNILibraryArtifact getNativeZipArtifact() {
        return nativeZipArtifact;
    }

    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    public List<String> getArguments() {
        return arguments;
    }

    public List<String> getHalsimExtensions() {
        return halsimExtensions;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    private String generateArgFile(DeployContext ctx) {
        List<String> args = new ArrayList<>();
        args.addAll(gcType.getGcArguments());
        args.add("-Djava.library.path=" + vmx.getLibraryDirectory());
        args.addAll(jvmArgs);

        args.add("-cp \"\\");

        // Put the entire deploy classpath
        String deployDirectory = getDirectory().get();

        List<File> files = new ArrayList<>(getFiles().get().getFiles());

        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            String path = PathUtils.combine(deployDirectory, file.getName());
            if (i != files.size() - 1) {
                args.add(path + ":\\");
            } else {
                args.add(path + "\"");
            }
        }

        if (vmx.getDebug().get()) {
            args.add("-XX:+UsePerfData -agentlib:jdwp=transport=dt_socket,address=0.0.0.0:" + getDebugPort() + ",server=y,suspend=y");
        }

        args.add(mainClass.get());

        args.addAll(arguments);

        args.add("");

        return String.join("\n", args);
    }

    /** HALSIM_EXTENSIONS value: absolute library paths separated by ':' (Linux). */
    String extensionString() {
        List<String> paths = new ArrayList<>();
        // Elements may be Groovy GStrings ("${dir}/x.so"), which are not Strings: go through String.valueOf.
        for (Object element : (List<?>) halsimExtensions) {
            String ext = String.valueOf(element);
            paths.add(ext.startsWith("/") ? ext : PathUtils.combine(vmx.getLibraryDirectory(), ext));
        }
        return String.join(":", paths);
    }

    /** NAME="value" pairs for the robot command, each followed by a space. Values may be Groovy GStrings. */
    String environmentString() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) environment).entrySet()) {
            builder.append(entry.getKey()).append("=\"").append(entry.getValue()).append("\" ");
        }
        return builder.toString();
    }

    String generateStartCommand(DeployContext ctx) {
        StringBuilder builder = new StringBuilder();

        if (!halsimExtensions.isEmpty()) {
            builder.append("HALSIM_EXTENSIONS=\"").append(extensionString()).append("\" ");
        }
        builder.append(environmentString());

        builder.append(javaCommand);

        builder.append(" @");
        builder.append(PathUtils.combine(ctx.getWorkingDir(), RobotCommandArtifact.ARG_FILE));

        return builder.toString();
    }
}
