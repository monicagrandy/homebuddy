import re

from pydantic import BaseModel, Field

GAS_PATTERNS = [
    r"\bsmell(?:s|ing)? gas\b",
    r"\bgas leak\b",
    r"\bcarbon monoxide\b",
    r"\bco alarm\b",
]

ELECTRICAL_PATTERNS = [
    r"\bsparks?\b",
    r"\bsparking\b",
    r"\belectrical shock\b",
    r"\bshock(?:ed|ing)?\b",
    r"\blive wire\b",
    r"\bexposed wire\b",
    r"\bshort(?:ing|ed)?\b",
    r"\bwater leaking near (?:an|the )?(?:outlet|panel|breaker|electrical)\b",
]

FIRE_HEAT_PATTERNS = [
    r"\bsmoke\b",
    r"\bburning smell\b",
    r"\bburnt smell\b",
    r"\bfire\b",
    r"\bflames?\b",
    r"\boverheat(?:ing)?\b",
    r"\btoo hot to touch\b",
]

WATER_SEWAGE_PATTERNS = [
    r"\bflood(?:ing|ed)?\b",
    r"\bmajor leak\b",
    r"\bwater pouring\b",
    r"\bsewage\b",
    r"\bbackflow\b",
]

class SafetyAssessment(BaseModel):
    matched: bool
    urgency_level: str | None = None
    should_escalate: bool = False
    stop_using: bool = False
    immediate_actions: list[str] = Field(default_factory=list)
    contractor: list[str] = Field(default_factory=list)
    rationale: str | None = None

class HazardAssessmentService:
    def __init__(self) -> None:
        self.gas_patterns = [re.compile(p, re.IGNORECASE) for p in GAS_PATTERNS]
        self.electrical_patterns = [re.compile(p, re.IGNORECASE) for p in ELECTRICAL_PATTERNS]
        self.fire_heat_patterns = [re.compile(p, re.IGNORECASE) for p in FIRE_HEAT_PATTERNS]
        self.water_sewage_patterns = [re.compile(p, re.IGNORECASE) for p in WATER_SEWAGE_PATTERNS]
    
    @staticmethod
    def _matches(patterns: list[re.Pattern], text: str) -> bool:
        return any(pattern.search(text) for pattern in patterns)

    def assess(self, text: str) -> SafetyAssessment:
        gas_issue = self._matches(self.gas_patterns, text)
        electrical_issue = self._matches(self.electrical_patterns, text)
        fire_heat_issue = self._matches(self.fire_heat_patterns, text)
        water_sewage_issue = self._matches(self.water_sewage_patterns, text)

        if gas_issue:
            return SafetyAssessment(
                matched = True,
                urgency_level = "critical",
                should_escalate = True,
                stop_using = True,
                contractor = ["electrician"],
                immediate_actions = ["leave area", "avoid switches and open flames", "call 911 if active fire"],
                rationale="Detected gas hazard language.",
            )
        if electrical_issue:
            return SafetyAssessment(
                matched = True,
                urgency_level = "critical",
                should_escalate = True,
                stop_using = True,
                contractor = ["electrician"],
                immediate_actions = ["stop using affected device/outlet", "shut off power if safe", "call 911 if active fire"],
                rationale="Detected electrical hazard language.",
            )
        if fire_heat_issue:
            return SafetyAssessment(
                matched = True,
                urgency_level = "critical",
                should_escalate = True,
                stop_using = True,
                contractor = ["electrician", "appliance technician"],
                immediate_actions = ["turn off appliance if safe", "disconnect from power if safe", "call 911 if active fire"],
                rationale="Detected fire/heat hazard language.",
            )
        if water_sewage_issue:
            return SafetyAssessment(
                matched = True,
                urgency_level = "high",
                should_escalate = True,
                stop_using = True,
                contractor = ["plumber"],
                immediate_actions = ["shut off water if possible", "avoid contaminated area", "keep away from electrical hazards"],
                rationale="Detected water/sewage hazard language.",
            )
        
        return SafetyAssessment(matched=False)
    
