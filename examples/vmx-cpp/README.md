# VMX-pi C++ example (WPILib 2027 on a Studica VMX)

A robot program written with WPILib's standard classes (`AnalogInput`, `Encoder`, `OnboardIMU`) plus a Studica Titan, cross compiled
on your PC and deployed with `./gradlew deploy`. On the robot, `robot_manager` runs it as a service.

What runs where:

* **PC**: builds the program, `libhalsim_studica.so` (connects WPILib's simulated hardware to the VMX) and
  `libstudica_hardware.so` (the Studica drivers behind it) for `linuxarm64`, then uploads everything.
* **VMX-pi**: `robot_manager` (Robot-Manager repository) starts `~/robotCommand`, which launches the program with the simulation HAL.
  The only things taken from the robot are the VMX headers and `libvmxpi_hal_cpp.so` (they come with the `vmx-hal` package).

## Before the first deploy

1. **Robot**: install `robot_manager` and the helpers once, on the robot (`Robot-Manager/install.sh`, see its README). It also
   installs the GPIO shim and the LSE emulation this example relies on.
2. **PC layout**: this folder lives in the `GradleRIO` checkout, and the `allwpilib` fork is next to `GradleRIO`
   (`W4Studica/allwpilib`, `W4Studica/GradleRIO`). Another place: `-PallwpilibDir=/path/to/allwpilib`.
   This source-build example also requires matching W4Studica Maven libraries; upstream-only WPILib lacks the fork HAL hooks. Use the VS Code extension for the normal prebuilt-release workflow.
3. **PC, once**: `./gradlew installArm64Toolchain` (downloads the `aarch64-trixie` cross compiler, about 400 MB).
4. **Robot address**: set the team number in VS Code with "Set Team Number". GradleRIO reads it from
   `.wpilib/wpilib_preferences.json` and tries the wired port (`172.22.11.2`) and the matching Wi-Fi access point
   (`10.TE.AM.2`; team 1234 gives `10.12.34.2`), using whichever answers. The SSH login is fixed to `ubuntu` / `password`.
5. **PC**: nothing to do: `./gradlew deploy` fetches `VMXPi.h` and `libvmxpi_hal_cpp.so` from the robot the first time (`-PrefetchVmxSdk` fetches again, e.g. after a new robot image).

## Build and deploy

```bash
./gradlew build      # compile only
./gradlew deploy     # build, upload, restart robot_manager
```

Look at the program's output on the robot: `sudo journalctl -u robot_manager -f`.

## Adapt it

* **Wiring**: DIO/PWM use VMX channel numbers directly; AnalogInput 0–3 maps to VMX 22–25. VMX channels include FlexDIO 0-11, encoder pairs (0,1) (2,3) ... A even, B odd,
  and AnalogIn 22-25.
* **Titan**: Set `kTitanId`, `kTitanMotor` and `kTitanSpeed` in the C++ source. Leave `kTitanId` empty for no Titan. **Take the wheels off the ground before
  the first run with a speed above 0.**
* **Enabling the robot**: there is no Driver Station on a VMX. `main.cpp` marks the simulated Driver Station attached and enabled
  after a second; your program decides this (that is the "MockDS"). `wpi::studica::Titan` follows that state and owns enable keepalive and freshness checks. Feed its control heartbeat after each completed cycle and its DS heartbeat after each valid update.
* **Low-level drivers**: include `wpi/studica/drivers/<Device>.hpp` and use `wpi::studica::drivers::<Device>`. Always pass `wpi::studica::SharedVMX()` as the `vmx` argument. Without it each class creates its own
  `VMXPi` and the SPI link breaks (measured).

## Known limits

* Verified on one VMX-pi (Raspberry Pi 4, Ubuntu 26.04, kernel 7.0).
* `libMrcLib.so` from WPILib is built for SystemCore and uses instructions a Pi 4 lacks; `robot_manager` emulates them
  (`LD_AUDIT`). Details in `GradleRIO/VMX.md`.
* PWM, DutyCycle, I2C and IMU quaternion paths are implemented but need hardware validation. DutyCycle uses the DIO channel number directly. The VMX I2C bus is connected to WPILib port 1 by default without an environment override.
* Java robot programs are not supported (C++ only).
* The default Titan watchdog deadlines (100 ms control loop, 150 ms Driver Station) are configurable; the stop time on a robot has not been measured.
