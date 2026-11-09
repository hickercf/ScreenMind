"""Small provider boundary: deterministic demo or a tool-capable Chat Completions API."""
import asyncio
import json
import httpx
from .store import encode


class ProviderError(Exception):
    """Sanitized error safe to show in the console; never includes credentials or bodies."""


class MockProvider:
    mode = "mock"

    def __init__(self, delay=0.35, registry=None):
        from .agents import Registry
        self.delay = delay
        self.registry = registry or Registry()

    async def complete(self, messages, tools):
        await asyncio.sleep(self.delay)
        context = json.loads(messages[1]["content"])
        return self.registry.get(context.get("subagent_id", "information")).mock_reply(messages)

    @staticmethod
    def call(name, arguments):
        return {"role": "assistant", "content": None, "tool_calls": [{
            "id": "demo_" + name, "type": "function",
            "function": {"name": name, "arguments": encode(arguments)},
        }]}

    async def close(self):
        pass


class CompatibleProvider:
    mode = "openai-compatible"

    def __init__(self, settings, transport=None):
        self.settings = settings
        self.client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.model_timeout, connect=10),
            follow_redirects=False, transport=transport,
        )

    async def complete(self, messages, tools):
        payload = {"model": self.settings.model, "messages": messages, "tools": tools,
                   "tool_choice": "auto", "stream": False, "max_tokens": 4096}
        # Only transient failures are retried, at most twice. No raw error body is exposed.
        for attempt in range(3):
            try:
                response = await self.client.post(
                    self.settings.base_url + "/chat/completions", json=payload,
                    headers={"Authorization": "Bearer " + self.settings.model_key},
                )
                if response.status_code in {429, 500, 502, 503, 504} and attempt < 2:
                    await asyncio.sleep(0.5 * 2 ** attempt)
                    continue
                if response.status_code >= 300:
                    raise ProviderError(f"模型服务返回 HTTP {response.status_code}，请检查配置或稍后重试")
                data = response.json()
                message = data["choices"][0]["message"]
                if not isinstance(message, dict):
                    raise ValueError("Invalid message")
                # Some providers require returning reasoning_content with tool turns.
                return {k: v for k, v in message.items() if k in {"role", "content", "tool_calls", "reasoning_content"}}
            except (httpx.TimeoutException, httpx.TransportError):
                if attempt == 2:
                    raise ProviderError("模型连接失败或超时，已达到重试上限") from None
                await asyncio.sleep(0.5 * 2 ** attempt)
            except (ValueError, KeyError, IndexError, TypeError):
                raise ProviderError("模型响应格式不符合 Chat Completions 协议") from None
        raise ProviderError("模型请求失败")

    async def close(self):
        await self.client.aclose()

