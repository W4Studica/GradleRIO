package org.wpilib.gradlerio.deploy.vmx;

import java.io.File;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.util.PatternFilterable;
import org.gradle.api.tasks.util.PatternSet;
import org.wpilib.deployutils.deploy.artifact.FileCollectionArtifact;
import org.wpilib.deployutils.deploy.context.DeployContext;

/** Deploys native libraries (JNI zips for linuxarm64) to the VMX library directory. */
public class WPILibJNILibraryArtifact extends FileCollectionArtifact {
    private final Property<Configuration> configuration;
    private boolean zipped;
    private final PatternFilterable filter;
    private final VmxPi vmx;

    @Inject
    public WPILibJNILibraryArtifact(String name, VmxPi target) {
        super(name, target);
        vmx = target;

        filter = new PatternSet();

        // Lazy: the username (and so the directory) is only known once the target is configured.
        getDirectory().set(target.getProject().provider(vmx::getLibraryDirectory));

        configuration = target.getProject().getObjects().property(Configuration.class);

        setOnlyIf(ctx -> {
            return getFiles().isPresent() && !getFiles().get().isEmpty() && !getFiles().get().getFiles().isEmpty();
        });

        getPreWorkerThread().add(cfg -> {
            if (!configuration.isPresent()) {
                return;
            }
            getFiles().set(computeFiles());
        });

        getPostdeploy().add(ctx -> {
            ctx.execute("sudo ldconfig " + vmx.getLibraryDirectory());
        });
    }

    public Property<Configuration> getConfiguration() {
        return configuration;
    }

    public boolean isZipped() {
        return zipped;
    }

    public void setZipped(boolean zipped) {
        this.zipped = zipped;
    }

    public PatternFilterable getFilter() {
        return filter;
    }

    @Override
    public void deploy(DeployContext ctx) {
        super.deploy(ctx);
    }

    public FileCollection computeFiles() {
        Set<File> configFileCaches = configuration.get().getIncoming().getFiles().getFiles();
        if (zipped) {
            Optional<FileTree> allFiles = configFileCaches.stream()
                    .map(file -> getTarget().getProject().zipTree(file).matching(filter))
                    .reduce((a, b) -> a.plus(b));
            if (allFiles.isPresent()) {
                return allFiles.get();
            } else {
                return getTarget().getProject().files();
            }
        } else {
            return getTarget().getProject().files(configFileCaches);
        }
    }
}
