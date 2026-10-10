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
- `libhalsim_vmx.so` (cross-buildable, no VMX headers) loads the Studica backend as a plugin through
  `HALSIMVMX_BACKEND=<path to libhalsim_vmx_studica.so>`; the plugin must be built once on the VMX itself
  (see allwpilib `simulation/halsim_vmx/DESIGN.md`). Set it with `environment.put('HALSIMVMX_BACKEND', ...)`.
  Neither library is published to Maven yet.
- The C++ artifact (`WPILibNativeArtifact`) is written but only registration is tested; it has not been used with a real
  `NativeExecutableSpec` build. aarch64 cross-compilation is enabled for C++ projects (needs the arm64 toolchain download).
- Debug (`debug = true`) adds a JDWP agent only; no gdbserver flow.
- Nothing here has been run against a real VMX-pi.
