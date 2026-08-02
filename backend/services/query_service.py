from langsmith import traceable

from backend.config import get_logger

logger = get_logger(__name__)


def _summarize_stream_events(events: list[dict] | None) -> dict:
    if not events:
        return {"events_emitted": 0}

    final_event = next(
        (
            event
            for event in reversed(events)
            if isinstance(event, dict) and event.get("type") == "final"
        ),
        None,
    )
    final_result = final_event.get("result", {}) if isinstance(final_event, dict) else {}
    return {
        "events_emitted": len(events),
        "final_route": final_result.get("route", []),
        "input_blocked": final_result.get("input_blocked", False),
    }

class QueryService:
    def __init__(self, graph):
        self.graph = graph

    def _to_query_response(self, result: dict) -> dict:
        return {
            "answer": result.get("final_answer") or "I couldn't complete that request.",
            "query": result.get("user_query"),
            "sanitized_query": result.get("sanitized_query"),
            "input_blocked": result.get("input_blocked", False),
            "route": [task.agent for task in result.get("tasks", [])],
            "route_confidence": result.get("route_confidence", 0.0),
            "route_explanation": result.get("route_explanation"),
            "urgency_level": result.get("urgency_level"),
            "should_escalate": result.get("should_escalate", False),
            "case_draft": result.get("case_draft"),
            "task_draft": result.get("task_draft"),
            "contractor_suggestions": result.get("contractor_suggestions", []),
            "retrieval_context": result.get("retrieval_context", [])
        }

    def _build_initial_state(
        self,
        *,
        user_query: str,
        household_id: int,
        session_id: str,
        entry_id: str | None = None,
        asset_id: int | None = None,
        household_zip_code: str | None,
        messages: list | None = None,
    ) -> dict:
        return {
            "user_query": user_query,
            "household_id": household_id,
            "session_id": session_id,
            "entry_id": entry_id,
            "asset_id": asset_id,
            "household_zip_code": household_zip_code,
            "messages": messages or [],
        }

    @staticmethod
    def _route_summary(tasks: list) -> str:
        if not tasks:
            return "No specialist agents selected."
        names = [task.agent for task in tasks]
        return ", ".join(names)

    @staticmethod
    def _latest_agent_response(items: list[dict] | None) -> dict | None:
        if not items:
            return None
        return items[-1]

    @staticmethod
    def _extract_update_payload(event) -> tuple[str | None, dict | None]:
        if (
            isinstance(event, tuple)
            and len(event) == 2
            and event[0] == "updates"
            and isinstance(event[1], dict)
        ):
            payload = event[1]
            if not payload:
                return None, None
            node_name, update = next(iter(payload.items()))
            return node_name, update
        return None, None

    @staticmethod
    def _extract_value_payload(event) -> dict | None:
        if (
            isinstance(event, tuple)
            and len(event) == 2
            and event[0] == "values"
            and isinstance(event[1], dict)
        ):
            return event[1]
        return None

    @traceable(
        name="homebuddy.query.stream",
        run_type="chain",
        reduce_fn=_summarize_stream_events,
    )
    def stream_query(
        self,
        *,
        user_query: str,
        household_id: int,
        session_id: str,
        entry_id: str | None = None,
        asset_id: int | None = None,
        household_zip_code: str | None,
        messages: list | None = None,
    ):
        initial_state = self._build_initial_state(
            user_query=user_query,
            household_id=household_id,
            session_id=session_id,
            entry_id=entry_id,
            asset_id=asset_id,
            household_zip_code=household_zip_code,
            messages=messages,
        )

        routed = False
        troubleshooting_done = False
        coverage_done = False
        safety_done = False
        operations_done = False
        synthesized = False
        final_payload = None
        sanitized_query = None

        for event in self.graph.stream(initial_state, stream_mode=["updates", "values"]):
            node_name, update = self._extract_update_payload(event)
            if update:
                if node_name == "troubleshooting_agent" and not troubleshooting_done:
                    troubleshooting_done = True
                    latest = self._latest_agent_response(update.get("troubleshooting_response"))
                    yield {"type": "status", "message": "🛠️ Troubleshooting agent checked the docs and finished its pass."}
                    if latest:
                        yield {
                            "type": "partial_answer",
                            "agent": latest.get("agent", "troubleshooting_agent"),
                            "content": latest.get("response", ""),
                        }

                if node_name == "coverage_and_warranty_agent" and not coverage_done:
                    coverage_done = True
                    latest = self._latest_agent_response(update.get("coverage_response"))
                    yield {"type": "status", "message": "📄 Coverage and warranty agent finished reviewing the paperwork."}
                    if latest:
                        yield {
                            "type": "partial_answer",
                            "agent": latest.get("agent", "coverage_and_warranty_agent"),
                            "content": latest.get("response", ""),
                        }

                if node_name == "safety_risk_agent" and not safety_done:
                    safety_done = True
                    latest = self._latest_agent_response(update.get("safety_response"))
                    yield {"type": "status", "message": "🚨 Safety agent finished its assessment."}
                    if latest:
                        yield {
                            "type": "partial_answer",
                            "agent": latest.get("agent", "safety_risk_agent"),
                            "content": latest.get("response", ""),
                        }

                if node_name == "home_operations_agent" and not operations_done:
                    operations_done = True
                    latest = self._latest_agent_response(update.get("operations_response"))
                    yield {"type": "status", "message": "🗂️ Home operations agent wrapped up the workflow steps."}
                    if latest:
                        yield {
                            "type": "partial_answer",
                            "agent": latest.get("agent", "home_operations_agent"),
                            "content": latest.get("response", ""),
                        }

            state = self._extract_value_payload(event)
            if state is None:
                continue
            
            if not sanitized_query and state.get("sanitized_query") is not None:
                sanitized_query = state.get("sanitized_query")
                yield {
                    "type": "user_accepted",
                    "sanitized_query": sanitized_query,
                    "input_blocked": state.get("input_blocked", False),
                }

            tasks = state.get("tasks", [])
            if tasks and not routed:
                routed = True
                yield {
                    "type": "status",
                    "message": f"🧭 Routing complete: {self._route_summary(tasks)}",
                }

            if state.get("final_answer") and not synthesized:
                synthesized = True
                yield {"type": "status", "message": "✨ Final response is ready."}
                final_payload = self._to_query_response(state)

        if final_payload is None:
            final_payload = {
                "answer": "I couldn't complete that request.",
                "query": user_query,
                "sanitized_query": None,
                "input_blocked": False,
                "route": [],
                "route_confidence": 0.0,
                "route_explanation": None,
                "urgency_level": None,
                "should_escalate": False,
                "case_draft": None,
                "task_draft": None,
                "contractor_suggestions": [],
                "retrieval_context": [],
            }

        yield {"type": "final", "result": final_payload}
    
    @traceable(name="homebuddy.query.sync", run_type="chain")
    def run_query(
        self,
        *,
        user_query: str,
        household_id: int,
        session_id: str,
        entry_id: str | None = None,
        asset_id: int | None = None,
        household_zip_code: str | None,
        messages: list | None = None
    ):
        initial_state = self._build_initial_state(
            user_query=user_query,
            household_id=household_id,
            session_id=session_id,
            entry_id=entry_id,
            asset_id=asset_id,
            household_zip_code=household_zip_code,
            messages=messages,
        )

        graph_result = self.graph.invoke(initial_state)
        return self._to_query_response(graph_result)
    
   
