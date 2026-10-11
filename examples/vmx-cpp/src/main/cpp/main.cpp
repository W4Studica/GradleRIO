// A hardware check for a VMX-pi, written with WPILib's standard classes: AnalogInput, Encoder and OnboardIMU read the sensors
// through halsim_vmx, and (if TITAN_ID is set) a Titan motor controller is enabled and driven by the Driver Station state.
//
// There is no real Driver Station on a VMX, so this program plays the part of the "MockDS" itself: it marks the simulated
// Driver Station attached and enabled one second after start. Your own program decides when the robot is enabled; the
// Titan follows that through wpilibvmx::TitanEnableGuard, and stops when the robot is disabled or E-stopped.
//
// Environment (build.gradle puts it into robotCommand): TITAN_ID (omit: no Titan), TITAN_MOTOR (0-3), TITAN_SPEED (0..1).
#include <cstdio>
#include <cstdlib>
#include <memory>

#include "titan.hpp"
#include "wpi/framework/TimedRobot.hpp"
#include "wpi/halsim/vmx/SharedVMX.hpp"
#include "wpi/halsim/vmx/StudicaTitan.hpp"
#include "wpi/halsim/vmx/TitanEnableGuard.hpp"
#include "wpi/hardware/discrete/AnalogInput.hpp"
#include "wpi/hardware/imu/OnboardIMU.hpp"
#include "wpi/hardware/rotation/Encoder.hpp"
#include "wpi/simulation/DriverStationSim.hpp"

namespace {
const char* Env(const char* name) {
  const char* v = std::getenv(name);
  return (v && *v) ? v : nullptr;
}
double EnvDouble(const char* name, double fallback) {
  const char* v = Env(name);
  return v ? std::atof(v) : fallback;
}
}  // namespace

class Robot : public wpi::TimedRobot {
 public:
  Robot() {
    wpi::sim::DriverStationSim::SetDsAttached(true);
    wpi::sim::DriverStationSim::SetEnabled(false);
    wpi::sim::DriverStationSim::NotifyNewData();

    if (Env("TITAN_ID")) {
      const auto id = static_cast<uint8_t>(EnvDouble("TITAN_ID", 0));
      m_motor = static_cast<uint8_t>(EnvDouble("TITAN_MOTOR", 0));
      m_speed = EnvDouble("TITAN_SPEED", 0);
      // Always give the Studica classes the shared VMXPi: a second VMXPi in the process breaks the SPI link.
      m_titan = std::make_unique<studica_driver::Titan>(id, 15600, 0.0006830601f, wpilibvmx::SharedVMX());
      m_switch = std::make_unique<wpilibvmx::StudicaTitan>(*m_titan, id);
      m_guard = std::make_unique<wpilibvmx::TitanEnableGuard<wpilibvmx::StudicaTitan>>(*m_switch);
      m_guard->Start();
      std::printf("Titan %d on motor %d, speed %.2f\n", id, m_motor, m_speed);
    }
  }

  void RobotPeriodic() override {
    // The part a team replaces: enable the robot when it should drive.
    if (m_ticks == 50) {
      wpi::sim::DriverStationSim::SetEnabled(true);
      wpi::sim::DriverStationSim::NotifyNewData();
    }
    if (m_titan) {
      // Keep commanding while enabled (the Titan stops by itself after ~200 ms without a command).
      m_titan->SetSpeed(m_motor, m_speed);
    }
    if (++m_ticks % 25 == 0) {  // twice a second
      constexpr double kDeg = 180.0 / 3.14159265358979;
      std::printf("analog0 %.3f V  encoder %d  imu yaw %.1f deg  accel z %.2f m/s^2%s\n", m_analog.GetVoltage(),
                  m_encoder.Get(), m_imu.GetYaw().value() * kDeg, m_imu.GetAccelZ().value(),
                  m_titan ? "" : "  (no Titan)");
      std::fflush(stdout);
    }
  }

 private:
  int m_ticks = 0;
  uint8_t m_motor = 0;
  double m_speed = 0;
  wpi::AnalogInput m_analog{0};
  wpi::Encoder m_encoder{0, 1};
  wpi::OnboardIMU m_imu{wpi::OnboardIMU::FLAT};
  // Declared in this order so the guard is destroyed (and disables the Titan) before the Titan.
  std::unique_ptr<studica_driver::Titan> m_titan;
  std::unique_ptr<wpilibvmx::StudicaTitan> m_switch;
  std::unique_ptr<wpilibvmx::TitanEnableGuard<wpilibvmx::StudicaTitan>> m_guard;
};

int main() {
  return wpi::StartRobot<Robot>();
}
