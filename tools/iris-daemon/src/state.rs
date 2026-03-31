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
            Self::Starting => write!(f, "Starting"),
            Self::Healthy => write!(f, "Healthy"),
            Self::Degraded => write!(f, "Degraded"),
            Self::Recovering => write!(f, "Recovering"),
            Self::RollbackNeeded => write!(f, "RollbackNeeded"),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FailureKind {
    Liveness,
    Readiness,
}

impl FailureKind {
    const fn label(self) -> &'static str {
        match self {
            Self::Liveness => "liveness",
            Self::Readiness => "readiness",
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
    pub liveness_fail_count: u32,
    pub readiness_fail_count: u32,
    pub recovery_count: u32,
    liveness_fail_threshold: u32,
    readiness_fail_threshold: u32,
    max_consecutive_failures: u32,
    rollback_attempted: bool,
}

impl StateMachine {
    pub const fn new(
        liveness_fail_threshold: u32,
        readiness_fail_threshold: u32,
        max_consecutive_failures: u32,
    ) -> Self {
        Self {
            state: State::Starting,
            liveness_fail_count: 0,
            readiness_fail_count: 0,
            recovery_count: 0,
            liveness_fail_threshold,
            readiness_fail_threshold,
            max_consecutive_failures,
            rollback_attempted: false,
        }
    }

    pub fn on_probe_ok(&mut self) -> Option<Transition> {
        let prev = self.state;
        self.reset_fail_counts();
        self.recovery_count = 0;
        self.rollback_attempted = false;
        if prev == State::Healthy {
            None
        } else {
            self.state = State::Healthy;
            Some(Transition {
                from: prev,
                to: State::Healthy,
                reason: "health check 성공".to_string(),
            })
        }
    }

    pub fn on_probe_fail(&mut self, kind: FailureKind) -> Option<Transition> {
        self.bump_fail_count(kind);
        let prev = self.state;
        match prev {
            State::Starting | State::Healthy => {
                let fail_count = self.fail_count(kind);
                let threshold = self.fail_threshold(kind);
                if fail_count >= threshold {
                    self.state = State::Degraded;
                    Some(Transition {
                        from: prev,
                        to: State::Degraded,
                        reason: format!(
                            "{} check {}회 연속 실패 (임계값: {})",
                            kind.label(),
                            fail_count,
                            threshold
                        ),
                    })
                } else {
                    None
                }
            }
            State::Degraded => {
                self.state = State::Recovering;
                self.recovery_count += 1;
                Some(Transition {
                    from: State::Degraded,
                    to: State::Recovering,
                    reason: format!("{} 장애 recovery 시작", kind.label()),
                })
            }
            State::Recovering => {
                self.recovery_count += 1;
                if self.recovery_count > self.max_consecutive_failures && !self.rollback_attempted {
                    self.state = State::RollbackNeeded;
                    Some(Transition {
                        from: State::Recovering,
                        to: State::RollbackNeeded,
                        reason: format!(
                            "{} 장애 recovery {}회 반복 실패, 롤백 필요 (임계값: {})",
                            kind.label(),
                            self.recovery_count,
                            self.max_consecutive_failures
                        ),
                    })
                } else {
                    None
                }
            }
            State::RollbackNeeded => None,
        }
    }

    const fn reset_fail_counts(&mut self) {
        self.liveness_fail_count = 0;
        self.readiness_fail_count = 0;
    }

    const fn bump_fail_count(&mut self, kind: FailureKind) {
        match kind {
            FailureKind::Liveness => {
                self.liveness_fail_count += 1;
                self.readiness_fail_count = 0;
            }
            FailureKind::Readiness => {
                self.readiness_fail_count += 1;
                self.liveness_fail_count = 0;
            }
        }
    }

    const fn fail_count(&self, kind: FailureKind) -> u32 {
        match kind {
            FailureKind::Liveness => self.liveness_fail_count,
            FailureKind::Readiness => self.readiness_fail_count,
        }
    }

    const fn fail_threshold(&self, kind: FailureKind) -> u32 {
        match kind {
            FailureKind::Liveness => self.liveness_fail_threshold,
            FailureKind::Readiness => self.readiness_fail_threshold,
        }
    }

    pub fn on_rollback_done(&mut self) -> Transition {
        let prev = self.state;
        self.rollback_attempted = true;
        self.reset_fail_counts();
        self.recovery_count = 0;
        self.state = State::Recovering;
        Transition {
            from: prev,
            to: State::Recovering,
            reason: "롤백 완료, health 재확인 시작".to_string(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn starts_in_starting_state() {
        let sm = StateMachine::new(2, 4, 5);
        assert_eq!(sm.state, State::Starting);
        assert_eq!(sm.liveness_fail_count, 0);
        assert_eq!(sm.readiness_fail_count, 0);
    }

    #[test]
    fn transitions_to_healthy_on_first_success() {
        let mut sm = StateMachine::new(2, 4, 5);
        let t = sm.on_probe_ok().expect("should transition");
        assert_eq!(t.from, State::Starting);
        assert_eq!(t.to, State::Healthy);
    }

    #[test]
    fn no_transition_when_already_healthy() {
        let mut sm = StateMachine::new(2, 4, 5);
        sm.on_probe_ok();
        assert!(sm.on_probe_ok().is_none());
    }

    #[test]
    fn single_liveness_fail_does_not_degrade() {
        let mut sm = StateMachine::new(2, 4, 5);
        sm.on_probe_ok();
        assert!(sm.on_probe_fail(FailureKind::Liveness).is_none());
        assert_eq!(sm.state, State::Healthy);
    }

    #[test]
    fn liveness_degrades_at_threshold() {
        let mut sm = StateMachine::new(2, 4, 5);
        sm.on_probe_ok();
        sm.on_probe_fail(FailureKind::Liveness);
        let t = sm
            .on_probe_fail(FailureKind::Liveness)
            .expect("should transition");
        assert_eq!(t.from, State::Healthy);
        assert_eq!(t.to, State::Degraded);
        assert!(t.reason.contains("liveness"));
    }

    #[test]
    fn readiness_degrades_at_own_threshold() {
        let mut sm = StateMachine::new(2, 3, 5);
        sm.on_probe_ok();
        sm.on_probe_fail(FailureKind::Readiness);
        sm.on_probe_fail(FailureKind::Readiness);
        let t = sm
            .on_probe_fail(FailureKind::Readiness)
            .expect("should transition");
        assert_eq!(t.from, State::Healthy);
        assert_eq!(t.to, State::Degraded);
        assert!(t.reason.contains("readiness"));
    }

    #[test]
    fn degraded_to_recovering_on_next_readiness_fail() {
        let mut sm = StateMachine::new(2, 2, 5);
        sm.on_probe_ok();
        sm.on_probe_fail(FailureKind::Readiness);
        sm.on_probe_fail(FailureKind::Readiness);
        let t = sm
            .on_probe_fail(FailureKind::Readiness)
            .expect("should transition");
        assert_eq!(t.from, State::Degraded);
        assert_eq!(t.to, State::Recovering);
        assert!(t.reason.contains("readiness"));
    }

    #[test]
    fn recovering_to_rollback_after_repeated_readiness_failures() {
        let mut sm = StateMachine::new(1, 1, 2);
        sm.on_probe_ok();
        sm.on_probe_fail(FailureKind::Readiness);
        sm.on_probe_fail(FailureKind::Readiness);
        sm.on_probe_fail(FailureKind::Readiness);
        let t = sm
            .on_probe_fail(FailureKind::Readiness)
            .expect("should transition");
        assert_eq!(t.to, State::RollbackNeeded);
        assert!(t.reason.contains("readiness"));
    }

    #[test]
    fn rollback_done_returns_to_recovering() {
        let mut sm = StateMachine::new(1, 1, 1);
        sm.on_probe_ok();
        sm.on_probe_fail(FailureKind::Liveness);
        sm.on_probe_fail(FailureKind::Liveness);
        sm.on_probe_fail(FailureKind::Liveness);
        let t = sm.on_rollback_done();
        assert_eq!(t.from, State::RollbackNeeded);
        assert_eq!(t.to, State::Recovering);
        assert_eq!(sm.recovery_count, 0);
    }

    #[test]
    fn no_second_rollback_after_rollback_attempted() {
        let mut sm = StateMachine::new(1, 1, 1);
        sm.on_probe_ok();
        sm.on_probe_fail(FailureKind::Liveness);
        sm.on_probe_fail(FailureKind::Liveness);
        sm.on_probe_fail(FailureKind::Liveness);
        sm.on_rollback_done();
        sm.on_probe_fail(FailureKind::Liveness);
        let t = sm.on_probe_fail(FailureKind::Liveness);
        assert!(t.is_none());
        assert_eq!(sm.state, State::Recovering);
    }

    #[test]
    fn probe_ok_resets_everything() {
        let mut sm = StateMachine::new(1, 1, 1);
        sm.on_probe_ok();
        sm.on_probe_fail(FailureKind::Readiness);
        sm.on_probe_fail(FailureKind::Readiness);
        let t = sm.on_probe_ok().expect("should transition back");
        assert_eq!(t.from, State::Recovering);
        assert_eq!(t.to, State::Healthy);
        assert_eq!(sm.liveness_fail_count, 0);
        assert_eq!(sm.readiness_fail_count, 0);
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
