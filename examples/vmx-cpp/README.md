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

* **Wiring**: `build.gradle` maps WPILib channels to VMX channels (`HALSIMSTUDICA_DIO_MAP`, `HALSIMSTUDICA_ANALOG_MAP`). VMX channels include FlexDIO 0-11, encoder pairs (0,1) (2,3) ... A even, B odd,
  and AnalogIn 22-25.
* **Titan**: `TITAN_ID`, `TITAN_MOTOR`, `TITAN_SPEED`. Leave `TITAN_ID` out for no Titan. **Take the wheels off the ground before
  the first run with a speed above 0.**
* **Enabling the robot**: there is no Driver Station on a VMX. `main.cpp` marks the simulated Driver Station attached and enabled
  after a second; your program decides this (that is the "MockDS"). `wpi::studica::TitanEnableGuard` makes the Titan follow it, and `wpi::studica::ControlLoopWatchdog` (fed once per completed cycle) also stops the Titan when this loop or the Driver Station updates stall.
* **Studica classes**: always pass `wpi::studica::SharedVMX()` as the `vmx` argument. Without it each class creates its own
  `VMXPi` and the SPI link breaks (measured).

## Known limits

* Verified on one VMX-pi (Raspberry Pi 4, Ubuntu 26.04, kernel 7.0).
* `libMrcLib.so` from WPILib is built for SystemCore and uses instructions a Pi 4 lacks; `robot_manager` emulates them
  (`LD_AUDIT`). Details in `GradleRIO/VMX.md`.
* PWM, DutyCycle and I2C are not connected to WPILib's classes yet; use the Studica classes directly for those.
* Java robot programs are not supported (C++ only).
* The Titan watchdog deadlines in `main.cpp` (100 ms control loop, 150 ms Driver Station) are examples; the stop time on a robot has not been measured.
