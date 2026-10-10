package org.wpilib.gradlerio.deploy.vmx;

import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.wpilib.deployutils.deploy.DeployExtension;
import org.wpilib.deployutils.deploy.NamedObjectFactory;
import org.wpilib.deployutils.deploy.artifact.Artifact;
import org.wpilib.gradlerio.deploy.WPILibExtension;

/** Registers the VmxPi target type and its artifact types. Kept out of WPILibDeployPlugin to ease merging. */
public final class VmxDeployRegistration {
    private VmxDeployRegistration() {
    }

    public static void register(Project project, DeployExtension deployExtension, WPILibExtension firstExtension) {
        deployExtension.getTargets().registerFactory(VmxPi.class, name -> {
            VmxPi target = project.getObjects().newInstance(VmxPi.class, name, project, deployExtension, firstExtension);
            configureTypes(target);
            return target;
        });
    }

    private static void configureTypes(VmxPi target) {
        ObjectFactory objects = target.getProject().getObjects();
        ExtensiblePolymorphicDomainObjectContainer<Artifact> artifacts = target.getArtifacts();

        // Same simple class names as the SystemCore artifacts, so a build.gradle's
        // getArtifactTypeClass('WPILibJavaArtifact') etc. works unchanged on a VmxPi target.
        NamedObjectFactory.registerType(WPILibJavaArtifact.class, artifacts, target, objects);
        NamedObjectFactory.registerType(WPILibNativeArtifact.class, artifacts, target, objects);
        NamedObjectFactory.registerType(WPILibJNILibraryArtifact.class, artifacts, target, objects);
        NamedObjectFactory.registerType(RobotCommandArtifact.class, artifacts, target, objects);
        NamedObjectFactory.registerType(RobotProgramKillArtifact.class, artifacts, target, objects);
        NamedObjectFactory.registerType(RobotProgramStartArtifact.class, artifacts, target, objects);
    }
}
