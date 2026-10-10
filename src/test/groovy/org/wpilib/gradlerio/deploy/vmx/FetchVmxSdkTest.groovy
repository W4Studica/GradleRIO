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

    def "copies the headers, the HAL and the plugin and keeps their paths"() {
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
        new File(sdk, 'opt/halsim_vmx/libhalsim_vmx_studica.so').bytes == [5, 6] as byte[]
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
}
