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
                wpilibJava(getArtifactTypeClass('VmxJavaArtifact')) {
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

## What the VMX must provide

- A `robot_manager` systemd service that runs `/home/<u>/robotCommand` **as root** (the VMX HAL uses pigpio).
  It does not exist yet (see `../robot_manager/`).
- Passwordless `sudo` for the SSH user for `systemctl` and `ldconfig`.
- A Java runtime at `/usr/bin/java` (see `javaCommand`).
- `libhalsim_vmx.so` for aarch64 in the library directory. Not published anywhere yet.

## Known risks / not done

- **glibc.** The only published arm64 desktop toolchain is `aarch64-trixie-linux-gnu` (Debian 13, GCC 14.3;
  OpenSDK has no older-glibc arm64 option). The Studica VMX image is documented as Ubuntu 22.04 (glibc 2.35).
  Natives built with the trixie toolchain, including the published `linuxarm64` WPILib natives, are expected
  not to load there (newer glibc/libstdc++ symbol versions). Unverified on hardware. This must be solved
  (newer OS on the VMX, own sysroot, or building on the device) before this target is usable.
- **`libhalsim_vmx.so` cannot be cross-built with the Studica backend** because the VMX headers
  (`VMXPi.h`) are only on the VMX image. A plugin boundary (backend loaded with dlopen) is needed.
- No C++ artifact (`WPILibNativeArtifact` equivalent) yet. aarch64 cross-compilation is enabled for C++ projects,
  but nothing deploys a C++ robot program to a `VmxPi`.
- Debug (`debug = true`) adds a JDWP agent only; no gdbserver flow.
- Nothing here has been run against a real VMX-pi.
