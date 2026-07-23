"""Sub-agent specialized in query formulation, web search, and search synthesis."""
import logging
import os
import time

import httpx

logger = logging.getLogger(__name__)

class SearchAgent:
    def __init__(self, llm_client, tavily_api_key: str = None):
        """Injects the LLM client used for query formulation/summary, and the Tavily API key
        used for web search.

        llm_client is injected rather than hardcoded to Ollama so this agent works the same
        way whether it's running on the mobile/edge tier (OllamaClient) or the cloud/Alexa
        tier (OpenAIClient) -- there's no local Ollama process to call from Lambda.
        """
        self.llm_client = llm_client
        self.tavily_api_key = tavily_api_key or os.environ.get("TAVILY_API_KEY")

    def _search_web(self, search_query: str) -> str:
        """Searches the web via Tavily's API.

        Replaces an earlier implementation that scraped DuckDuckGo's HTML directly with
        BeautifulSoup. That approach was fragile (breaks whenever DuckDuckGo changes its
        markup) and unreliable (DuckDuckGo actively rate-limits/blocks non-browser traffic),
        which is why web search kept silently coming back empty. Tavily is a search API
        built for LLM/agent use cases -- it returns clean, pre-extracted page content
        instead of raw HTML we'd have to scrape and parse ourselves.
        """
        try:
            resp = httpx.post(
                "https://api.tavily.com/search",
                json={"api_key": self.tavily_api_key, "query": search_query, "max_results": 3, "search_depth": "basic"},
                timeout=20,
            )
            resp.raise_for_status()
            results = resp.json().get("results", [])
            if not results:
                return "No results found."

            formatted = [f"Source: {r['url']}\nText: {r['content']}" for r in results]
            return "\n\n".join(formatted)
        except Exception as e:
            # Catch network/API errors during the search request
            logger.error(f"Tavily search failed: {e}")
            return f"Error executing web search: {e}"

    def execute_synthesized_search(self, user_question: str) -> str:
        """Runs query formulation, web search, and synthesizes a summary."""

        # --- STEP 1: Query Formulation / Keyword Extraction ---
        # Translate a long conversational question into optimized search keywords
        # (e.g. converting "why water is pooling at the bottom of my Whirlpool wrf535swhz?"
        # to "Whirlpool wrf535swhz water bottom leak troubleshooting").
        formulation_prompt = (
            f"You are a search query optimizer. Given the user's question: '{user_question}', "
            "write the single best search term to find troubleshooting manuals or fix guides. "
            "Respond with ONLY the search term and nothing else."
        )
        t0 = time.perf_counter()
        try:
            result = self.llm_client.chat([{"role": "user", "content": formulation_prompt}])
            optimized_query = (result["content"] or user_question).strip().strip('"')
        except Exception as e:
            # FALLBACK: if query formulation fails, search using the user's raw question directly.
            logger.warning(f"Query formulation failed: {e}. Falling back to raw user question.")
            optimized_query = user_question
        logger.info(f"[search_agent] query formulation took {time.perf_counter() - t0:.2f}s -> {optimized_query!r}")

        # --- STEP 2: Execute web search ---
        t0 = time.perf_counter()
        raw_results = self._search_web(optimized_query)
        logger.info(f"[search_agent] tavily search took {time.perf_counter() - t0:.2f}s")

        # --- STEP 3: Synthesis and Summary ---
        # Since raw snippets contain search noise, feed them back into the model to generate
        # a polished, cited response highlighting steps, fixes and URLs.
        summary_prompt = (
            f"You are a web search summarizing agent. Synthesize the following web search results "
            f"to answer the question: '{user_question}'. Highlight troubleshooting instructions and "
            f"provide source URLs.\n\nWeb Search Results:\n{raw_results}"
        )
        t0 = time.perf_counter()
        try:
            result = self.llm_client.chat([{"role": "user", "content": summary_prompt}])
            logger.info(f"[search_agent] summary synthesis took {time.perf_counter() - t0:.2f}s")
            return result["content"]
        except Exception as e:
            # FALLBACK: if synthesis fails, return the raw search snippets so the user still
            # gets links to check, rather than crashing.
            logger.error(f"Search agent synthesis failed: {e}. Returning raw search snippets.")
            return f"Could not generate AI search summary. Here are the matching links:\n\n{raw_results}"
