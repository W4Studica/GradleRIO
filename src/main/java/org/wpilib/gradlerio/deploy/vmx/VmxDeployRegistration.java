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

        NamedObjectFactory.registerType(VmxJavaArtifact.class, artifacts, target, objects);
        NamedObjectFactory.registerType(VmxJNILibraryArtifact.class, artifacts, target, objects);
        NamedObjectFactory.registerType(VmxRobotCommandArtifact.class, artifacts, target, objects);
        NamedObjectFactory.registerType(VmxProgramKillArtifact.class, artifacts, target, objects);
        NamedObjectFactory.registerType(VmxProgramStartArtifact.class, artifacts, target, objects);
    }
}
