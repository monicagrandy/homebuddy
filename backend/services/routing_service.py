import re

from pydantic import BaseModel

from backend.services.hazard_assessment_service import SafetyAssessment
from backend.workflow.state import AgentTask

HAZARD_PATTERNS = [
    r"\bsmell(?:s|ing)? gas\b",
    r"\bgas leak\b",
    r"\bcarbon monoxide\b",
    r"\bco alarm\b",
    r"\bsmoke\b",
    r"\bburning smell\b",
    r"\bburnt smell\b",
    r"\bsparks?\b",
    r"\bsparking\b",
    r"\bfire\b",
    r"\bflames?\b",
    r"\boverheat(?:ing)?\b",
    r"\btoo hot to touch\b",
    r"\bshock(?:ed|ing)?\b",
    r"\belectrical shock\b",
    r"\blive wire\b",
    r"\bexposed wire\b",
    r"\bshort(?:ing|ed)?\b",
    r"\btripped breaker\b",
    r"\bwater leaking near (?:an|the )?(?:outlet|panel|breaker|electrical)\b",
    r"\bflood(?:ing|ed)?\b",
    r"\bmajor leak\b",
    r"\bwater pouring\b",
    r"\bsewage\b",
    r"\bbackflow\b",
    r"\bnot safe\b",
    r"\bdangerous\b",
    r"\bemergency\b",
]

HAZARD_REGEXES = [re.compile(p, re.IGNORECASE) for p in HAZARD_PATTERNS]

TROUBLESHOOTING_PATTERNS = [
    r"\bnot working\b",
    r"\bbroken\b",
    r"\bwon'?t (?:turn on|start|work|cool|heat|run)\b",
    r"\bwhy is my\b",
    r"\bhow do i (?:turn on|use|reset|fix|troubleshoot)\b",
    r"\bhow do i set\b",
    r"\berror code\b",
    r"\bissue with\b",
    r"\bproblem with\b",
    r"\btire pressure light\b",
]

TROUBLESHOOTING_ENTITY_PATTERNS = [
    r"\bac\b",
    r"\bair conditioning\b",
    r"\bthermostat\b",
    r"\bdishwasher\b",
    r"\bwater heater\b",
    r"\balarm\b",
    r"\bsecurity system\b",
    r"\bbidet\b",
    r"\bgarage door\b",
    r"\bwasher\b",
    r"\bvehicle\b",
    r"\bcar\b",
    r"\bboat\b",
]

TROUBLESHOOTING_INFORMATION_PATTERNS = [
    r"\bwhat does\b",
    r"\bwhen does\b",
    r"\bwhat kind of\b",
    r"\bhow can i\b",
    r"\bhow do i\b",
]

HOME_OPS_PATTERNS = [
    r"\bremind me\b",
    r"\breminder\b",
    r"\bfollow up\b",
    r"\bfind (?:an?|me)?\b",
    r"\bcontractor\b",
    r"\btechnician\b",
    r"\bplumber\b",
    r"\belectrician\b",
    r"\bhvac (?:technician|contractor|repair|service|company|specialist|pro)\b",
    r"\bmechanic\b",
    r"\blandscap(?:e|er|ing)\b",
    r"\barborist\b",
    r"\bpest control\b",
    r"\bcase\b",
    r"\btask\b",
]

COVERAGE_PATTERNS = [
    r"\bwarranty\b",
    r"\bcovered\b",
    r"\bcoverage\b",
    r"\binsurance\b",
    r"\bclaim\b",
    r"\breceipt\b",
    r"\bcontract\b",
    r"\bservice call fee\b",
    r"\boptional coverage\b",
    r"\bproof of purchase\b",
    r"\beffective date\b",
    r"\bexclusions?\b",
]

TROUBLESHOOTING_REGEXES = [re.compile(p, re.IGNORECASE) for p in TROUBLESHOOTING_PATTERNS]
TROUBLESHOOTING_ENTITY_REGEXES = [re.compile(p, re.IGNORECASE) for p in TROUBLESHOOTING_ENTITY_PATTERNS]
TROUBLESHOOTING_INFORMATION_REGEXES = [re.compile(p, re.IGNORECASE) for p in TROUBLESHOOTING_INFORMATION_PATTERNS]
HOME_OPS_REGEXES = [re.compile(p, re.IGNORECASE) for p in HOME_OPS_PATTERNS]
COVERAGE_REGEXES = [re.compile(p, re.IGNORECASE) for p in COVERAGE_PATTERNS]

class RouteDecision(BaseModel):
    route: list[AgentTask]
    route_confidence: float
    route_explanation: str
    urgency_level: str
    should_parallelize: bool
    should_escalate: bool

class RoutingService:
    def __init__(self):
        pass

    def route(self, question: str, assessment: SafetyAssessment) -> RouteDecision | None:

        if assessment.matched:
            return RouteDecision(
                route=[
                    AgentTask(
                        agent="safety_risk_agent",
                        task_description="Provide immediate safety guidance based on the hazard assessment."
                    )
                ],
                route_confidence=1.0,
                route_explanation="Detected hazard language requiring safety-first handling.",
                urgency_level=assessment.urgency_level or "high",
                should_parallelize=False,
                should_escalate=True
            )


        return None
