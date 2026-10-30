# Studica VMX-pi deploy (W4Studica fork)

This fork adds a `VmxPi` deploy target next to `SystemCore`, for **C++ robot programs** (Java is not supported). All VMX code is in `src/main/java/org/wpilib/gradlerio/deploy/vmx/`; upstream files are touched only to register it (`WPILibDeployPlugin`, one call) and to enable aarch64 cross-compilation (`WPINativeExtension`, one line).

A VMX-pi runs robot code on the **desktop (simulation) HAL** plus a hardware extension (`halsim_studica`), so the target platform is `linuxarm64`, not `linuxsystemcore` (those natives contain the real SystemCore HAL).

Start from `examples/vmx-cpp/` (complete project and steps). Normally the W4Studica VS Code extension installs the pre-release bundles and switches a project to this target; the example builds `halsim_studica` from the `allwpilib` sources instead.

## Deploying

`./gradlew deploy` cross-compiles on the PC, uploads the program and its libraries to `/home/lvuser/wpilib/third-party/lib`, writes `robotCommand` (and `robotCommand.args`) under `/home/lvuser`, and restarts the `robot_manager` service (`serviceName`, default `robot_manager`; the unit also answers to `robot` and the Robot-Manager repository installs it). Once per PC: `./gradlew installArm64Toolchain` (run alone: it ends the run).

The program's stdout and stderr go to the systemd journal and, in WPILib NetConsole framing, to TCP port 1741, so VS Code's RioLog works (`robot.local` is published through Avahi, or use the team address).

## Finding the robot: `team`

```groovy
vmx(getTargetTypeClass('VmxPi')) {
    team = project.wpilib.getTeamNumber()   // VS Code's Set Team Number setting
}
```

`team` adds both addresses the VMX has: the wired port, always `172.22.11.2`, and its own Wi-Fi network at `10.TE.AM.2` (1234 gives `10.12.34.2`). The deploy uses whichever answers. The login is fixed to `lvuser` / `password` (`install.sh` creates `lvuser` for deploys and robot files, and `admin` for interactive SSH); `setUsername`/`setPassword` change it when a robot was set up differently. The SSH server key is accepted without checking.

## Driving the robot from a Driver Station

Robot-Manager's `mrccomm.service` owns the Driver Station sockets and forwards the decoded state
(enable/disable, mode, E-stop, joysticks) over loopback to the robot program, where the
`libhalsim_ds_relay.so` extension applies it to the simulation HAL and returns the battery voltage.
Both extensions are enabled by default (`libhalsim_studica.so` plus `libhalsim_ds_relay.so`), so a
real (or simulated) Driver Station on the robot's network can enable the program and move the
joints exactly as on SystemCore. Joystick decoding follows the documented Driver Station packet
layout; it still needs a check against a real Driver Station. Game data, match time and alliance
station are not relayed yet.

## Fetching what only the robot has

A C++ program needs `VMXPi.h` and `libvmxpi_hal_cpp.so` from the robot's OS image (`vmx-hal`); they cannot be downloaded. `deploy` fetches them over SFTP, with the same login and addresses, into `build/vmxsdk<target>/` before the C++ tasks run. The regular `build` never contacts the robot. Standalone: `./gradlew fetchVmxSdk<target>` (for a target named `vmx`: `fetchVmxSdkvmx`).

```groovy
def vmxHeaders = deploy.targets.vmx.sdkPath('/usr/local/include/vmxpi')
def vmxHal     = deploy.targets.vmx.sdkPath('/usr/local/lib/vmxpi/libvmxpi_hal_cpp.so')
```

The files are reused from the build directory; `-PrefetchVmxSdk` fetches them again after a new robot image. `remotePaths` and `port` can be set on the task. File names sent by the robot are checked, so they cannot write outside the SDK directory.

## Same names as 2027 (SystemCore)

The VMX classes use the same simple names as the SystemCore ones, so a `build.gradle` written for SystemCore works on a `VmxPi` after changing the target type and the platform and configuration names (`vmxDebug` / `vmxRelease` instead of `systemcoreDebug` / `systemcoreRelease`).

| 2027 SystemCore | VmxPi |
|---|---|
| `WPILibNativeArtifact` | C++ program on the desktop HAL + `HALSIM_EXTENSIONS`; no chown or setcap, no gdb flow |
| `RobotCommandArtifact` | writes `robotCommand` / `robotCommand.args`, no chown |
| `RobotProgramKillArtifact` / `RobotProgramStartArtifact` | `systemctl stop` / `enable` + `start` of the service (no `sudo sync` afterwards: it made deploys slow) |
| `WPILibJavaArtifact`, `WPILibJNILibraryArtifact` | exist for symmetry; not supported or tested |

Not ported: `FirstDsDeployLocation` / `NiDsDeployLocation` (robot discovery through a Driver Station; MockDS is user code here) and `WPILibNativeLibraryArtifact`.

## What the VMX must provide

- The `robot_manager` service, which runs `robotCommand` **as root** (the VMX HAL uses pigpio), plus the GPIO shim and LSE emulation it sets up (all from `install.sh` in Robot-Manager). It also creates the `lvuser` deploy account (home `/home/lvuser`) and the `admin` SSH account.
- Passwordless `sudo` for deploy commands (`systemctl` `stop`/`start`/`enable robot_manager`, `ldconfig`); `install.sh --sudoers` also gives `admin` and `lvuser` full passwordless `sudo`.
- `libhalsim_studica.so` and its Studica backend in the library directory. They are built from the `allwpilib` fork (`tools/w4studica/build_vmx_runtime.py`) and shipped with the W4Studica pre-releases, or compiled by the project as in the example. They are not published to Maven.

## Things to know

- **Titan: use `wpi::studica::Titan` (`wpi/studica/Titan.hpp`).** It owns the driver, shared VMX, enable keepalive and watchdog. Feed `FeedControlLoop()` after a completed control cycle and `FeedDriverStation()` after each new valid DS update. The object starts disabled and automatically disables on stale input, HAL disable or E-stop. The low-level `wpi::studica::drivers::Titan` exposes the unchanged vendor API and does not provide this policy.
- **`OnboardIMU::GetQuaternion()` / `GetRotation3d()` use a quaternion constructed from the corrected WPILib Euler angles.** Create `OnboardIMU` with `FLAT`; other mount orientations are not handled. The new hardware paths still need on-robot validation.
- I2C uses one complete SDK transaction on WPILib port 1. DutyCycle uses the same VMX channel number as DIO. Digital edge capture is opt-in through `wpi/hal/simulation/DigitalEdgeData.h`; timestamps retain the VMX hardware microsecond clock. The backend ABI is v4: rebuild the HAL, extension and backend together.
- **When using VMX-backed low-level `wpi::studica::drivers` classes, pass `wpi::studica::SharedVMX()`**; a second `VMXPi` in a process breaks the SPI link.
- `libMrcLib.so` (linked by `wpi.cpp.deps.wpilib()`) uses ARMv8.1 atomics that a Pi 4 lacks; `robot_manager` emulates them through `LD_AUDIT`.
- The published `linuxarm64` natives need glibc 2.34 and `GLIBCXX_3.4.31`; the VMX image is Ubuntu 26.04 (glibc 2.43). Ubuntu 22.04 (GCC 12 libstdc++) lacks `GLIBCXX_3.4.31` and is not used.
- Debug (`debug = true`) adds only a JDWP agent; there is no gdbserver flow.
- Hardware evidence comes from one development VMX-pi.
