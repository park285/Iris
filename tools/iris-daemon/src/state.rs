use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    Starting,
    Healthy,
    Degraded,
    Recovering,
    RollbackNeeded,
}

impl fmt::Display for State {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            State::Starting => write!(f, "Starting"),
            State::Healthy => write!(f, "Healthy"),
            State::Degraded => write!(f, "Degraded"),
            State::Recovering => write!(f, "Recovering"),
            State::RollbackNeeded => write!(f, "RollbackNeeded"),
        }
    }
}

#[derive(Debug, Clone)]
pub struct Transition {
    pub from: State,
    pub to: State,
    pub reason: String,
}

pub struct StateMachine {
    pub state: State,
    pub fail_count: u32,
    pub recovery_count: u32,
    health_fail_threshold: u32,
    max_consecutive_failures: u32,
    rollback_attempted: bool,
}

impl StateMachine {
    pub fn new(health_fail_threshold: u32, max_consecutive_failures: u32) -> Self {
        Self {
            state: State::Starting,
            fail_count: 0,
            recovery_count: 0,
            health_fail_threshold,
            max_consecutive_failures,
            rollback_attempted: false,
        }
    }

    pub fn on_health_ok(&mut self) -> Option<Transition> {
        let prev = self.state;
        self.fail_count = 0;
        self.recovery_count = 0;
        self.rollback_attempted = false;
        if prev != State::Healthy {
            self.state = State::Healthy;
            Some(Transition { from: prev, to: State::Healthy, reason: "health check 성공".to_string() })
        } else {
            None
        }
    }

    pub fn on_health_fail(&mut self) -> Option<Transition> {
        self.fail_count += 1;
        let prev = self.state;
        match prev {
            State::Starting | State::Healthy => {
                if self.fail_count >= self.health_fail_threshold {
                    self.state = State::Degraded;
                    Some(Transition {
                        from: prev, to: State::Degraded,
                        reason: format!("health check {}회 연속 실패 (임계값: {})", self.fail_count, self.health_fail_threshold),
                    })
                } else { None }
            }
            State::Degraded => {
                self.state = State::Recovering;
                self.recovery_count += 1;
                Some(Transition { from: State::Degraded, to: State::Recovering, reason: "recovery 시작".to_string() })
            }
            State::Recovering => {
                self.recovery_count += 1;
                if self.recovery_count > self.max_consecutive_failures && !self.rollback_attempted {
                    self.state = State::RollbackNeeded;
                    Some(Transition {
                        from: State::Recovering, to: State::RollbackNeeded,
                        reason: format!("recovery {}회 반복 실패, 롤백 필요 (임계값: {})", self.recovery_count, self.max_consecutive_failures),
                    })
                } else { None }
            }
            State::RollbackNeeded => None,
        }
    }

    pub fn on_rollback_done(&mut self) -> Transition {
        let prev = self.state;
        self.rollback_attempted = true;
        self.recovery_count = 0;
        self.state = State::Recovering;
        Transition { from: prev, to: State::Recovering, reason: "롤백 완료, health 재확인 시작".to_string() }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn starts_in_starting_state() {
        let sm = StateMachine::new(2, 5);
        assert_eq!(sm.state, State::Starting);
        assert_eq!(sm.fail_count, 0);
    }

    #[test]
    fn transitions_to_healthy_on_first_success() {
        let mut sm = StateMachine::new(2, 5);
        let t = sm.on_health_ok().expect("should transition");
        assert_eq!(t.from, State::Starting);
        assert_eq!(t.to, State::Healthy);
    }

    #[test]
    fn no_transition_when_already_healthy() {
        let mut sm = StateMachine::new(2, 5);
        sm.on_health_ok();
        assert!(sm.on_health_ok().is_none());
    }

    #[test]
    fn single_fail_does_not_degrade() {
        let mut sm = StateMachine::new(2, 5);
        sm.on_health_ok();
        assert!(sm.on_health_fail().is_none());
        assert_eq!(sm.state, State::Healthy);
    }

    #[test]
    fn degrades_at_threshold() {
        let mut sm = StateMachine::new(2, 5);
        sm.on_health_ok();
        sm.on_health_fail();
        let t = sm.on_health_fail().expect("should transition");
        assert_eq!(t.from, State::Healthy);
        assert_eq!(t.to, State::Degraded);
    }

    #[test]
    fn degraded_to_recovering_on_next_fail() {
        let mut sm = StateMachine::new(2, 5);
        sm.on_health_ok();
        sm.on_health_fail();
        sm.on_health_fail();
        let t = sm.on_health_fail().expect("should transition");
        assert_eq!(t.from, State::Degraded);
        assert_eq!(t.to, State::Recovering);
    }

    #[test]
    fn recovering_to_rollback_after_max_failures() {
        let mut sm = StateMachine::new(1, 2);
        sm.on_health_ok();
        sm.on_health_fail();
        sm.on_health_fail();
        sm.on_health_fail();
        let t = sm.on_health_fail().expect("should transition");
        assert_eq!(t.to, State::RollbackNeeded);
    }

    #[test]
    fn rollback_done_returns_to_recovering() {
        let mut sm = StateMachine::new(1, 1);
        sm.on_health_ok();
        sm.on_health_fail();
        sm.on_health_fail();
        sm.on_health_fail();
        let t = sm.on_rollback_done();
        assert_eq!(t.from, State::RollbackNeeded);
        assert_eq!(t.to, State::Recovering);
        assert_eq!(sm.recovery_count, 0);
    }

    #[test]
    fn no_second_rollback_after_rollback_attempted() {
        let mut sm = StateMachine::new(1, 1);
        sm.on_health_ok();
        sm.on_health_fail();
        sm.on_health_fail();
        sm.on_health_fail();
        sm.on_rollback_done();
        sm.on_health_fail();
        let t = sm.on_health_fail();
        assert!(t.is_none());
        assert_eq!(sm.state, State::Recovering);
    }

    #[test]
    fn health_ok_resets_everything() {
        let mut sm = StateMachine::new(1, 1);
        sm.on_health_ok();
        sm.on_health_fail();
        sm.on_health_fail();
        let t = sm.on_health_ok().expect("should transition back");
        assert_eq!(t.from, State::Recovering);
        assert_eq!(t.to, State::Healthy);
        assert_eq!(sm.fail_count, 0);
        assert_eq!(sm.recovery_count, 0);
    }

    #[test]
    fn state_display() {
        assert_eq!(State::Starting.to_string(), "Starting");
        assert_eq!(State::Healthy.to_string(), "Healthy");
        assert_eq!(State::Degraded.to_string(), "Degraded");
        assert_eq!(State::Recovering.to_string(), "Recovering");
        assert_eq!(State::RollbackNeeded.to_string(), "RollbackNeeded");
    }
}
