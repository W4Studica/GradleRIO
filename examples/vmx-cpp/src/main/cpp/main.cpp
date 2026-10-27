// A hardware check for a VMX-pi, written with WPILib's standard classes: AnalogInput, Encoder and OnboardIMU read the sensors
// through halsim_studica, and (if TITAN_ID is set) a Titan motor controller is enabled and driven by the Driver Station state.
//
// There is no real Driver Station on a VMX, so this program plays the part of the "MockDS" itself: it marks the simulated
// Driver Station attached and enabled one second after start. Your own program decides when the robot is enabled; the
// Titan follows that through wpi::studica::Titan, and stops when the robot is disabled or E-stopped.
//
// wpi::studica::Titan owns enable keepalive and freshness checks. FeedControlLoop() follows a completed cycle;
// FeedDriverStation() follows a new valid Driver Station state. This example generates that state locally.
//
// Environment (build.gradle puts it into robotCommand): TITAN_ID (omit: no Titan), TITAN_MOTOR (0-3), TITAN_SPEED (0..1).
#include <cstdio>
#include <chrono>
#include <cstdlib>
#include <memory>

#include "wpi/framework/TimedRobot.hpp"
#include "wpi/studica/Titan.hpp"
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
      m_titan = std::make_unique<wpi::studica::Titan>(id, 15600, 0.0006830601f);
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
      // This program is its own MockDS, so a "new Driver Station packet" is the state it publishes every cycle. A real
      // MockDS feeds this when it receives a valid packet.
      wpi::sim::DriverStationSim::NotifyNewData();
      m_titan->FeedDriverStation();
    }
    if (++m_ticks % 25 == 0) {  // twice a second
      constexpr double kDeg = 180.0 / 3.14159265358979;
      std::printf("analog0 %.3f V  encoder %d  imu yaw %.1f deg  accel z %.2f m/s^2%s\n", m_analog.GetVoltage(),
                  m_encoder.Get(), m_imu.GetYaw().value() * kDeg, m_imu.GetAccelZ().value(),
                  m_titan ? "" : "  (no Titan)");
      std::fflush(stdout);
    }
    // Last: only a fully completed cycle feeds the control heartbeat.
    if (m_titan) { m_titan->FeedControlLoop(); }
  }

 private:
  int m_ticks = 0;
  uint8_t m_motor = 0;
  double m_speed = 0;
  wpi::AnalogInput m_analog{0};
  wpi::Encoder m_encoder{0, 1};
  wpi::OnboardIMU m_imu{wpi::OnboardIMU::FLAT};
  std::unique_ptr<wpi::studica::Titan> m_titan;
};

int main() {
  return wpi::StartRobot<Robot>();
}
