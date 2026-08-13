from __future__ import annotations

import json
import os
from pathlib import Path
from statistics import mean
from typing import Any

os.environ.setdefault("CONTRACTOR_SEARCH_PROVIDER", "mock")

from langchain_core.prompts import ChatPromptTemplate
from langsmith import Client, traceable
from langsmith.evaluation import evaluate

from backend.config import settings
from backend.dependencies import get_state_graph
from backend.runtime import get_judge_llm
from backend.services.query_service import QueryService

CASE_ROOT = Path(__file__).resolve().parent / "cases"
DEFAULT_HOUSEHOLD_ID = int(os.getenv("EVAL_HOUSEHOLD_ID", "4"))
DEFAULT_USER_ID = int(os.getenv("EVAL_USER_ID", "999"))
DEFAULT_SESSION_ID = os.getenv("EVAL_SESSION_ID", "eval-suite")
DEFAULT_ZIP_CODE = os.getenv("EVAL_HOUSEHOLD_ZIP", "90032")
ROUTING_DATASET_NAME = "home-buddy-routing"
GROUNDING_DATASET_NAME = "home-buddy-grounding"
CORRECTNESS_DATASET_NAME = "home-buddy-correctness"
judge_llm = get_judge_llm()
homebuddy_graph = get_state_graph()


def _load_cases(name: str) -> list[dict[str, Any]]:
    with (CASE_ROOT / f"{name}.json").open() as fh:
        return json.load(fh)


def _build_query_service() -> QueryService:
    return QueryService(homebuddy_graph)


def _normalize_routes(routes: list[str]) -> list[str]:
    return sorted(dict.fromkeys(routes))


def _contains_all_expected_keywords(answer: str, expected_keywords: list[str]) -> float:
    if not expected_keywords:
        return 1.0
    lowered = answer.lower()
    matches = sum(1 for keyword in expected_keywords if keyword.lower() in lowered)
    return matches / len(expected_keywords)


def _bool_score(actual: bool, expected: bool) -> float:
    return 1.0 if actual is expected else 0.0


def _coerce_context_to_text(context: Any) -> str:
    """Normalize retrieval context into a single text blob for judge prompts."""
    if not context:
        return "(no context — escalation response)"
    if isinstance(context, str):
        if _is_fallback_retrieval_item(context):
            return "(no context — escalation response)"
        return context[:4000]
    if isinstance(context, list):
        parts: list[str] = []
        for item in context:
            if isinstance(item, str):
                if not _is_fallback_retrieval_item(item):
                    parts.append(item)
            else:
                parts.append(json.dumps(item))
        if not parts:
            return "(no context — escalation response)"
        return "\n\n".join(parts)[:4000]
    return str(context)[:4000]


def _is_fallback_retrieval_item(item: str) -> bool:
    lowered = item.strip().lower()
    return lowered.startswith("no relevant ") or lowered == "(no context — escalation response)"


def _count_grounded_retrieval_items(context: Any) -> int:
    if not context:
        return 0
    if isinstance(context, str):
        return 0 if _is_fallback_retrieval_item(context) else 1
    if isinstance(context, list):
        return sum(
            0 if isinstance(item, str) and _is_fallback_retrieval_item(item) else 1
            for item in context
        )
    return 1


def _case_to_example(case: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    inputs = {
        "question": case["question"],
        "household_id": case.get("household_id", DEFAULT_HOUSEHOLD_ID),
        "session_id": case.get("session_id", DEFAULT_SESSION_ID),
        "household_zip_code": case.get("household_zip_code", DEFAULT_ZIP_CODE),
    }
    outputs = {
        "expected_answer": case.get("expected_answer", ""),
        "expected_routes": case.get("expected_routes", []),
        "reference_facts": case.get("reference_facts", []),
        "min_citations": case.get("min_citations", 1),
        "expected_keywords": case.get("expected_keywords", []),
    }
    return inputs, outputs


def ensure_grounding_dataset() -> str:
    """Create the LangSmith grounding dataset if it does not already exist."""
    client = Client()
    try:
        client.read_dataset(dataset_name=GROUNDING_DATASET_NAME)
        return GROUNDING_DATASET_NAME
    except Exception:
        pass

    dataset = client.create_dataset(
        dataset_name=GROUNDING_DATASET_NAME,
        description="Home Buddy grounding evals based on indexed thermostat and warranty documents.",
    )

    for case in _load_cases("grounding"):
        inputs, outputs = _case_to_example(case)
        client.create_example(
            dataset_id=dataset.id,
            inputs=inputs,
            outputs=outputs,
        )

    return GROUNDING_DATASET_NAME


def ensure_routing_dataset() -> str:
    """Create the LangSmith routing dataset if it does not already exist."""
    client = Client()
    try:
        client.read_dataset(dataset_name=ROUTING_DATASET_NAME)
        return ROUTING_DATASET_NAME
    except Exception:
        pass

    dataset = client.create_dataset(
        dataset_name=ROUTING_DATASET_NAME,
        description="Home Buddy routing evals for single-agent, multi-agent, and fallback routing behavior.",
    )

    for case in _load_cases("routing"):
        inputs, outputs = _case_to_example(case)
        client.create_example(
            dataset_id=dataset.id,
            inputs=inputs,
            outputs=outputs,
        )

    return ROUTING_DATASET_NAME

def ensure_correctness_dataset() -> str:
    """Create the LangSmith correctness dataset if it does not already exist."""
    client = Client()
    try:
        client.read_dataset(dataset_name=CORRECTNESS_DATASET_NAME)
        return CORRECTNESS_DATASET_NAME
    except Exception:
        pass

    dataset = client.create_dataset(
        dataset_name=CORRECTNESS_DATASET_NAME,
        description="Home Buddy correctness evals based on indexed thermostat and warranty documents.",
    )

    for case in _load_cases("correctness"):
        inputs, outputs = _case_to_example(case)
        client.create_example(
            dataset_id=dataset.id,
            inputs=inputs,
            outputs=outputs,
        )

    return CORRECTNESS_DATASET_NAME

# Adapter layer: takes one eval case, runs the real app through 
# QueryService -> graph and normalizes the output into a stable shape for scoring.
# Guarantees every evaluator gets the same input shape and tests the real multi-agent flow.
@traceable(name="home_buddy_eval_run_case")
def run_case(inputs: dict[str, Any]) -> dict[str, Any]:
    """Execute one query against the real graph and normalize the response shape."""
    query_service = _build_query_service()
    result = query_service.run_query(
        user_query=inputs["question"],
        user_id=inputs.get("user_id", DEFAULT_USER_ID),
        household_id=inputs.get("household_id", DEFAULT_HOUSEHOLD_ID),
        session_id=inputs.get("session_id", DEFAULT_SESSION_ID),
        entry_id=inputs.get("entry_id"),
        asset_id=inputs.get("asset_id"),
        household_zip_code=inputs.get("household_zip_code", DEFAULT_ZIP_CODE),
        messages=[],
    )
    return {
        "query": inputs["question"],
        "answer": result.get("answer", ""),
        "route": _normalize_routes(result.get("route", [])),
        "route_confidence": result.get("route_confidence", 0.0),
        "should_escalate": result.get("should_escalate", False),
        "urgency_level": result.get("urgency_level", None),
        "case_draft": result.get("case_draft"),
        "task_draft": result.get("task_draft"),
        "contractor_suggestions": result.get("contractor_suggestions", []),
        "retrieval_context": result.get("retrieval_context", [])
    }

# Faithfulness eval checks that the answer is grounded in the retrieved docs via an LLM Judge.
FAITHFULNESS_PROMPT = ChatPromptTemplate.from_messages([
    ("system",
     "You are a faithfulness evaluator. Assess whether the answer is faithful "
     "to its provided context and score based on the following rubric:\n\n"
     "Score 1.0 = Every claim in the answer is supported by the context\n"
     "Score 0.5 = Parts of the answer are supported while others are not.\n"
     "Score 0.0 = All claims in the answer are either contradictory or absent from its context.\n\n"
     "If the context is empty (escalation response), score 1.0.\n\n"
     'Respond ONLY with JSON: {{"score": <float>, "reason": "<one sentence>"}}'),
    ("human",
    "Context:\n{context}\n\nQuestion: {question}\n\nAnswer to evaluate:\n{answer}"),
])

def faithfulness_evaluator(run, example):
    answer = run.outputs.get("answer", "")
    context = run.outputs.get("retrieval_context") or example.outputs.get("reference_facts", [])
    context_text = _coerce_context_to_text(context)
    question = example.inputs.get("question", "")

    if not answer:
        return {"key": "faithfulness", "score": 0.0}

    messages = FAITHFULNESS_PROMPT.format_messages(
        context=context_text,
        question=question,
        answer=answer,
    )
    response = judge_llm.invoke(messages).content.strip()

    try:
        start, end = response.find("{"), response.rfind("}") + 1
        parsed = json.loads(response[start:end])
        score = float(parsed.get("score", 0.5))
        reason = parsed.get("reason", "")
        return {"key": "faithfulness", "score": score, "comment": reason}
    except (json.JSONDecodeError, ValueError):
        return {"key": "faithfulness", "score": 0.5}
    
# Correctness eval checks the factual correctness of the answer by comparing it to the answer in the dataset.
CORRECTNESS_PROMPT = ChatPromptTemplate.from_messages([
    ("system",
     "You are a correctness evaluator. Compare the AI's answer to the expected answer.\n\n"
     "Score 1.0 = all facts correct\n"
     "Score 0.5 = some facts correct but others are incorrect or too vague\n"
     "Score 0.0 = key facts wrong or missing\n\n"
     "Focus on factual accuracy, not exact wording."
     'Respond ONLY with JSON: {{"score": <float>, "reason": "<one sentence>"}}'),
    ("human",
     "Question: {question}\n\nExpected: {expected}\n\nActual: {actual}"),
])


def correctness_evaluator(run, example):
    actual = run.outputs.get("answer", "")
    expected = example.outputs.get("expected_answer", "")
    question = example.inputs.get("question", "")

    if not actual or not expected:
        return {"key": "correctness", "score": 0.0}

    messages = CORRECTNESS_PROMPT.format_messages(
        question=question, expected=expected, actual=actual
    )
    response = judge_llm.invoke(messages).content.strip()

    try:
        start, end = response.find("{"), response.rfind("}") + 1
        parsed = json.loads(response[start:end])
        score = float(parsed.get("score", 0.5))
        reason = parsed.get("reason", "")
        return {"key": "correctness", "score": score, "comment": reason}
    except (json.JSONDecodeError, ValueError):
        return {"key": "correctness", "score": 0.5}


# Checks wehather the graph chose the right specialist or agents:
    # route_exact_match == the returned routes must exactly equal the expected routes
    # route_subset_match == the expected routes must be present in the actual routes (useful if the graph is noisy)
def routing_evaluator(run, example):
    expected_routes = _normalize_routes(example.outputs.get("expected_routes", []))
    actual_routes = _normalize_routes(run.outputs.get("route", []))
    return {
        "key": "route_exact_match",
        "score": _bool_score(actual_routes == expected_routes, True),
        "comment": f"expected={expected_routes} actual={actual_routes}",
    }


def evaluate_routing_case(case: dict[str, Any], run_output: dict[str, Any]) -> dict[str, float]:
    expected_routes = _normalize_routes(case.get("expected_routes", []))
    actual_routes = _normalize_routes(run_output.get("route", []))
    return {
        "route_exact_match": _bool_score(actual_routes == expected_routes, True),
        "route_subset_match": _bool_score(set(expected_routes).issubset(set(actual_routes)), True),
    }

def run_langsmith_grounding_eval() -> dict[str, Any]:
    dataset_name = ensure_grounding_dataset()
    evaluate(
        run_case,
        data=dataset_name,
        evaluators=[faithfulness_evaluator],
        experiment_prefix="home-buddy-grounding",
        metadata={"suite": "grounding", "judge_model": settings.testing_openai_model},
    )
    return {
        "dataset": dataset_name,
        "experiment_prefix": "home-buddy-grounding",
        "submitted": True,
    }


def run_langsmith_routing_eval() -> dict[str, Any]:
    dataset_name = ensure_routing_dataset()
    evaluate(
        run_case,
        data=dataset_name,
        evaluators=[routing_evaluator],
        experiment_prefix="home-buddy-routing",
        metadata={"suite": "routing", "model": settings.openai_model},
    )
    return {
        "dataset": dataset_name,
        "experiment_prefix": "home-buddy-routing",
        "submitted": True,
    }

def run_langsmith_correctness_eval() -> dict[str, Any]:
    dataset_name = ensure_correctness_dataset()
    evaluate(
        run_case,
        data=dataset_name,
        evaluators=[correctness_evaluator],
        experiment_prefix="home-buddy-correctness",
        metadata={"suite": "correctness", "judge_model": settings.testing_openai_model},
    )
    return {
        "dataset": dataset_name,
        "experiment_prefix": "home-buddy-correctness",
        "submitted": True,
    }

# Check whether hazardous inputs sray on the safety path and carry the right escalation metadata:
    # route_exact_match == did we correctly route to the safety agent
    # should_escalate == did the response mark the situation as escalation worthy
    # urgency_match == did the response set the expected urgency (ie "critical" vs "high")
def evaluate_safety_case(case: dict[str, Any], run_output: dict[str, Any]) -> dict[str, float]:
    return {
        "should_escalate": _bool_score(run_output["should_escalate"], case["expected_should_escalate"]),
        "urgency_match": _bool_score(run_output["urgency_level"] == case["expected_urgency_level"], True),
    }

# Check if the answer is grounded in the indexed docs
    # route_exact_match == did the 
def evaluate_grounding_case(case: dict[str, Any], run_output: dict[str, Any]) -> dict[str, float]:
    answer = run_output["answer"]
    citation_count = _count_grounded_retrieval_items(run_output["retrieval_context"])
    return {
        "citations_present": 1.0 if citation_count >= case.get("min_citations", 1) else 0.0,
        "expected_fact_coverage": _contains_all_expected_keywords(answer, case.get("expected_keywords", [])),
    }


def evaluate_correctness_case(case: dict[str, Any], run_output: dict[str, Any]) -> dict[str, float]:
    answer = run_output["answer"]
    expected_answer = case.get("expected_answer", "")
    return {
        "answer_present": 1.0 if answer else 0.0,
        "expected_answer_keyword_coverage": _contains_all_expected_keywords(
            answer,
            expected_answer.split(),
        ) if expected_answer else 0.0,
    }


def evaluate_workflow_case(case: dict[str, Any], run_output: dict[str, Any]) -> dict[str, float]:
    contractor_count = len(run_output["contractor_suggestions"])
    return {
    
        "case_draft_presence": _bool_score(bool(run_output["case_draft"]), case["expect_case_draft"]),
        "task_draft_presence": _bool_score(bool(run_output["task_draft"]), case["expect_task_draft"]),
        "contractor_presence": 1.0 if contractor_count >= case.get("min_contractor_suggestions", 0) else 0.0,
    }


def evaluate_case(suite_name: str, case: dict[str, Any], run_output: dict[str, Any]) -> dict[str, float]:
    if suite_name == "routing":
        return evaluate_routing_case(case, run_output)
    if suite_name == "safety":
        return evaluate_safety_case(case, run_output)
    if suite_name == "grounding":
        return evaluate_grounding_case(case, run_output)
    if suite_name == "correctness":
        return evaluate_correctness_case(case, run_output)
    if suite_name == "workflow":
        return evaluate_workflow_case(case, run_output)
    raise ValueError(f"Unknown suite: {suite_name}")


def maybe_run_deepeval(case: dict[str, Any], run_output: dict[str, Any]) -> dict[str, float]:
    """Optional LLM-judge scoring.

    This is deliberately opt-in because it makes remote model calls and is slower.
    Enable with:
        ENABLE_LLM_JUDGE_EVALS=true python3 tests/run_evals.py grounding
    """
    if os.getenv("ENABLE_LLM_JUDGE_EVALS", "").lower() != "true":
        return {}

    try:
        from deepeval.metrics import AnswerRelevancyMetric, FaithfulnessMetric
        from deepeval.test_case import LLMTestCase
    except Exception:
        return {"deepeval_available": 0.0}

    test_case = LLMTestCase(
        input=case["question"],
        actual_output=run_output["answer"],
        expected_output=case.get("expected_answer", ""),
        retrieval_context=case.get("reference_facts", []),
    )

    faithfulness_metric = FaithfulnessMetric(threshold=0.8)
    answer_relevancy_metric = AnswerRelevancyMetric(threshold=0.8)

    faithfulness_metric.measure(test_case)
    answer_relevancy_metric.measure(test_case)

    return {
        "deepeval_faithfulness": faithfulness_metric.score or 0.0,
        "deepeval_answer_relevancy": answer_relevancy_metric.score or 0.0,
    }


def run_suite(name: str) -> dict[str, Any]:
    cases = _load_cases(name)
    suite_results: list[dict[str, Any]] = []

    for case in cases:
        run_output = run_case(case)
        scores = evaluate_case(name, case, run_output)
        scores.update(maybe_run_deepeval(case, run_output))
        suite_results.append(
            {
                "id": case["id"],
                "question": case["question"],
                "scores": scores,
                "output": {
                    "route": run_output["route"],
                    "route_confidence": run_output["route_confidence"],
                    "should_escalate": run_output["should_escalate"],
                    "urgency_level": run_output["urgency_level"],
                    "citation_count": _count_grounded_retrieval_items(run_output["retrieval_context"]),
                    "has_case_draft": bool(run_output["case_draft"]),
                    "has_task_draft": bool(run_output["task_draft"]),
                    "contractor_count": len(run_output["contractor_suggestions"]),
                    "answer_preview": run_output["answer"][:280],
                },
            }
        )

    metric_names = sorted(
        {
            metric_name
            for result in suite_results
            for metric_name in result["scores"].keys()
        }
    )
    aggregates = {
        metric_name: mean(result["scores"][metric_name] for result in suite_results)
        for metric_name in metric_names
    }

    return {
        "suite": name,
        "cases": len(cases),
        "metrics": aggregates,
        "results": suite_results,
        "langsmith": (
            run_langsmith_routing_eval()
            if name == "routing"
            else run_langsmith_grounding_eval()
            if name == "grounding"
            else run_langsmith_correctness_eval()
            if name == "correctness"
            else None
        ),
    }


def run_all_suites() -> dict[str, Any]:
    suite_names = ["routing", "safety", "grounding", "workflow", "correctness"]
    suites = {name: run_suite(name) for name in suite_names}
    return {
        "suites": suites,
        "notes": [
            "These evals are graph-backed and measure the current multi-agent workflow end to end.",
            "Grounding currently uses retrieval context + expected fact coverage, with optional DeepEval judge metrics behind ENABLE_LLM_JUDGE_EVALS=true.",
            "LangSmith tracing will capture each case run automatically when LangSmith env vars are enabled.",
        ],
    }
