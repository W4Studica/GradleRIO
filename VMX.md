# Studica VMX-pi deploy (W4Studica fork)

This fork adds a `VmxPi` deploy target next to `SystemCore`. All VMX code lives in
`src/main/java/org/wpilib/gradlerio/deploy/vmx/`; upstream files are touched only to register it
(`WPILibDeployPlugin`, one call) and to enable aarch64 cross-compilation (`WPINativeExtension`, one line).

## Platform: `linuxarm64`, not `linuxsystemcore`

The VMX-pi runs robot code on the **desktop (simulation) HAL** plus a hardware extension
(`halsim_vmx`). `linuxsystemcore` natives contain the real SystemCore HAL and are useless on a VMX.
So the target platform is `linuxarm64` (`wpi.platforms.linuxarm64`).

## Usage (Java)

```groovy
deploy {
    targets {
        vmx(getTargetTypeClass('VmxPi')) {
            // Nothing is assumed. Set these before addAddress.
            username = '<ssh user>'
            password = '<ssh password>'
            addAddress('<host or ip>')

            artifacts {
                wpilibJava(getArtifactTypeClass('WPILibJavaArtifact')) {
                    // halsimExtensions defaults to ['libhalsim_vmx.so'] (relative to the library dir)
                    environment.put('HALSIMVMX_DIO_MAP', '0:12,1:13')
                    environment.put('HALSIMVMX_IMU', '1')
                }
            }
        }
    }
}

dependencies {
    implementation wpi.java.deps.wpilib()
    vmxRelease wpi.java.deps.wpilibJniRelease(wpi.platforms.linuxarm64)
    vmxDebug   wpi.java.deps.wpilibJniDebug(wpi.platforms.linuxarm64)
}
```

`./gradlew deploy` uploads the classpath and libraries, writes `robotCommand` (and
`robotCommand.args`) under `/home/<username>`, and restarts the `robot_manager` service
(`serviceName`, default `robot_manager`).

Directories (derived from `username`): `/home/<u>`, `/home/<u>/wpilib/classpath`,
`/home/<u>/wpilib/third-party/lib`.

## Fetching what only the robot has (C++)

A C++ program needs `VMXPi.h` and `libvmxpi_hal_cpp.so`. They come with the robot's OS image (the `vmx-hal` package) and cannot be
downloaded. `./gradlew fetchVmxSdk<target>` (for a target named `vmx`: `fetchVmxSdkvmx`) copies them over the same SSH login and
address the deploy uses (SFTP, no extra tools) into `build/vmxsdk<target>/`, keeping their paths. Everything else, including
`halsim_vmx` and its Studica backend plugin, is compiled on the PC (see `examples/vmx-cpp/`, a complete project,
and `allwpilib/simulation/halsim_vmx/DESIGN.md`).

```groovy
def vmxHeaders = deploy.targets.vmx.sdkPath('/usr/local/include/vmxpi')
def vmxHal     = deploy.targets.vmx.sdkPath('/usr/local/lib/vmxpi/libvmxpi_hal_cpp.so')
```

`remotePaths` (default: the two paths above; directories are copied recursively) and `port` (default 22) can be set on the
task. The server key is accepted without checking, like the deploy does. Run it once per robot image; the build itself is
offline afterwards.

## Same names as 2027 (SystemCore)

The VMX classes use the **same simple class names** as the SystemCore ones, because
`getArtifactTypeClass(name)` resolves by simple name per target. A `build.gradle` written for SystemCore
therefore works on a `VmxPi` by changing only the target type (and the platform/configuration names).

| 2027 SystemCore | VmxPi (`deploy/vmx/`) |
|---|---|
| `WPILibJavaArtifact` | same name, runs on desktop HAL + `HALSIM_EXTENSIONS` |
| `WPILibNativeArtifact` | same name (C++), no chown/setcap, no gdb flow yet |
| `WPILibJNILibraryArtifact` | same name, deploys linuxarm64 libraries |
| `RobotCommandArtifact` | same name, writes `robotCommand` / `robotCommand.args`, no chown |
| `RobotProgramKillArtifact` | same name (`programKill<target>`), `systemctl stop <service>` |
| `RobotProgramStartArtifact` | same name (`programStart<target>`), `systemctl enable/start <service>` |

Intentionally not ported: `FirstDsDeployLocation` / `NiDsDeployLocation` and the DS exceptions (robot discovery
through a Driver Station; MockDS is user code here) and `WPILibNativeLibraryArtifact` (an empty stub upstream).
Configuration names differ so both targets can coexist: `vmxDebug` / `vmxRelease` instead of `systemcoreDebug` / `systemcoreRelease`.

## What the VMX must provide

- A `robot_manager` systemd service that runs `/home/<u>/robotCommand` **as root** (the VMX HAL uses pigpio).
  `robotCommand` is the contract, same as 2027. The service does not exist yet (see `../robot_manager/`).
- Passwordless `sudo` for the SSH user for `systemctl` and `ldconfig`.
- A Java runtime at `/usr/bin/java` (see `javaCommand`).
- `libhalsim_vmx.so` for aarch64 in the library directory. Not published anywhere yet.

## Known risks / not done

- **glibc (resolved on paper for the development robot; measured on the published library).** `libwpiHal.so` from `org.wpilib.hal:hal-cpp:2027.0.0-alpha-7:linuxarm64`
  needs at most `GLIBC_2.34` and `GLIBCXX_3.4.31` (not the 2.41 of the toolchain), and it is the simulation HAL (460 `HALSIM_*` symbols). Ubuntu 22.04 would pass the glibc
  check but its libstdc++ (GCC 12) lacks `GLIBCXX_3.4.31`. Other WPILib libraries were not checked, and nothing was run on the robot with them.
- (older note, kept for context) The development VMX runs
  Ubuntu 26.04.1 with glibc 2.43, which is newer than the toolchain's 2.41, so the published `linuxarm64` natives are expected to load.
  Run the `ldd`/`objdump` check in `HARDWARE_CHECKLIST.md` section 5 to confirm. For an Ubuntu 22.04 VMX the original text below still applies.
  The only published arm64 desktop toolchain is `aarch64-trixie-linux-gnu` (Debian 13, GCC 14.3;
  OpenSDK has no older-glibc arm64 option). The Studica VMX image is documented as Ubuntu 22.04 (glibc 2.35).
  Natives built with the trixie toolchain, including the published `linuxarm64` WPILib natives, are expected
  not to load there (newer glibc/libstdc++ symbol versions). Unverified on hardware. This must be solved
  (newer OS on the VMX, own sysroot, or building on the device) before this target is usable.
- `libhalsim_vmx.so` (no VMX headers needed) loads the Studica backend as a plugin through
  `HALSIMVMX_BACKEND=<path to libhalsim_vmx_studica.so>`. Neither library is published to Maven; a C++ project compiles both
  from the `allwpilib` fork's sources as extra components and deploys them with the other libraries (see "C++ example" below).
  The plugin needs `VMXPi.h` and `libvmxpi_hal_cpp.so` from `fetchVmxSdk<target>`. A Java project has no way to build them yet.
- **`libMrcLib.so` (WPILib, binary only) kills a Pi 4 with `SIGILL`.** It is built with the SystemCore toolchain and uses ARMv8.1
  atomics, which a Cortex-A72 lacks; it is linked into a C++ program by `wpi.cpp.deps.wpilib()`. The robot's `robot_manager`
  works around it (`lse_emu`, emulates the instructions through `LD_AUDIT`; about 27 microseconds per instruction), see the
  Robot-Manager repository. Nothing to do in the project, but it is why a program that exits without it exits with code 132.
- The C++ artifact (`WPILibNativeArtifact`) was used with a real `NativeExecutableSpec` build and deployed to a VMX-pi
  (2026-10-11, see "C++ example"). aarch64 cross-compilation is enabled for C++ projects (needs `installArm64Toolchain` once).
- Debug (`debug = true`) adds a JDWP agent only; no gdbserver flow.
- Nothing here has been run against a real VMX-pi.

## C++ example (measured on a VMX-pi, 2026-10-11)

The complete project is `examples/vmx-cpp/` (build.gradle, a robot program using `AnalogInput`, `Encoder`, `OnboardIMU` and a Titan, and a
README with the steps). The excerpt below shows the parts that matter.

Cross compiled on the PC, deployed with `./gradlew deploy`, run by `robot_manager`. Only `VMXPi.h` and `libvmxpi_hal_cpp.so` come
from the robot. `halsim_vmx` and the Studica backend plugin are built from the `allwpilib` fork's sources as two extra
`NativeLibrarySpec` components and uploaded with the other libraries. The parts that matter in `build.gradle`:

```groovy
plugins { id "cpp"; id "org.wpilib.GradleRIO" }

deploy { targets { vmx(getTargetTypeClass('VmxPi')) {
    username = '<ssh user>'; password = '<ssh password>'; addAddress('<host or ip>')
    artifacts { wpilibCpp(getArtifactTypeClass('WPILibNativeArtifact')) {
        // halsimExtensions defaults to <library dir>/libhalsim_vmx.so, which deploy uploads (it is linked below)
        environment.put('HALSIMVMX_BACKEND', "/home/<ssh user>/wpilib/third-party/lib/libhalsim_vmx_studica.so")
        environment.put('LD_PRELOAD', '/opt/robot_manager/lib/libvmx_gpio_isr_shim.so')   // kernels without /sys/class/gpio
        environment.put('HALSIMVMX_DIO_MAP', '0:0,1:1')
    } }
} } }

def vmxHeaders = deploy.targets.vmx.sdkPath('/usr/local/include/vmxpi')                      // from fetchVmxSdkvmx
def vmxHal     = deploy.targets.vmx.sdkPath('/usr/local/lib/vmxpi/libvmxpi_hal_cpp.so')
def ext        = '<path to>/allwpilib/simulation/halsim_vmx'

model { components {
    halsim_vmx(NativeLibrarySpec) {
        targetPlatform wpi.platforms.linuxarm64
        sources.cpp { source { srcDir "$ext/src/main/native/cpp" }; exportedHeaders { srcDir "$ext/src/main/native/include" } }
        wpi.cpp.deps.useLibrary(it, 'hal_shared', 'wpiutil_shared')
    }
    halsim_vmx_studica(NativeLibrarySpec) {
        targetPlatform wpi.platforms.linuxarm64
        sources {
            cpp { source { srcDir "$ext/src/studica/native/cpp" }
                  exportedHeaders { srcDir "$ext/src/studica/native/include"; srcDir "$ext/src/main/native/include" } }
            studicaDrivers(CppSourceSet) { source { srcDir '<path to>/allwpilib/studica_drivers'
                                                    include 'analog_input.cpp', 'dio.cpp', 'encoder.cpp', 'imu.cpp' } }
        }
        binaries.all {
            cppCompiler.args '-I' + vmxHeaders.path, '-I<path to>/allwpilib/studica_drivers'
            linker.args vmxHal.path, '-Wl,-rpath,/usr/local/lib/vmxpi', '-Wl,-rpath-link,' + vmxHal.parent, '-Wl,--allow-shlib-undefined'
        }
    }
    robot(NativeExecutableSpec) {
        targetPlatform wpi.platforms.linuxarm64
        sources.cpp { source { srcDir 'src/main/cpp' } }
        binaries.all {
            // The robot program must use the plugin's VMXPi: wpilibvmx::SharedVMX()
            lib library: 'halsim_vmx_studica', linkage: 'shared'
            lib library: 'halsim_vmx', linkage: 'shared'      // makes deploy upload the extension; the HAL loads it by path
            cppCompiler.args '-I' + vmxHeaders.path, '-I<path to>/allwpilib/studica_drivers', "-I$ext/src/main/native/include",
                             "-I$ext/src/studica/native/include"
            linker.args vmxHal.path, '-Wl,-rpath,/usr/local/lib/vmxpi', '-Wl,-rpath-link,' + vmxHal.parent, '-Wl,--allow-shlib-undefined'
        }
        deploy.targets.vmx.artifacts.wpilibCpp.component = it     // binds the deploy artifact to this program
        wpi.cpp.enableExternalTasks(it)
        wpi.cpp.deps.wpilib(it)
    }
} }
```

Once per robot image: `./gradlew fetchVmxSdkvmx`; once per PC: `./gradlew installArm64Toolchain`. Then `./gradlew deploy`.
