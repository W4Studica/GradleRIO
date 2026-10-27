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
        assert t.username == 'ubuntu'
        assert t.password == 'password'
        assert t.directory == '/home/ubuntu'
        println "LOCATIONS=" + t.locations.names
    }
}
"""
        when:
        def result = run('showVmx')

        then:
        result.task(':showVmx').outcome == SUCCESS
        result.output.contains('PLATFORM=linuxarm64')
        result.output.contains('DIR=/home/ubuntu')
        result.output.contains('LIB=/home/ubuntu/wpilib/third-party/lib')
        result.output.contains('CLASSPATH=/home/ubuntu/wpilib/classpath')
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

    def "VMX defaults provide fixed credentials and allow an address without project settings"() {
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
                    team = 1234
                    addAddress('vmx.local')
                }
            }
        }

tasks.register('verifyVmxDefaults') {
    doLast {
        def t = deploy.targets.vmx
        assert t.username == 'ubuntu'
        assert t.password == 'password'
        assert t.directory == '/home/ubuntu'
        assert t.locations.names.contains('10.12.34.2')
        assert t.locations.names.contains('172.22.11.2')
        assert t.locations.names.contains('vmx.local')
    }
}
"""
        when:
        def result = run('verifyVmxDefaults')

        then:
        result.task(':verifyVmxDefaults').outcome == SUCCESS
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
                    environment.put('HALSIMSTUDICA_IMU', '1')
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
        result.output.contains('EXT=/home/tester/wpilib/third-party/lib/libhalsim_studica.so:/opt/other/libext.so')
        result.output.contains('ENV=[HALSIMSTUDICA_IMU:1]')
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

    def "Groovy GStrings work in environment and halsimExtensions of both artifacts"() {
        given:
        buildFile << """
plugins {
    id 'java'
    id 'application'
    id 'org.wpilib.GradleRIO'
}

def dir = '/opt/gstring'
deploy {
    targets {
        vmx(getTargetTypeClass('VmxPi')) {
            username = 'tester'
            password = 'secret'
            addAddress('vmx.local')

            artifacts {
                wpilibJava(getArtifactTypeClass('WPILibJavaArtifact')) {
                    halsimExtensions.add("\${dir}/libext.so")
                    environment.put('A', "\${dir}/value")
                }
                wpilibCpp(getArtifactTypeClass('WPILibNativeArtifact')) {
                    halsimExtensions.add("\${dir}/libext.so")
                    environment.put('B', "\${dir}/value")
                }
            }
        }
    }
}

tasks.register('showGString') {
    doLast {
        def j = deploy.targets.vmx.artifacts.wpilibJava
        def c = deploy.targets.vmx.artifacts.wpilibCpp
        println "JEXT=" + j.extensionString()
        println "JENV=" + j.environmentString()
        println "CEXT=" + c.extensionString()
        println "CENV=" + c.environmentString()
    }
}
"""
        when:
        def result = run('showGString')

        then:
        result.output.contains('JEXT=/home/tester/wpilib/third-party/lib/libhalsim_studica.so:/opt/gstring/libext.so')
        result.output.contains('JENV=A="/opt/gstring/value" ')
        result.output.contains('CEXT=/home/tester/wpilib/third-party/lib/libhalsim_studica.so:/opt/gstring/libext.so')
        result.output.contains('CENV=B="/opt/gstring/value" ')
    }

    def "team = N adds the wired address and the Wi-Fi access point address of the team"() {
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
            team = 1234
        }
    }
}

tasks.register('showTeam') {
    doLast {
        def t = deploy.targets.vmx
        println "TEAM=" + t.team
        println "LOCATIONS=" + t.locations.collect { it.address }.sort().join(',')
    }
}
"""
        when:
        def result = run('showTeam')

        then:
        result.output.contains('TEAM=1234')
        result.output.contains('LOCATIONS=10.12.34.2,172.22.11.2')
    }

    def "team numbers follow the same rule as the robot's setupHotspot"() {
        expect:
        org.wpilib.gradlerio.deploy.vmx.VmxPi.teamAddress(team) == address

        where:
        team | address
        1234 | '10.12.34.2'
        4444 | '10.44.44.2'
        254  | '10.2.54.2'
        1    | '10.0.1.2'
        9999 | '10.99.99.2'
    }

    def "a team number outside 1-9999 fails the build with a message"() {
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
            team = 12345
        }
    }
}
"""
        when:
        def result = GradleRunner.create().withProjectDir(testProjectDir).withArguments('tasks').withPluginClasspath().buildAndFail()

        then:
        result.output.contains('team number must be 1-9999')
    }

    def "team uses the fixed VMX login by default"() {
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
            team = 1234
        }
        }
}
"""
        when:
        def result = run('tasks')

        then:
        result.task(':tasks').outcome == SUCCESS
    }

    def "team and addAddress can be combined and a repeated team does not fail"() {
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
            addAddress('172.22.11.2')
            team = 1234
            team = 1234
        }
    }
}

tasks.register('showLocations') {
    doLast {
        println "LOCATIONS=" + deploy.targets.vmx.locations.collect { it.address }.sort().join(',')
    }
}
"""
        when:
        def result = run('showLocations')

        then:
        result.output.contains('LOCATIONS=10.12.34.2,172.22.11.2')
    }
}
