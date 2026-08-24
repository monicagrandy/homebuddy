"""Agentic Query Engine: RAG orchestration over a pluggable LLM client.

Supports two orchestration modes, selected by `multihop`:
  - deterministic (multihop=False): for small/edge models. Local search always
    runs first and web search is a fixed fallback -- never left to the model's
    own tool-call judgment, since small local models are unreliable at native
    function-calling and otherwise either skip retrieval silently or emit a
    tool-call-shaped string as their literal answer.
  - multihop (multihop=True): for capable cloud models (e.g. gpt-4o-mini) that
    handle native tool-calling reliably. The model decides which tools to call,
    in what order, and can chain multiple hops before answering.
"""
import logging
import time

import httpx

from backend.config import get_logger
from rag.search_agent import SearchAgent
from rag.vector_store import VectorStore

logger = get_logger("Agentic Query Engine")


class QueryEngineError(Exception):
    """Base exception for all query engine failures."""


class AgenticQueryEngine:
    def __init__(
        self,
        vector_manager: VectorStore,
        search_agent: SearchAgent,
        llm_client,
        multihop: bool = False,
        max_hops: int = 4,
    ):
        """Injects the vector manager, safety guardrails, search agent, and the LLM client.

        llm_client must expose chat(messages, tools=None) -> {"raw_message", "content", "tool_calls"},
        e.g. rag.llm_clients.OllamaClient or rag.llm_clients.OpenAIClient.
        """
        self.vector_manager = vector_manager
        self.search_agent = search_agent
        self.llm_client = llm_client
        self.multihop = multihop
        self.max_hops = max_hops
        self.tools = [
            {
                "type": "function",
                "function": {
                    "name": "local_vector_search",
                    "description": "Searches the database of local appliance manuals for troubleshooting steps.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {"type": "string", "description": "The search terms (e.g. error code 12)."}
                        },
                        "required": ["query"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "web_search_agent",
                    "description": "Searches the web for troubleshooting articles if manuals do not contain the answer.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {"type": "string", "description": "The search terms to search the web for."}
                        },
                        "required": ["query"],
                    },
                },
            },
        ]

    def retrieve_local_documents(
        self,
        household_id: int,
        query: str,
        session_id: str,
        doc_type: str | None,
        entry_id: str = None,
        where: dict | None = None,
        n_results: int = 3,
    ) -> list[dict]:
        """Returns structured retrieval matches for agent-specific routing and filtering."""
        try:
            logger.info(
                "retrieve_local_documents query=%r session_id=%s household_id=%s entry_id=%r doc_type=%s where=%s n_results=%s provider=%s",
                query,
                session_id,
                household_id,
                entry_id,
                doc_type,
                where,
                n_results,
                self.vector_manager.__class__.__name__,
            )
            matches = self.vector_manager.query_chunks(
                household_id=household_id,
                query=query,
                session_id=session_id,
                doc_type=doc_type,
                entry_id=entry_id,
                where=where,
                n_results=n_results,
            )
            if not matches:
                logger.info("retrieve_local_documents no_matches query=%r", query)
                return []

            logger.info(
                "retrieve_local_documents matched=%s sources=%s",
                len(matches),
                [
                    {
                        "entry_id": match["metadata"].get("entry_id"),
                        "source": match["metadata"].get("source"),
                        "page": match["metadata"].get("page"),
                        "doc_type": match["metadata"].get("doc_type"),
                    }
                    for match in matches
                ],
            )
            return matches
        except Exception as exc:
            logger.error(f"Vector retrieval failed: {exc}")
            return []

    def local_vector_search(self, household_id: int, query: str, session_id: str, doc_type: str, entry_id: str = None) -> str:
        """Queries the vector store for matching manual pages."""
        matches = self.retrieve_local_documents(household_id, query, session_id, entry_id=entry_id, n_results=3, doc_type=doc_type)
        if not matches:
            logger.info(
                "local_vector_search no_matches query=%r household_id=%s session_id=%s entry_id=%r doc_type=%s",
                query,
                household_id,
                session_id,
                entry_id,
                doc_type,
            )
            return "No information found in local vector store."

        formatted_results = []
        for match in matches:
            meta = match["metadata"]
            formatted_results.append(
                f"[Source: {meta.get('source', 'unknown')}, Page {meta.get('page', 'n/a')}]:\n{match['text']}"
            )
        return "\n\n".join(formatted_results)

    def _dispatch_tool(self, household_id: int, func_name: str, args: dict, session_id: str, entry_id: str, doc_type: str) -> str:
        if func_name == "local_vector_search":
            return self.local_vector_search(household_id, args["query"], session_id, entry_id=entry_id, doc_type=doc_type)
        if func_name == "web_search_agent":
            return self.search_agent.execute_synthesized_search(args["query"])
        return "Unknown tool call"

    def ask(self, user_query: str, session_id: str, household_id: int, doc_type: str, entry_id: str = None,) -> tuple[str, list[str]]:
        """Filters input, gathers grounding context, and asks the LLM to synthesize an answer."""
       
        try:
            if self.multihop:
                return self._ask_multihop(household_id, user_query, session_id, entry_id, doc_type)
            return self._ask_deterministic(household_id, user_query, session_id, entry_id, doc_type)
    
        except httpx.RequestError as exc:
            logger.error(f"Failed to communicate with the LLM backend: {exc}")
            raise QueryEngineError(
                "The AI server is not responding. If you're running locally, make sure Ollama is running."
            ) from exc
        except Exception as exc:
            logger.error(f"Query engine loop failed: {exc}")
            raise QueryEngineError(f"An unexpected query engine error occurred: {exc}") from exc

    def _ask_deterministic(self, household_id: int, user_query: str, session_id: str, entry_id: str, doc_type: str) -> tuple[str, list[str]]:
        system_prompt = (
            "You are HomeBuddy, a household documentation manager and troubleshooter. Manual and/or web "
            "search results for the user's question are provided below. Base your answer on them whenever "
            "they are relevant, and cite your sources clearly using page numbers or web URLs."
        )

        t0 = time.perf_counter()
        local_context = self.local_vector_search(household_id, user_query, session_id, entry_id=entry_id, doc_type=doc_type)
        elapsed = time.perf_counter() - t0
        steps = [
            f"Tool call -> local_vector_search(query={user_query!r}, entry_id={entry_id!r}) [{elapsed:.2f}s]\nResult: {local_context[:500]}"
        ]

        context_sections = [f"[Local manual search results]\n{local_context}"]

        if local_context.startswith("No information found") or local_context.startswith(
            "Error reading local manuals database"
        ):
            t0 = time.perf_counter()
            web_context = self.search_agent.execute_synthesized_search(user_query)
            elapsed = time.perf_counter() - t0
            steps.append(
                f"Tool call -> web_search_agent(query={user_query!r}) [{elapsed:.2f}s]\nResult: {web_context[:500]}"
            )
            context_sections.append(f"[Web search results]\n{web_context}")
        else:
            steps.append("Local manuals had relevant results; skipping web search.")

        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": f"{user_query}\n\n" + "\n\n".join(context_sections)},
        ]

        t0 = time.perf_counter()
        result = self.llm_client.chat(messages)
        elapsed = time.perf_counter() - t0
        steps.append(f"LLM call -> synthesizing answer from gathered context [{elapsed:.2f}s]")
        return result["content"], steps

    def _ask_multihop(self, household_id: int, user_query: str, session_id: str, entry_id: str, doc_type: str) -> tuple[str, list[str]]:
        system_prompt = (
            "You are HomeBuddy, a household documentation manager and troubleshooter. You have access to "
            "local manuals search and web search tools. Use them to retrieve facts before answering, chaining "
            "multiple searches if a first result points you toward something more specific to look up. "
            "Always cite your sources clearly using page numbers or web URLs."
        )
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_query},
        ]

        steps = []
        for hop in range(self.max_hops):
            t0 = time.perf_counter()
            result = self.llm_client.chat(messages, tools=self.tools)
            elapsed = time.perf_counter() - t0
            steps.append(
                f"LLM call (hop {hop + 1}) -> tools offered={[t['function']['name'] for t in self.tools]} [{elapsed:.2f}s]"
            )

            if not result["tool_calls"]:
                steps.append("No further tool calls requested; returning answer.")
                return result["content"], steps

            messages.append(result["raw_message"])
            steps.append(
                f"LLM requested {len(result['tool_calls'])} tool call(s): {[tc['name'] for tc in result['tool_calls']]}"
            )

            for tool_call in result["tool_calls"]:
                t0 = time.perf_counter()
                tool_result = self._dispatch_tool(household_id, tool_call["name"], tool_call["arguments"], session_id, entry_id, doc_type)
                elapsed = time.perf_counter() - t0
                steps.append(
                    f"Tool call -> {tool_call['name']}(args={tool_call['arguments']}) [{elapsed:.2f}s]\nResult: {tool_result[:500]}"
                )
                messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": tool_call["id"],
                        "name": tool_call["name"],
                        "content": tool_result,
                    }
                )

        steps.append(
            f"Reached max hop limit ({self.max_hops}); requesting a final answer without further tool calls."
        )
        t0 = time.perf_counter()
        result = self.llm_client.chat(messages)
        elapsed = time.perf_counter() - t0
        steps.append(f"LLM call -> final answer after hop limit [{elapsed:.2f}s]")
        return result["content"], steps
