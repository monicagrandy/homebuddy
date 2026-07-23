# HomeBuddy builder & compiler

from langgraph.graph import END, START, StateGraph

from backend.agents.coverage_warranty import coverage_and_warranty_agent
from backend.agents.operations import home_operations_agent
from backend.agents.orchestrator import orchestrator_agent
from backend.agents.safety import safety_risk_agent
from backend.agents.synthesizer import synthesizer_agent
from backend.agents.troubleshooting import troubleshooting_agent
from backend.config import get_logger
from backend.guardrails.nodes.input_node import input_guardrail_node
from backend.guardrails.nodes.output_node import final_output_guardrail_node
from backend.workflow.state import HomeBuddyState

logger = get_logger(__name__)

def build_graph() -> StateGraph:
    """Create, wire and compile the SnackStack multi-agent graph."""
    builder = StateGraph(HomeBuddyState)

    # Add nodes — each represents one step in the pipeline
    builder.add_node("input_guardrail", input_guardrail_node)
    builder.add_node("orchestrator", orchestrator_agent)  # classifies the query and dispatches tasks
    builder.add_node("safety_risk_agent", safety_risk_agent)
    builder.add_node("troubleshooting_agent", troubleshooting_agent)
    builder.add_node("coverage_and_warranty_agent", coverage_and_warranty_agent)
    builder.add_node("home_operations_agent", home_operations_agent)
    builder.add_node("synthesizer", synthesizer_agent)
    builder.add_node("final_output_guardrail_node", final_output_guardrail_node)
   
    builder.add_edge(START, "input_guardrail")
    builder.add_edge("final_output_guardrail_node", END)

    graph = builder.compile()
    logger.info("Graph compiled")
    return graph
