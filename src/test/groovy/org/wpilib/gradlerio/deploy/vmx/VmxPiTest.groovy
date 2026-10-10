package org.wpilib.gradlerio.deploy.vmx

import org.gradle.testkit.runner.GradleRunner
import static org.gradle.testkit.runner.TaskOutcome.*
import spock.lang.TempDir
import spock.lang.Specification

class VmxPiTest extends Specification {
    @TempDir File testProjectDir
    File buildFile
    File settingsFile

    def setup() {
        buildFile = new File(testProjectDir, 'build.gradle')
        settingsFile = new File(testProjectDir, 'settings.gradle')
        settingsFile << ""
    }

    private def run(String... args) {
        return GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments(args.toList() + ['--stacktrace'])
            .withPluginClasspath()
            .build()
    }

    def "VmxPi target registers its tasks and uses the linuxarm64 platform"() {
        given:
        buildFile << """
plugins {
    id 'java'
    id 'application'
    id 'org.wpilib.GradleRIO'
}

deploy {
    targets {
        vmx(getTargetTypeClass('VmxPi')) {
            username = 'tester'
            password = 'secret'
            addAddress('vmx.local')

            artifacts {
                wpilibJava(getArtifactTypeClass('WPILibJavaArtifact')) {
                }
            }
        }
    }
}

tasks.register('showVmx') {
    doLast {
        def t = deploy.targets.vmx
        println "PLATFORM=" + t.targetPlatform.get()
        println "DIR=" + t.directory
        println "LIB=" + t.libraryDirectory
        println "CLASSPATH=" + t.classpathDirectory
        println "SERVICE=" + t.serviceName
        println "LOCATIONS=" + t.locations.names
    }
}
"""
        when:
        def result = run('showVmx')

        then:
        result.task(':showVmx').outcome == SUCCESS
        result.output.contains('PLATFORM=linuxarm64')
        result.output.contains('DIR=/home/tester')
        result.output.contains('LIB=/home/tester/wpilib/third-party/lib')
        result.output.contains('CLASSPATH=/home/tester/wpilib/classpath')
        result.output.contains('SERVICE=robot_manager')
        result.output.contains('vmx.local')
    }

    def "VmxPi deploy tasks exist"() {
        given:
        buildFile << """
plugins {
    id 'java'
    id 'application'
    id 'org.wpilib.GradleRIO'
}

deploy {
    targets {
        vmx(getTargetTypeClass('VmxPi')) {
            username = 'tester'
            password = 'secret'
            addAddress('vmx.local')
        }
    }
}
"""
        when:
        def result = run('tasks', '--all')

        then:
        result.task(':tasks').outcome == SUCCESS
        result.output.contains('deployvmx')
        result.output.contains('deployStagevmxProgramKill')
        result.output.contains('deployStagevmxProgramStart')
    }

    def "addAddress before credentials is rejected with a clear message"() {
        given:
        buildFile << """
plugins {
    id 'java'
    id 'application'
    id 'org.wpilib.GradleRIO'
}

deploy {
    targets {
        vmx(getTargetTypeClass('VmxPi')) {
            addAddress('vmx.local')
        }
    }
}
"""
        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments('tasks')
            .withPluginClasspath()
            .buildAndFail()

        then:
        result.output.contains('set username and password before addAddress')
    }

    def "Java artifact builds HALSIM_EXTENSIONS from absolute paths and creates vmx configurations"() {
        given:
        buildFile << """
plugins {
    id 'java'
    id 'application'
    id 'org.wpilib.GradleRIO'
}

deploy {
    targets {
        vmx(getTargetTypeClass('VmxPi')) {
            username = 'tester'
            password = 'secret'
            addAddress('vmx.local')

            artifacts {
                wpilibJava(getArtifactTypeClass('WPILibJavaArtifact')) {
                    halsimExtensions.add('/opt/other/libext.so')
                    environment.put('HALSIMVMX_IMU', '1')
                }
            }
        }
    }
}

tasks.register('showJava') {
    doLast {
        def a = deploy.targets.vmx.artifacts.wpilibJava
        println "EXT=" + a.extensionString()
        println "ENV=" + a.environment
        println "HAS_RELEASE=" + (configurations.findByName('vmxRelease') != null)
        println "HAS_DEBUG=" + (configurations.findByName('vmxDebug') != null)
    }
}
"""
        when:
        def result = run('showJava')

        then:
        result.output.contains('EXT=/home/tester/wpilib/third-party/lib/libhalsim_vmx.so:/opt/other/libext.so')
        result.output.contains('ENV=[HALSIMVMX_IMU:1]')
        result.output.contains('HAS_RELEASE=true')
        result.output.contains('HAS_DEBUG=true')
    }

    def "VmxPi uses the same artifact type names and artifact names as SystemCore"() {
        given:
        buildFile << """
plugins {
    id 'java'
    id 'application'
    id 'org.wpilib.GradleRIO'
}

deploy {
    targets {
        systemcore(getTargetTypeClass('SystemCore')) {
            addAddress('robot.local')
            artifacts {
                wpilibJava(getArtifactTypeClass('WPILibJavaArtifact')) { }
            }
        }
        vmx(getTargetTypeClass('VmxPi')) {
            username = 'tester'
            password = 'secret'
            addAddress('vmx.local')
            artifacts {
                wpilibJava(getArtifactTypeClass('WPILibJavaArtifact')) { }
            }
        }
    }
}

tasks.register('showTypes') {
    doLast {
        def names = { t -> t.artifacts.names.sort() }
        println "SC=" + names(deploy.targets.systemcore)
        println "VMX=" + names(deploy.targets.vmx)
        ['WPILibJavaArtifact', 'WPILibNativeArtifact', 'WPILibJNILibraryArtifact', 'RobotCommandArtifact',
         'RobotProgramKillArtifact', 'RobotProgramStartArtifact'].each { n ->
            println "TYPE " + n + "=" + deploy.targets.vmx.getArtifactTypeClass(n).name
            println "SCTYPE " + n + "=" + deploy.targets.systemcore.getArtifactTypeClass(n).name
        }
    }
}
"""
        when:
        def result = run('showTypes')
        def out = result.output

        then:
        result.task(':showTypes').outcome == SUCCESS
        // same artifact instance names on both targets (only the target name inside them differs)
        def sc = (out =~ /SC=\[(.*)\]/)[0][1]
        def vmx = (out =~ /VMX=\[(.*)\]/)[0][1]
        sc.replace('systemcore', 'TARGET') == vmx.replace('vmx', 'TARGET')
        out.contains('TYPE WPILibJavaArtifact=org.wpilib.gradlerio.deploy.vmx.WPILibJavaArtifact')
        out.contains('TYPE WPILibNativeArtifact=org.wpilib.gradlerio.deploy.vmx.WPILibNativeArtifact')
        out.contains('TYPE RobotProgramKillArtifact=org.wpilib.gradlerio.deploy.vmx.RobotProgramKillArtifact')
        out.contains('SCTYPE WPILibJavaArtifact=org.wpilib.gradlerio.deploy.systemcore.WPILibJavaArtifact')
        out.contains('SCTYPE RobotProgramStartArtifact=org.wpilib.gradlerio.deploy.systemcore.RobotProgramStartArtifact')
    }
}
