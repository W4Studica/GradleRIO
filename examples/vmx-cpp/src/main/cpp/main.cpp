// A hardware check for a VMX-pi, written with WPILib's standard classes: AnalogInput, Encoder and OnboardIMU read the sensors
// through halsim_studica, and (if kTitanId is set) a Titan motor controller is enabled and driven by the Driver Station state.
//
// There is no real Driver Station on a VMX, so this program plays the part of the "MockDS" itself: it marks the simulated
// Driver Station attached and enabled one second after start. Your own program decides when the robot is enabled; the
// Titan follows that through wpi::studica::Titan, and stops when the robot is disabled or E-stopped.
//
// wpi::studica::Titan owns enable keepalive and freshness checks. FeedControlLoop() follows a completed cycle;
// FeedDriverStation() follows a new valid Driver Station state. This example generates that state locally.
//
#include <cstdio>
#include <chrono>
#include <optional>
#include <memory>

#include "wpi/framework/TimedRobot.hpp"
#include "wpi/studica/Titan.hpp"
#include "wpi/hardware/discrete/AnalogInput.hpp"
#include "wpi/hardware/imu/OnboardIMU.hpp"
#include "wpi/hardware/rotation/Encoder.hpp"
#include "wpi/simulation/DriverStationSim.hpp"

namespace {
// Change these constants for the connected controller; no process environment is needed.
constexpr std::optional<uint8_t> kTitanId = std::nullopt;
constexpr uint8_t kTitanMotor = 0;
constexpr double kTitanSpeed = 0.0;
}  // namespace

class Robot : public wpi::TimedRobot {
 public:
  Robot() {
    wpi::sim::DriverStationSim::SetDsAttached(true);
    wpi::sim::DriverStationSim::SetRobotMode(wpi::hal::RobotMode::TELEOPERATED);
    wpi::sim::DriverStationSim::SetEnabled(false);
    wpi::sim::DriverStationSim::NotifyNewData();

    if (kTitanId) {
      const auto id = *kTitanId;
      m_motor = kTitanMotor;
      m_speed = kTitanSpeed;
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
