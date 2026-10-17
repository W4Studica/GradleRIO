package org.wpilib.gradlerio.deploy.vmx

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/** Runs fetchVmxSdk against an embedded SFTP server whose root stands in for the robot's filesystem. */
class FetchVmxSdkTest extends Specification {
    @TempDir File projectDir
    @TempDir File robotRoot
    SshServer server

    def setup() {
        server = SshServer.setUpDefaultServer()
        server.port = 0
        server.keyPairProvider = new SimpleGeneratorHostKeyProvider(new File(robotRoot.parentFile, "hostkey-${System.nanoTime()}").toPath())
        server.passwordAuthenticator = { user, pass, session -> user == 'tester' && pass == 'secret' }
        server.subsystemFactories = [new SftpSubsystemFactory()]
        server.fileSystemFactory = new VirtualFileSystemFactory(robotRoot.toPath())
        server.start()

        new File(robotRoot, 'usr/local/include/vmxpi').mkdirs()
        new File(robotRoot, 'usr/local/include/vmxpi/VMXPi.h').text = '// header'
        new File(robotRoot, 'usr/local/include/vmxpi/sub').mkdirs()
        new File(robotRoot, 'usr/local/include/vmxpi/sub/Inner.h').text = '// inner'
        new File(robotRoot, 'usr/local/lib/vmxpi').mkdirs()
        new File(robotRoot, 'usr/local/lib/vmxpi/libvmxpi_hal_cpp.so').bytes = [1, 2, 3, 4] as byte[]
        new File(robotRoot, 'opt/halsim_vmx').mkdirs()
        new File(robotRoot, 'opt/halsim_vmx/libhalsim_vmx_studica.so').bytes = [5, 6] as byte[]
    }

    def cleanup() {
        server?.stop(true)
    }

    private def project(String extra = '') {
        new File(projectDir, 'settings.gradle').text = ''
        new File(projectDir, 'build.gradle').text = """
plugins {
    id 'java'
    id 'application'
    id 'org.wpilib.GradleRIO'
}
deploy {
    targets {
        vmx(getTargetTypeClass('VmxPi')) {
            username = 'tester'
            password = '${extra ?: 'secret'}'
            addAddress('127.0.0.1')
        }
    }
}
tasks.named('fetchVmxSdkvmx') { port = ${server.port} }
"""
    }

    private GradleRunner runner(String... args) {
        GradleRunner.create().withProjectDir(projectDir).withArguments(args.toList() + ['--stacktrace']).withPluginClasspath()
    }

    def "copies the headers and the HAL and keeps their paths"() {
        given:
        project()

        when:
        def result = runner('fetchVmxSdkvmx').build()

        then:
        result.task(':fetchVmxSdkvmx').outcome == SUCCESS
        def sdk = new File(projectDir, 'build/vmxsdkvmx')
        new File(sdk, 'usr/local/include/vmxpi/VMXPi.h').text == '// header'
        new File(sdk, 'usr/local/include/vmxpi/sub/Inner.h').text == '// inner'
        new File(sdk, 'usr/local/lib/vmxpi/libvmxpi_hal_cpp.so').bytes == [1, 2, 3, 4] as byte[]
        !new File(sdk, 'opt').exists()   // not a default: halsim_vmx and its plugin are built on the PC
    }

    def "other robot files can be asked for"() {
        given:
        project()
        new File(projectDir, 'build.gradle') << "\ntasks.named('fetchVmxSdkvmx') { remotePaths = ['/opt/halsim_vmx/libhalsim_vmx_studica.so'] }\n"

        when:
        def result = runner('fetchVmxSdkvmx').build()

        then:
        result.task(':fetchVmxSdkvmx').outcome == SUCCESS
        new File(projectDir, 'build/vmxsdkvmx/opt/halsim_vmx/libhalsim_vmx_studica.so').bytes == [5, 6] as byte[]
    }

    def "a wrong password fails the task"() {
        given:
        project('wrong')

        when:
        def result = runner('fetchVmxSdkvmx').buildAndFail()

        then:
        result.output.contains('fetchVmxSdkvmx')
        !new File(projectDir, 'build/vmxsdkvmx/usr').exists()
    }

    def "a file missing on the robot is named in the error"() {
        given:
        project()
        new File(projectDir, 'build.gradle') << "\ntasks.named('fetchVmxSdkvmx') { remotePaths = ['/opt/not/there.so'] }\n"

        when:
        def result = runner('fetchVmxSdkvmx').buildAndFail()

        then:
        result.output.contains('/opt/not/there.so')
    }

    def "an address that does not answer is skipped and the next one is used"() {
        given: "the first address (by name) has nothing listening, the second is the server"
        project()
        new File(projectDir, 'build.gradle').text = new File(projectDir, 'build.gradle').text.replace(
                "addAddress('127.0.0.1')", "addAddress('127.0.0.0')\n            addAddress('127.0.0.1')")

        when:
        def result = runner('fetchVmxSdkvmx').build()

        then:
        result.task(':fetchVmxSdkvmx').outcome == SUCCESS
        new File(projectDir, 'build/vmxsdkvmx/usr/local/include/vmxpi/VMXPi.h').text == '// header'
    }

    def "when no address answers the error lists every one"() {
        given:
        project()
        new File(projectDir, 'build.gradle').text = new File(projectDir, 'build.gradle').text.replace(
                "addAddress('127.0.0.1')", "addAddress('127.0.0.0')\n            addAddress('127.0.0.2')") +
                "\ntasks.named('fetchVmxSdkvmx') { port = 1; attempts = 2; retryDelaySeconds = 0 }\n"

        when:
        def result = runner('fetchVmxSdkvmx').buildAndFail()

        then:
        result.output.contains('Could not reach the robot on any of its addresses (2 rounds)')
        result.output.contains('The robot did not answer (round 1 of 2); trying again')
        result.output.contains('127.0.0.0')
        result.output.contains('127.0.0.2')
    }

    def "directory entry names from the robot must be a single path component"() {
        when:
        FetchVmxSdkTask.checkEntryName(name, '/usr/local/include/vmxpi')

        then:
        thrown(org.gradle.api.GradleException)

        where:
        name << ['', 'a/b', '../x', '/etc/passwd', 'a\\b', 'nul\0name']
    }

    def "ordinary names are accepted"() {
        when:
        FetchVmxSdkTask.checkEntryName(name, '/usr/local/include/vmxpi')

        then:
        noExceptionThrown()

        where:
        name << ['VMXPi.h', 'libvmxpi_hal_cpp.so', '.hidden', 'a..b']
    }
}
